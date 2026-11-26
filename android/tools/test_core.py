#!/usr/bin/env python3
import pathlib,subprocess,os,zipfile,tempfile
root=pathlib.Path(__file__).resolve().parents[1]
if not (root/'tools/ecj.jar').exists():subprocess.run(['python3',str(root/'tools/fetch.py')],check=True)
with tempfile.TemporaryDirectory(prefix='tyanu-test-') as d:
 p=pathlib.Path(d);c=p/'classes';c.mkdir()
 with zipfile.ZipFile(root/'libs/libtorrent4j-linux.jar') as z:(p/'libtorrent4j.so').write_bytes(z.read('lib/x86_64/libtorrent4j.so'))
 subprocess.run(['java','-jar',str(root/'tools/ecj.jar'),'-17','-nowarn','-cp',str(root/'libs/libtorrent4j.jar'),'-d',str(c),str(root/'app/src/main/java/ru/sveta/tyanu/TorrentEngine.java'),str(root/'tools/CoreStateTest.java')],check=True)
 subprocess.run(['java','-Djava.library.path='+str(p),'-cp',str(c)+os.pathsep+str(root/'libs/libtorrent4j.jar'),'CoreStateTest',str(p/'state-test')],check=True,timeout=45)
