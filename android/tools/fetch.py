import urllib.request,concurrent.futures,pathlib,zipfile
root=pathlib.Path(__file__).resolve().parents[1]
urls={
 'tools/build-tools.zip':'https://dl.google.com/android/repository/build-tools_r35_linux.zip',
 'tools/platform.zip':'https://dl.google.com/android/repository/platform-35_r02.zip',
 'tools/ecj.jar':'https://repo.maven.apache.org/maven2/org/eclipse/jdt/ecj/3.39.0/ecj-3.39.0.jar',
 'libs/libtorrent4j.jar':'https://repo.maven.apache.org/maven2/org/libtorrent4j/libtorrent4j/2.1.0-39/libtorrent4j-2.1.0-39.jar',
 'libs/libtorrent4j-android-arm64.jar':'https://repo.maven.apache.org/maven2/org/libtorrent4j/libtorrent4j-android-arm64/2.1.0-39/libtorrent4j-android-arm64-2.1.0-39.jar',
 'libs/libtorrent4j-linux.jar':'https://repo.maven.apache.org/maven2/org/libtorrent4j/libtorrent4j-linux/2.1.0-39/libtorrent4j-linux-2.1.0-39.jar',
 'libs/libtorrent4j-sources.jar':'https://repo.maven.apache.org/maven2/org/libtorrent4j/libtorrent4j/2.1.0-39/libtorrent4j-2.1.0-39-sources.jar',
}
def fetch(item):
 name,url=item;p=root/name;p.parent.mkdir(parents=True,exist_ok=True)
 if not p.exists():urllib.request.urlretrieve(url,p)
 print(name,p.stat().st_size,flush=True)
with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:list(pool.map(fetch,urls.items()))
for name in ['build-tools','platform']:
 with zipfile.ZipFile(root/f'tools/{name}.zip') as z:z.extractall(root/'tools')
with zipfile.ZipFile(root/'libs/libtorrent4j-sources.jar') as z:z.extractall(root/'engine-src')
