package org.jellyfin.mobile.utils

import androidx.media3.datasource.DataSpec

/** Local video and sidecars have no server UUID path; each complete URI identifies a different cache resource. */
internal fun playbackCacheKey(spec: DataSpec): String = spec.key ?: when (spec.uri.scheme) {
    "file", "content" -> spec.uri.toString()
    else -> spec.uri.extractId()
}
