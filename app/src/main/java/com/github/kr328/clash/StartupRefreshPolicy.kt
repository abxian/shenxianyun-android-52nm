package com.github.kr328.clash

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.withTimeoutOrNull

/** Merge only an active same-code refresh; completed checks never skip Start. */
internal fun shouldReuseSubscriptionRefresh(inFlightCode: String, code: String, active: Boolean): Boolean =
    active && code.isNotBlank() && inFlightCode == code

/** Bound the UI wait, not the shared refresh transaction. Never cancel a profile commit. */
internal suspend fun awaitStartupRefresh(
    refresh: Deferred<Boolean>,
    timeoutMillis: Long = 5_000L,
): Boolean? = withTimeoutOrNull(timeoutMillis) { refresh.await() }
