#!/usr/bin/env python3
import pathlib,subprocess,zipfile,shutil,json,os,hashlib
root=pathlib.Path(__file__).resolve().parents[1]
os.chdir(root)
def run(*args):
 print('+',args[0],flush=True);subprocess.run([str(a) for a in args],check=True)
if not (root/'tools/windows-jre.zip').is_file():run('python3',root/'tools/fetch.py')
build=root/'build';dist=root/'dist';classes=build/'classes';classes.mkdir(exist_ok=True,parents=True);dist.mkdir(exist_ok=True)
run('java','-jar',root/'tools/ecj.jar','-17','-nowarn','-cp',str(root/'libs/libtorrent4j.jar')+os.pathsep+str(root/'libs/flatlaf.jar'),'-d',classes,*list((root/'src').rglob('*.java')))
app=build/'tyanu.jar'
with zipfile.ZipFile(app,'w',zipfile.ZIP_DEFLATED) as z:
 z.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\nMain-Class: ru.sveta.tyanu.DesktopApp\n\n')
 for p in classes.rglob('*.class'):z.write(p,p.relative_to(classes))
 z.write(root/'NOTICE.txt','NOTICE.txt')
native=build/'native-windows';native.mkdir(exist_ok=True)
with zipfile.ZipFile(root/'libs/libtorrent4j-windows.jar') as z:
 for n in z.namelist():
  if n.endswith('.dll'):(native/pathlib.Path(n).name).write_bytes(z.read(n))
config={'platform':'windows64','jdk':str(root/'tools/windows-jre.zip'),'executable':'Tyanu','classpath':[str(app),str(root/'libs/libtorrent4j.jar'),str(root/'libs/flatlaf.jar')],'mainclass':'ru.sveta.tyanu.DesktopApp','vmargs':['Xmx512m','Dfile.encoding=UTF-8','Djava.library.path=.'],'output':str(build/'windows')}
(build/'packr.json').write_text(json.dumps(config,indent=2))
if(build/'windows').exists():shutil.rmtree(build/'windows')
run('java','-jar',root/'tools/packr.jar',build/'packr.json')
out=build/'windows'
for p in native.glob('*.dll'):shutil.copy(p,out/p.name)
shutil.copy(root/'NOTICE.txt',out/'NOTICE.txt')
shutil.copy(root/'README.md',out/'README-Windows.txt')
shutil.copytree(root/'licenses',out/'licenses',dirs_exist_ok=True)
# A documented fallback if Windows policies prevent the native wrapper from starting.
(out/'Start-Tyanu.cmd').write_bytes(b'@echo off\r\ncd /d "%~dp0"\r\nstart "" "%~dp0jre\\bin\\javaw.exe" -Dfile.encoding=UTF-8 -Djava.library.path=. -cp "%~dp0tyanu.jar;%~dp0libtorrent4j.jar;%~dp0flatlaf.jar" ru.sveta.tyanu.DesktopApp %*\r\n')
archive=dist/'Tyanu-0.1.0-Windows-x64.zip'
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
 for p in sorted(out.rglob('*')):
  if p.is_file():z.write(p,pathlib.Path('Tyanu')/p.relative_to(out))
print(json.dumps({'file':str(archive),'size':archive.stat().st_size,'sha256':hashlib.sha256(archive.read_bytes()).hexdigest()},indent=2))
