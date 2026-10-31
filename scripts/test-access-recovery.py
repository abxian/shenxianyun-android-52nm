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
assert 'NET_CAPABILITY_NOT_VPN' in network[network.index('internal suspend fun Context.readManagedUpdateState'):]
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
