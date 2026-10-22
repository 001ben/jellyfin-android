package org.jellyfin.mobile.bridge

import android.net.Uri
import android.webkit.WebView
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jellyfin.mobile.data.entity.ServerEntity
import org.jellyfin.mobile.downloads.smart.SmartOfflineAccount
import org.jellyfin.mobile.downloads.smart.SmartOfflineManager
import org.jellyfin.mobile.events.ActivityEvent
import org.jellyfin.mobile.events.ActivityEventHandler
import org.jellyfin.mobile.player.interaction.PlayOptions
import org.jellyfin.mobile.player.source.LocalPlaybackResolver
import org.jellyfin.sdk.model.serializer.toUUIDOrNull

/** A small, origin-bound bridge. Older WebViews keep the native Downloads entry point. */
class SmartOfflineWebBridge(
    private val server: ServerEntity,
    private val scope: CoroutineScope,
    private val session: SmartOfflineWebSession,
    private val manager: SmartOfflineManager,
    private val local: LocalPlaybackResolver,
    private val events: ActivityEventHandler,
) : WebViewCompat.WebMessageListener {
    private val mutex = Mutex()

    fun attach(webView: WebView) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        val origin = SmartOfflineWebPolicy.origin(server.hostname) ?: return
        WebViewCompat.addWebMessageListener(webView, "SmartOfflineBridge", setOf(origin), this)
    }

    override fun onPostMessage(
        view: WebView,
        message: WebMessageCompat,
        sourceOrigin: Uri,
        isMainFrame: Boolean,
        replyProxy: JavaScriptReplyProxy,
    ) {
        if (!SmartOfflineWebPolicy.accepts(server.hostname, sourceOrigin.toString(), isMainFrame)) return
        if (message.type != WebMessageCompat.TYPE_STRING) return
        val request = SmartOfflineWebPolicy.parse(message.data.orEmpty()) ?: return
        scope.launch {
            if (!mutex.tryLock()) {
                respond(replyProxy, SmartOfflineWebReply(request.requestId, error = "Busy"))
                return@launch
            }
            try {
                handle(request, replyProxy)
            } finally {
                mutex.unlock()
            }
        }
    }

    private suspend fun handle(request: SmartOfflineWebRequest, reply: JavaScriptReplyProxy) {
        try {
            val user = withContext(Dispatchers.IO) { session.capture(request) } ?: return
            val account = manager.current(server.id, user.id) ?: return
            if (account.serverIdentity.isNotBlank() &&
                !SmartOfflineWebPolicy.sameId(account.serverIdentity, request.serverId)
            ) {
                return
            }
            val items = withContext(Dispatchers.IO) { snapshot(request, account) }
            if (withContext(Dispatchers.IO) { session.capture(request) } != user) return
            dispatch(request, account)
            respond(reply, SmartOfflineWebReply(request.requestId, items))
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // Do not expose endpoint URLs, filenames, tokens, or exception messages to the page.
            respond(reply, SmartOfflineWebReply(request.requestId, error = "Unavailable"))
        }
    }

    private fun respond(reply: JavaScriptReplyProxy, content: SmartOfflineWebReply) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            // Navigation may discard the requesting frame while its local lookup is suspended.
            runCatching { reply.postMessage(Json.encodeToString(content)) }
        }
    }

    private suspend fun snapshot(
        request: SmartOfflineWebRequest,
        account: SmartOfflineAccount,
    ): List<SmartOfflineWebItem> {
        if (request.action != "snapshot") return emptyList()
        val catalog = manager.observeCatalog(account.serverId, account.userId).first().associateBy { it.item.id }
        val selected = manager.series(account).map { it.seriesId.toUUIDOrNull() }.toSet()
        return request.ids().map { id ->
            val entry = catalog[id]
            val canPlay = local.canPlay(id, account.serverId, account.userId)
            SmartOfflineWebItem(
                id = id.toString(),
                seriesId = entry?.seriesId,
                phase = entry?.phase?.name ?: if (canPlay) "COMPLETE" else "",
                progress = entry?.totalBytes?.takeIf { it > 0 }?.let {
                    (entry.downloadedBytes.toDouble() / it).coerceIn(0.0, 1.0)
                },
                watched = entry?.watched == true,
                pendingSync = entry?.pendingSync == true,
                cleanupEligible = entry?.cleanupEligible == true,
                selected = id in selected || entry?.selected == true,
                canPlayLocal = canPlay,
            )
        }
    }

    private suspend fun dispatch(request: SmartOfflineWebRequest, account: SmartOfflineAccount) {
        when (request.action) {
            "manage" -> check(
                events.tryEmit(
                    ActivityEvent.OpenSmartOffline(
                        requireNotNull(request.seriesId?.toUUIDOrNull()),
                        account.serverId,
                        account.userId,
                    ),
                ),
            )
            "play" -> {
                val user = withContext(Dispatchers.IO) { session.capture(request) }
                requireNotNull(user)
                val id = requireNotNull(request.itemId?.toUUIDOrNull())
                val candidate = withContext(Dispatchers.IO) { local.resolve(id, account.serverId, account.userId) }
                requireNotNull(candidate) { "Downloaded file is no longer available" }
                // Recheck identity after filesystem work. The player acquires its own managed-file lease.
                require(withContext(Dispatchers.IO) { session.capture(request) } == user)
                check(
                    events.tryEmit(
                        ActivityEvent.LaunchNativePlayer(
                            PlayOptions(
                                ids = listOf(id), mediaSourceId = id.toString(), startIndex = 0, startPosition = null,
                                audioStreamIndex = null, subtitleStreamIndex = null, playFromDownloads = true,
                                accountServerId = account.serverId, accountUserId = account.userId,
                            ),
                        ),
                    )
                )
            }
        }
    }
}
