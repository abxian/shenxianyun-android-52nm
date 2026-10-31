"""271 source wiring invariants, no production network/profile/credential access."""
from pathlib import Path
r=Path(__file__).resolve().parents[1]
app=(r/'app/src/main/java/com/github/kr328/clash/MainActivity.kt').read_text()
assert 'sendClientTraffic(' not in app and 'nextTrafficReportAt' not in app
assert app.count('configureServiceTrafficReporting()')>=4
for name in ['TunService.kt','ClashService.kt']:
 s=(r/'service/src/main/java/com/github/kr328/clash/service'/name).read_text()
 assert s.count('ServiceTrafficAccounting.begin(self)')==1
 assert 'ServiceTrafficAccounting.begin(self)' in s and 'install(TrafficReportingModule(self))' in s
 assert s.index('ServiceTrafficAccounting.finish(self)')<s.index('stopSelf()',s.index('finally'))
s=(r/'service/src/main/java/com/github/kr328/clash/service/clash/module/ConfigurationModule.kt').read_text()
assert s.index('ServiceTrafficAccounting.sample(service)')<s.index('Clash.load(')
assert 'ServiceTrafficAccounting.profileLoaded' in s
secure=(r/'service/src/main/java/com/github/kr328/clash/service/traffic/TrafficSecureStore.kt').read_text()
assert 'noBackupFilesDir' in secure and 'AES/GCM/NoPadding' in secure and 'AtomicFile' in secure
s=(r/'service/src/main/java/com/github/kr328/clash/service/traffic/ServiceTrafficAccounting.kt').read_text()
assert 'instanceFollowRedirects=false' in s and 'NET_CAPABILITY_NOT_VPN' in s
assert 'persist(service)' in s and 'counter.accept(frozen,sequence)' in s
c=(r/'core/src/main/cpp/main.c').read_text().split('Bridge_nativeQueryTrafficTotalBytes',1)[1].split('JNIEXPORT',1)[0]
assert 'queryProxyTotal' in c
print('271 service ownership, encrypted state, byte bridge, and lifecycle invariants passed')
