#!/usr/bin/env python3
"""Inspect the actual APK before making it the delivery artifact."""
import json
from pathlib import Path
import re
import struct
import subprocess
import sys
import zipfile

apk=Path(sys.argv[1])
sdk=Path(sys.argv[2])
tools=sdk/'build-tools/36.0.0'
def run(args):
    result=subprocess.run([str(x) for x in args],capture_output=True,text=True,check=True)
    return result.stdout
run([tools/'apksigner','verify','--verbose',apk])
run([tools/'zipalign','-c','-P','16','4',apk])
badging=run([tools/'aapt','dump','badging',apk])
manifest=run([tools/'aapt','dump','xmltree',apk,'AndroidManifest.xml'])
permissions=re.findall(r"uses-permission: name='([^']+)'",badging)
assert 'android.permission.INTERNET' not in permissions, 'APK still requests INTERNET'
assert not set(permissions) & {'android.permission.READ_LOGS', 'android.permission.READ_EXTERNAL_STORAGE', 'android.permission.WRITE_EXTERNAL_STORAGE', 'android.permission.MANAGE_EXTERNAL_STORAGE', 'android.permission.QUERY_ALL_PACKAGES', 'android.permission.READ_SMS', 'android.permission.RECEIVE_SMS'}, 'Broad logs/storage permissions are forbidden'
assert 'android.permission.POST_NOTIFICATIONS' in permissions
assert "name='org.ffortuna.capturepoc'" in badging
assert "application-label:'Tique" in badging, 'App display name must be Tique'
assert 'application-debuggable' not in badging
assert re.search(r'android:allowBackup[^\n]*0x0\s*$',manifest,re.M), 'Backup must be disabled'
assert 'android:dataExtractionRules' in manifest and 'android:fullBackupContent' in manifest
assert 'org.ffortuna.capture.SyntheticNotificationListener' in manifest
assert 'com.nu.production' in manifest, 'Narrow Nubank package query missing'
activity=Path('android/app/src/main/java/org/ffortuna/capturepoc/MainActivity.kt').read_text()
assert 'window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)' in activity, 'Secure window flag missing from generated activity'
assert 'android.permission.BIND_NOTIFICATION_LISTENER_SERVICE' in manifest
libraries=[]
notice_asset=Path('modules/ffortuna-capture/android/src/main/assets/ffortuna-legal/THIRD_PARTY_NOTICES.txt')
with zipfile.ZipFile(apk) as archive:
    assert archive.read('assets/ffortuna-legal/THIRD_PARTY_NOTICES.txt') == notice_asset.read_bytes(), 'Third-party notices missing/stale in APK'
    assert archive.read('assets/ffortuna-legal/LICENSE.txt') == Path('LICENSE').read_bytes(), 'AGPL text missing/stale in APK'
    assert 'assets/index.android.bundle' in archive.namelist(), 'Offline JS bundle missing'
    font_payloads=[archive.read(name) for name in archive.namelist() if name.lower().endswith('.ttf')]
    for font in Path('assets/fonts').glob('*.ttf'):
        assert font.read_bytes() in font_payloads, f'Bundled font missing: {font.name}'
    for name in archive.namelist():
        if not name.startswith('lib/') or not name.endswith('.so'): continue
        data=archive.read(name)
        assert data[:4]==b'\x7fELF' and data[4]==2, name
        endian='<' if data[5]==1 else '>'
        offset=struct.unpack_from(endian+'Q',data,32)[0]
        size,count=struct.unpack_from(endian+'HH',data,54)
        alignments=[]
        for index in range(count):
            fields=struct.unpack_from(endian+'IIQQQQQQ',data,offset+index*size)
            if fields[0]==1:
                assert fields[7]>=16384 and fields[2]%16384==fields[3]%16384, f'ELF not 16KB aligned: {name}'
                alignments.append(fields[7])
        libraries.append({'name':name,'loadAlignments':alignments})
assert any(item['name'].endswith('/libsqlcipher.so') for item in libraries), 'SQLCipher missing'
report={'apk':apk.name,'signatureVerified':True,'zipAligned16KB':True,'elfAligned16KB':True,
        'noticeAssetsVerified':True,'internetPermission':False,'allowBackup':False,'offlineBundle':True,'secureWindowConfigured':True,'narrowSourceQuery':True,
        'permissions':permissions,'nativeLibraries':libraries,
        'deviceExecutionVerified':False}
output=Path('artifacts/apk-inspection.json')
output.parent.mkdir(parents=True,exist_ok=True)
run([sys.executable,'scripts/audit-apk-notices.py','--check','--apk',apk])
output.write_text(json.dumps(report,indent=2)+'\n')
print(f'APK inspected: signature, backup policy, offline bundle and {len(libraries)} native libraries aligned to 16KB; no INTERNET.')
