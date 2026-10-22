package org.jellyfin.mobile.downloads.smart

import android.content.Context
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.util.AuthorizationHeaderBuilder
import org.jellyfin.sdk.model.api.BaseItemDto
import org.jellyfin.sdk.model.api.ImageType

/** Captured credentials stay out of URLs, cache keys, persistent records and generated toString output. */
class SmartOfflineArtwork internal constructor(
    val url: String,
    val cacheKey: String,
    private val authorization: String,
    val allowNetwork: Boolean,
) {
    fun request(context: Context): ImageRequest = ImageRequest.Builder(context)
        .data(url)
        .memoryCacheKey(cacheKey)
        .diskCacheKey(cacheKey)
        .networkCachePolicy(if (allowNetwork) CachePolicy.ENABLED else CachePolicy.DISABLED)
        .httpHeaders(NetworkHeaders.Builder().set("Authorization", authorization).build())
        .apply {
            if (url.startsWith("https://") || url.startsWith("http://")) {
                fetcherFactory(OkHttpNetworkFetcherFactory(callFactory = { artworkClient }))
            }
        }
        .build()

    companion object {
        private const val POSTER_WIDTH = 160
        private val artworkClient = OkHttpClient.Builder()
            .followRedirects(false)
            .followSslRedirects(false)
            .build()

        fun capture(
            account: String,
            api: ApiClient,
            item: BaseItemDto,
            allowNetwork: Boolean,
        ): SmartOfflineArtwork? {
            val tag = item.imageTags?.get(ImageType.PRIMARY) ?: return null
            val base = api.baseUrl?.toHttpUrl() ?: return null
            val url = base.newBuilder().username("").password("").query(null).fragment(null)
                .addPathSegments("Items/${item.id}/Images/Primary")
                .addQueryParameter("tag", tag)
                .addQueryParameter("maxWidth", POSTER_WIDTH.toString())
                .build()
            val header = AuthorizationHeaderBuilder.buildHeader(
                clientName = api.clientInfo.name,
                clientVersion = api.clientInfo.version,
                deviceId = api.deviceInfo.id,
                deviceName = api.deviceInfo.name,
                accessToken = api.accessToken,
            )
            return SmartOfflineArtwork(url.toString(), "smart-poster/$account/${item.id}/$tag", header, allowNetwork)
        }
    }
}
