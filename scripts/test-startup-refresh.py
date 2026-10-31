"""276 integration guards for real UI request path and nonblocking startup.
Python3 only; no devices, network, credentials or production config writes.
Executable policy tests run with app:testMetaDebugUnitTest in CI.
"""
from pathlib import Path
import xml.etree.ElementTree as ET
r=Path(__file__).resolve().parents[1]
a=(r/'app/src/main/java/com/github/kr328/clash/MainActivity.kt').read_text()
d=(r/'design/src/main/java/com/github/kr328/clash/design/MainDesign.kt').read_text()
l=(r/'design/src/main/res/layout/design_main.xml').read_text();ET.fromstring(l)
assert 'if (starting && request != Request.UpdateSubscription) return' in d
assert 'setOf(Request.ToggleStatus, Request.ImportByCode, Request.UpdateSubscription)' in d
assert 'subscriptionStatus' in l and 'subscriptionUpdating' in l
assert '@{!clashRunning &amp;&amp; activationCode != null ? View.VISIBLE : View.GONE}' in l
main=a[a.index('override suspend fun main()'):a.index('val ticker =')]
assert 'launch { runCatching { EndpointResolver.initialize(); refreshLineStatus(design) } }' in main
assert 'design.requestSubscriptionRefresh(force = true)' in main
assert 'runCatching { design.fetch() }' not in main
refresh=a[a.index('private fun MainDesign.requestSubscriptionRefresh'):a.index('private suspend fun MainDesign.importSubscriptionCode')]
assert 'shouldReuseSubscriptionRefresh(subscriptionRefreshCode, code, existing?.isActive == true)' in refresh
assert 'setSubscriptionStatus("正在更新订阅' in refresh
assert 'setSubscriptionStatus("暂未更新，已保留原节点' in refresh
assert 'knownState = state' in a and 'val updateState = knownState ?: queryUpdateState(code)' in a
start=a[a.index('private suspend fun MainDesign.startClash()'):a.index('val vpnRequest = startClashService()')]
assert 'requestSubscriptionRefresh(force = true)' in start and 'refresh.await()' in start
assert 'withTimeoutOrNull(1_500)' not in start and 'KEY_ACCESS_RESTRICTION' in start
returned=a[a.index('Event.ActivityStart ->'):a.index('Event.ServiceRecreated,')]
assert 'requestSubscriptionRefresh' not in returned
assert 'openingRefreshRequested.compareAndSet(false, true)' in main
print('276 actual startup/request/status/coalescing/restriction integration guards passed')
