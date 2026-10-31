package com.github.kr328.clash

/** Pure policy shared by foreground startup and periodic recovery checks. */
internal fun shouldRefreshAccessSubscription(
    startup: Boolean,
    restricted: Boolean,
    expiryChanged: Boolean,
    localVersion: Long,
    remoteVersion: Long,
    localRevision: String?,
    remoteRevision: String?,
): Boolean = startup || restricted || expiryChanged ||
    (!remoteRevision.isNullOrBlank() && remoteRevision != localRevision) || remoteVersion > localVersion
