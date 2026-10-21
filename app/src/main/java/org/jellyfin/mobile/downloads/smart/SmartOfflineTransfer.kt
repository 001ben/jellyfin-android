package org.jellyfin.mobile.downloads.smart

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection.HTTP_PARTIAL

private const val HTTP_RANGE_NOT_SATISFIABLE = 416
private const val TRANSFER_BUFFER_SIZE = 64 * 1024
private const val CONTENT_RANGE_START = 1
private const val CONTENT_RANGE_END = 2
private const val CONTENT_RANGE_TOTAL = 3
private val contentRangePattern = Regex("bytes (\\d+)-(\\d+)/(\\d+)")

class DownloadRejected(val status: Int) : IOException("Download HTTP status $status")
class StorageBudgetExceeded : IOException("Smart download storage limit reached")

data class SmartOfflineTransferRequest(
    val endpoint: VerifiedDownloadEndpoint,
    val api: ApiClient,
    val managed: SmartOfflineManaged,
    val file: File,
    val budget: Long,
)

private data class TransferRange(val start: Long, val total: Long) {
    fun validFor(responseCode: Int, offset: Long): Boolean {
        val hasLength = start >= 0 && total > start
        val correctStart = responseCode != HTTP_PARTIAL || start == offset
        return hasLength && correctStart
    }
}

class SmartOfflineTransfer {
    /** A strong ETag and If-Range prevent appending bytes from a changed server file. */
    suspend fun download(
        transfer: SmartOfflineTransferRequest,
        permitted: suspend () -> Boolean,
        saveValidator: suspend (String?) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        checkPermitted(permitted)
        val offset = resumeOffset(transfer)
        val request = createRequest(transfer, offset)
        transfer.endpoint.client.newCall(request).execute().use { response ->
            if (isCompletedRange(response, transfer, offset)) {
                checkBudget(offset, transfer.budget)
                return@withContext offset
            }
            val range = validateResponse(response, transfer, offset, saveValidator)
            checkStorage(range, transfer)
            saveResponse(response, range, transfer, permitted, saveValidator)
        }
    }

    private fun resumeOffset(transfer: SmartOfflineTransferRequest): Long =
        if (transfer.managed.etag.isStrongValidator()) transfer.file.length() else 0L

    private fun createRequest(transfer: SmartOfflineTransferRequest, offset: Long): Request {
        val api = transfer.api
        val url = transfer.endpoint.url.newBuilder()
            .addPathSegments("Items/${transfer.managed.item.id}/Download").build()
        return Request.Builder().url(url).header(
            "Authorization",
            AuthorizationHeaderBuilder.buildHeader(
                clientName = api.clientInfo.name,
                clientVersion = api.clientInfo.version,
                deviceId = api.deviceInfo.id,
                deviceName = api.deviceInfo.name,
                accessToken = api.accessToken,
            ),
        ).header("Accept-Encoding", "identity").apply {
            if (offset > 0) {
                header("Range", "bytes=$offset-")
                header("If-Range", requireNotNull(transfer.managed.etag))
            }
        }.build()
    }

    private fun isCompletedRange(response: Response, transfer: SmartOfflineTransferRequest, offset: Long): Boolean {
        if (response.code != HTTP_RANGE_NOT_SATISFIABLE || offset <= 0) return false
        val sameValidator = response.header("ETag") == transfer.managed.etag
        val completeLength = response.header("Content-Range") == "bytes */$offset"
        return sameValidator && completeLength
    }

    private suspend fun validateResponse(
        response: Response,
        transfer: SmartOfflineTransferRequest,
        offset: Long,
        saveValidator: suspend (String?) -> Unit,
    ): TransferRange {
        if (!response.isSuccessful) throw DownloadRejected(response.code)
        val range = parseRange(response)
        val invalidReason = invalidRangeReason(response, transfer, offset, range)
        if (invalidReason != null) {
            saveValidator(null)
            throw IOException(invalidReason)
        }
        checkEncoding(response)
        return requireNotNull(range)
    }

