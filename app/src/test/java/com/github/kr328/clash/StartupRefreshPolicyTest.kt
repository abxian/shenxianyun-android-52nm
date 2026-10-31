package com.github.kr328.clash

import org.junit.Assert.*
import org.junit.Test

class StartupRefreshPolicyTest {
    @Test fun sameCodeInFlightDownloadIsShared() {
        assertTrue(shouldReuseSubscriptionRefresh("current", "current", true))
    }
    @Test fun aCompletedCheckNeverSkipsTheNextStartRefresh() {
        assertFalse(shouldReuseSubscriptionRefresh("current", "current", false))
        assertFalse(shouldReuseSubscriptionRefresh("old", "current", true))
        assertFalse(shouldReuseSubscriptionRefresh("", "", true))
    }
    @Test fun forcedRefreshDownloadsEvenWhenBackendVersionDidNotChange() {
        assertTrue(shouldRefreshAccessSubscription(true, false, false, 5, 5, "same", "same"))
        assertFalse(shouldRefreshAccessSubscription(false, false, false, 5, 5, "same", "same"))
    }
}
