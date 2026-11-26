import urllib.request,pathlib,concurrent.futures,json,hashlib
root=pathlib.Path(__file__).resolve().parents[1]
urls={
 'tools/windows-jre.zip':'https://cdn.azul.com/zulu/bin/zulu21.54.201-ca-jre21.0.13.1-win_x64.zip',
'libs/libtorrent4j-windows.jar':'https://repo.maven.apache.org/maven2/org/libtorrent4j/libtorrent4j-windows/2.1.0-39/libtorrent4j-windows-2.1.0-39.jar',
'libs/flatlaf.jar':'https://repo.maven.apache.org/maven2/com/formdev/flatlaf/3.8/flatlaf-3.8.jar',
'libs/libtorrent4j.jar':'https://repo.maven.apache.org/maven2/org/libtorrent4j/libtorrent4j/2.1.0-39/libtorrent4j-2.1.0-39.jar',
'libs/libtorrent4j-linux.jar':'https://repo.maven.apache.org/maven2/org/libtorrent4j/libtorrent4j-linux/2.1.0-39/libtorrent4j-linux-2.1.0-39.jar',
 'tools/ecj.jar':'https://repo.maven.apache.org/maven2/org/eclipse/jdt/ecj/3.39.0/ecj-3.39.0.jar',
 'tools/packr.jar':'https://github.com/libgdx/packr/releases/download/4.0.0/packr-all-4.0.0.jar',
}
def fetch(item):
 name,url=item;p=root/name;p.parent.mkdir(exist_ok=True,parents=True)
 if not p.exists():urllib.request.urlretrieve(url,p)
 return {'file':name,'url':url,'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'size':p.stat().st_size}
with concurrent.futures.ThreadPoolExecutor(max_workers=5) as ex:r=list(ex.map(fetch,urls.items()))
(root/'tools/dependencies.json').write_text(json.dumps(r,indent=2));print(json.dumps(r,indent=2))
