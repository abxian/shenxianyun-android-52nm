#!/usr/bin/env python3
"""Static integration guards plus executable pure Kotlin policy regression.
Python3, Java, kotlinc required for --kotlin; no Android/user config/network IO.
"""
import pathlib,subprocess,tempfile,sys
r=pathlib.Path(__file__).resolve().parents[1]
s=(r/'app/src/main/java/com/github/kr328/clash/MainActivity.kt').read_text()
network=(r/'app/src/main/java/com/github/kr328/clash/ManagedProfile.kt').read_text()
layout=(r/'design/src/main/res/layout/design_main.xml').read_text()
assert '(clashRunning || activityStarted)' in s
assert 'KEY_SUBSCRIPTION_REVISION' in s and 'KEY_ACCESS_RESTRICTION' in s
assert 'val updateState = knownState ?: queryUpdateState(code)\n            val content = withTimeout(20_000) { fetchManagedSubscription(credentials) }' in s
assert 'error !is ManagedSubscriptionUnauthorized' not in s, 'Do not silently reauthorize revoked credentials'
assert 'readManagedUpdateState(code, stableClientId())' in s
assert 'if (status == 403 || status == 404) throw AccessStateRejected' in s
# State checks now delegate to the shared management transport. Follow that
# wiring: merely keeping the old function-name-local text check rejects the
# safe refactor, while deleting the check could hide VPN-dependent requests.
transport=(r/'service/src/main/java/com/github/kr328/clash/service/traffic/RelayTransport.kt').read_text()
state=network[network.index('internal suspend fun Context.readManagedUpdateState'):]
compact=lambda text: ''.join(text.split())
assert 'RelayTransport.entry(' in state and '"state"' in state, 'State must use shared management transport'
request=transport[transport.index('fun request('):transport.index('private fun grant(')]
assert 'hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)' in compact(request)
assert 'hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)' in compact(request), 'Underlying routes must exclude VPN networks'
assert 'for(networkinunderlying+listOf(null))' in compact(request), 'Underlying network must be attempted before default routing'
assert 'network?.openConnection(target)' in compact(request), 'Selected underlying network must own the socket'
assert 'instanceFollowRedirects=false' in compact(request), 'Do not forward managed credentials through redirects'
entry=transport[transport.index('fun entry('):transport.index('fun enqueue(')]
assert '"state"->"/api/v2/client/state"' in compact(entry)
assert 'request(context,apiBase.trimEnd' in compact(entry) and '/v1/client/$TENANT/$operation' in entry
assert 'primary.status<500' in compact(entry), 'Business auth failures must not replay through fallback'
assert 'renewalNeeded' in layout and 'Request.RenewCode' in layout
assert 'activationCode == null ? View.GONE : View.VISIBLE' in layout
assert '!clashRunning || activationExpiresAt == null' not in layout
assert '@string/activation_expiry_pending' in layout
assert 'lastSubscriptionUpdateCheck = 0L' in s
assert 'recoverFromExpired' not in s, 'Old token-rotating recovery must be removed'
assert s.index('if (code.isNotBlank() && onCodeTrack)') < s.index('requestSubscriptionRefresh(force = true)',s.index('if (code.isNotBlank() && onCodeTrack)')) < s.index('val vpnRequest = startClashService()')
print('PASS: Android integration guards (stopped checks, foreground wake, pre-download revision, original credential, rejection classification, underlying network, renewal entry, startup authority)')
if '--kotlin' in sys.argv:
    with tempfile.TemporaryDirectory(prefix='sxy-recovery-policy-') as tmp:
        p=pathlib.Path(tmp); (p/'Check.kt').write_text('''package com.github.kr328.clash
fun main() {
    fun checkPolicy(expected: Boolean, startup: Boolean=false, restricted: Boolean=false, expiry: Boolean=false, local: Long=100, remote: Long=100, before: String?="old", after: String?="old") {
        check(shouldRefreshAccessSubscription(startup,restricted,expiry,local,remote,before,after)==expected)
    }
    checkPolicy(true,startup=true)
    checkPolicy(true,restricted=true)
    checkPolicy(true,expiry=true)
    checkPolicy(true,remote=10,after="moved")
    checkPolicy(true,remote=101,after=null)
    checkPolicy(false)
    checkPolicy(false,remote=10,after=null)
    checkPolicy(true,before=null,after="first")
    println("PASS: 8 executable production Kotlin recovery decisions")
}
''')
        subprocess.run(['kotlinc',str(r/'app/src/main/java/com/github/kr328/clash/AccessRecoveryPolicy.kt'),str(p/'Check.kt'),'-include-runtime','-d',str(p/'check.jar')],check=True)
        subprocess.run(['java','-jar',str(p/'check.jar')],check=True)
