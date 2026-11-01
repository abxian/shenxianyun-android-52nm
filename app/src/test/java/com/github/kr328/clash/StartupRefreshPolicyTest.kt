package com.github.kr328.clash

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking

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
    @Test fun slowRefreshReleasesStartupButKeepsSharedTransactionAlive() = runBlocking {
        val refresh = CompletableDeferred<Boolean>()
        assertNull(awaitStartupRefresh(refresh, timeoutMillis = 20))
        assertTrue(refresh.isActive)
        refresh.complete(true)
        assertEquals(true, awaitStartupRefresh(refresh))
    }
    @Test fun failedRefreshDoesNotAuthorizeStartup() = runBlocking {
        assertEquals(false, awaitStartupRefresh(CompletableDeferred(false)))
    }
}
