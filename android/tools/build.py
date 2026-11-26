#!/usr/bin/env python3
"""Reproducible standalone Android build. Python 3, Java 21 runtime, network for initial fetch."""
import os,pathlib,subprocess,zipfile,shutil
root=pathlib.Path(__file__).resolve().parents[1]
os.chdir(root)
def run(*args):
    print('+',str(args[0]),flush=True)
    subprocess.run([str(a) for a in args],check=True)
if not (root/'tools/android-15/aapt2').exists():run('python3',root/'tools/fetch.py')
bt=root/'tools/android-15';android=root/'tools/android-35/android.jar';build=root/'build';dist=root/'dist';signing=root/'signing'
for p in [build/'classes',build/'dex',build/'res',dist,signing]:p.mkdir(parents=True,exist_ok=True)
for name in ['aapt2','d8','zipalign']: (bt/name).chmod(0o755)
run(bt/'aapt2','compile','--dir',root/'app/src/main/res','-o',build/'resources.zip')
run(bt/'aapt2','link','-o',build/'base.apk','--manifest',root/'app/src/main/AndroidManifest.xml','-I',android,'--java',build/'generated','--min-sdk-version','28','--target-sdk-version','35',build/'resources.zip')
sources=list((root/'app/src/main/java').rglob('*.java'))+list((build/'generated').rglob('*.java'))
run('java','-jar',root/'tools/ecj.jar','-8','-nowarn','-bootclasspath',str(android)+os.pathsep+str(bt/'core-lambda-stubs.jar'),'-cp',root/'libs/libtorrent4j.jar','-d',build/'classes',*sources)
with zipfile.ZipFile(build/'app-classes.jar','w') as z:
    for p in (build/'classes').rglob('*.class'):z.write(p,p.relative_to(build/'classes'))
run('java','-cp',bt/'lib/d8.jar','com.android.tools.r8.D8','--min-api','28','--lib',android,'--output',build/'dex',build/'app-classes.jar',root/'libs/libtorrent4j.jar')
shutil.copy(build/'base.apk',build/'unsigned.apk')
with zipfile.ZipFile(build/'unsigned.apk','a',compression=zipfile.ZIP_DEFLATED) as z:
    for p in (build/'dex').glob('*.dex'):z.write(p,p.name)
    with zipfile.ZipFile(root/'libs/libtorrent4j-android-arm64.jar') as native:
        for name in native.namelist():
            if name.endswith('.so'):z.writestr(name,native.read(name))
    z.writestr('assets/NOTICE.txt',(root/'NOTICE.txt').read_bytes())
run(bt/'zipalign','-f','-P','16','4',build/'unsigned.apk',build/'aligned.apk')
keystore=pathlib.Path(os.environ.get('TYANU_KEYSTORE',str(signing/'release.jks'))).resolve()
password_file=pathlib.Path(os.environ.get('TYANU_KEYSTORE_PASSWORD_FILE',str(signing/'password.txt'))).resolve()
if not keystore.is_file() or not password_file.is_file():
    raise SystemExit('Provide the existing release key via TYANU_KEYSTORE and TYANU_KEYSTORE_PASSWORD_FILE. A new key is never generated automatically.')
run('java','-jar',bt/'lib/apksigner.jar','sign','--ks',keystore,'--ks-key-alias','tyanu','--ks-pass','file:'+str(password_file),'--out',dist/'Tyanu-0.1.3-arm64.apk',build/'aligned.apk')
run('java','-jar',bt/'lib/apksigner.jar','verify','--verbose','--print-certs',dist/'Tyanu-0.1.3-arm64.apk')
run(bt/'aapt2','dump','badging',dist/'Tyanu-0.1.3-arm64.apk')
print('Built:',dist/'Tyanu-0.1.3-arm64.apk')
