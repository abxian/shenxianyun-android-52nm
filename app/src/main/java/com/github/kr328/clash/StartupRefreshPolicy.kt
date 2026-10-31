package com.github.kr328.clash

/** Merge only an active same-code refresh; completed checks never skip Start. */
internal fun shouldReuseSubscriptionRefresh(inFlightCode: String, code: String, active: Boolean): Boolean =
    active && code.isNotBlank() && inFlightCode == code
