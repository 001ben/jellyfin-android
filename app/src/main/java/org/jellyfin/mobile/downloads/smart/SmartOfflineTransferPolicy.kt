package org.jellyfin.mobile.downloads.smart

import org.jellyfin.mobile.data.dao.UserDao
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemDto
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Revalidates current consent, identity and subscription during transfers and before publication. */
internal class SmartOfflineTransferPolicy(
    private val dao: SmartOfflineDao,
    private val users: UserDao,
    private val endpoints: SmartOfflineEndpoint,
    private val nanoTime: () -> Long = System::nanoTime,
) {
    suspend fun requireCurrent(account: SmartOfflineAccount) {
        val latest = dao.account(account.key)
        if (latest?.enabled != true || latest.copy(status = "") != account.copy(status = "")) {
            throw IOException("Settings changed")
        }
    }

    suspend fun subscribed(account: SmartOfflineAccount, item: BaseItemDto): Boolean =
        item.seriesId?.let { dao.isSubscribed(account.key, it.toString()) } == true

    fun permitted(
        account: SmartOfflineAccount,
        endpoint: VerifiedDownloadEndpoint,
        api: ApiClient,
        item: BaseItemDto,
    ): suspend () -> Boolean {
        var checkedAt: Long? = null
        var settingsAllowed = true
        return {
            val now = nanoTime()
            val previousCheck = checkedAt
            if (previousCheck == null || now - previousCheck >= TimeUnit.SECONDS.toNanos(1)) {
                val current = dao.account(account.key)
                settingsAllowed = current?.enabled == true && current.copy(status = "") == account.copy(status = "")
                val user = users.getUser(account.userId)
                settingsAllowed = settingsAllowed && user != null && user.serverId == account.serverId &&
                    user.accessToken == api.accessToken
                settingsAllowed = settingsAllowed && subscribed(account, item)
                checkedAt = now
            }
            settingsAllowed && endpoints.allowed(endpoint, account.wifiOnly)
        }
    }
}

internal fun transferFailure(error: IOException): String = when (error) {
    is StorageBudgetExceeded -> "Waiting for storage"
    is DownloadRejected -> "Server rejected download; waiting to retry"
    else -> "Transfer interrupted or network unavailable; waiting to retry"
}