    private fun parseRange(response: Response): TransferRange? {
        if (response.code != HTTP_PARTIAL) return response.body?.contentLength()?.let { TransferRange(0, it) }
        val match = response.header("Content-Range")?.let(contentRangePattern::matchEntire) ?: return null
        val start = match.groupValues[CONTENT_RANGE_START].toLongOrNull() ?: return null
        val total = match.groupValues[CONTENT_RANGE_TOTAL].toLongOrNull() ?: return null
        val end = match.groupValues[CONTENT_RANGE_END].toLongOrNull()
        val bodyLength = response.body?.contentLength()
        val lengthMatches = bodyLength == null || bodyLength < 0 || bodyLength == total - start
        if (end != total - 1 || !lengthMatches) return null
        return TransferRange(start, total)
    }

    private fun invalidRangeReason(
        response: Response,
        transfer: SmartOfflineTransferRequest,
        offset: Long,
        range: TransferRange?,
    ): String? = when {
        range?.validFor(response.code, offset) != true -> "Invalid download range or length"
        response.code == HTTP_PARTIAL && response.header("ETag") != transfer.managed.etag ->
            "Download validator changed"
        else -> null
    }

    private fun checkEncoding(response: Response) {
        if (response.header("Content-Encoding")?.let { it != "identity" } == true) {
            throw IOException("Unexpected content encoding")
        }
    }

    private fun checkStorage(range: TransferRange, transfer: SmartOfflineTransferRequest) {
        checkBudget(range.total, transfer.budget)
        val available = SmartOfflineDiskSpace.available(requireNotNull(transfer.file.parentFile)) -
            SmartOfflinePolicy.FREE_SPACE_RESERVE
        if (range.total - range.start > available) throw StorageBudgetExceeded()
    }

    private fun checkBudget(bytes: Long, budget: Long) {
        if (bytes > budget) throw StorageBudgetExceeded()
    }

    private suspend fun saveResponse(
        response: Response,
        range: TransferRange,
        transfer: SmartOfflineTransferRequest,
        permitted: suspend () -> Boolean,
        saveValidator: suspend (String?) -> Unit,
    ): Long = RandomAccessFile(transfer.file, "rw").use { output ->
        if (range.start == 0L) output.setLength(0)
        // Persist only after truncating: a process death cannot associate a new validator with old bytes.
        saveValidator(response.header("ETag")?.takeIf { it.isStrongValidator() })
        output.seek(range.start)
        val input = response.body?.byteStream() ?: throw IOException("Missing download body")
        val written = input.use { copyBody(it, output, range, transfer, permitted) }
        output.fd.sync()
        if (written != range.total) throw IOException("Interrupted download")
        output.setLength(range.total)
        range.total
    }

    private suspend fun copyBody(
        input: InputStream,
        output: RandomAccessFile,
        range: TransferRange,
        transfer: SmartOfflineTransferRequest,
        permitted: suspend () -> Boolean,
    ): Long {
        val buffer = ByteArray(TRANSFER_BUFFER_SIZE)
        var written = range.start
        while (true) {
            currentCoroutineContext().ensureActive()
            checkPermitted(permitted)
            val read = input.read(buffer)
            if (read < 0) break
            checkWriteCapacity(written, read, range.total, transfer)
            output.write(buffer, 0, read)
            written += read
        }
        return written
    }

    private fun checkWriteCapacity(written: Long, read: Int, total: Long, transfer: SmartOfflineTransferRequest) {
        checkBudget(written, minOf(total, transfer.budget) - read)
        val available = SmartOfflineDiskSpace.available(requireNotNull(transfer.file.parentFile))
        if (available < SmartOfflinePolicy.FREE_SPACE_RESERVE + read) throw StorageBudgetExceeded()
    }

    private suspend fun checkPermitted(permitted: suspend () -> Boolean) {
        if (!permitted()) throw IOException("Download paused by network policy")
    }
}

private fun String?.isStrongValidator(): Boolean = !isNullOrBlank() && !startsWith("W/")
