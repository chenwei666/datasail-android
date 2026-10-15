#!/usr/bin/env python3
"""Build verified source overlays; preserve every unmodified upstream class/resource."""
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import sys
import zipfile

def sha(data): return hashlib.sha256(data).hexdigest()
def require(ok, message):
    if not ok: raise SystemExit(message)

root=Path.cwd()
lock=json.loads((root/'upstream.lock.json').read_text())
mode,binary,destination=sys.argv[1:]
binary=Path(binary); destination=Path(destination)
require(sha(binary.read_bytes())==lock['binarySha256'],'Upstream binary checksum mismatch')
if mode=='prepare':
    source=root/'upstream'/lock['sourceFile']
    require(sha(source.read_bytes())==lock['sourceSha256'],'Upstream source checksum mismatch')
    if destination.exists(): shutil.rmtree(destination)
    destination.mkdir(parents=True)
    with zipfile.ZipFile(source) as archive:
        for name in lock['sources']:
            path=destination/name;path.parent.mkdir(parents=True,exist_ok=True)
            path.write_text(archive.read(name).decode().replace('\r\n','\n'))
    subprocess.run(['patch','--batch','--fuzz=0','-p1','-i',str(root/'patches/android.patch')],cwd=destination,check=True)
    for name, expected in lock['sources'].items(): require(sha((destination/name).read_bytes())==expected,'Patched source mismatch: '+name)
elif mode=='verify':
    with zipfile.ZipFile(binary) as original,zipfile.ZipFile(destination) as built:
        old={n for n in original.namelist() if not n.endswith('/')};new={n for n in built.namelist() if not n.endswith('/')}
        replaced=set(lock['replacedEntries']);compiled=set(lock['compiledEntries'])
        signatures={n for n in old if n=='META-INF/MANIFEST.MF' or (n.startswith('META-INF/') and n.endswith(('.SF','.RSA','.DSA')))}
        require(len(built.namelist())==len(set(built.namelist())),'Duplicate archive entries')
        require(new==(old-replaced-signatures)|compiled|{'META-INF/MANIFEST.MF',f'META-INF/datasail-{root.name}-profile.json'},'Unexpected profile entries')
        for name in old-replaced-signatures: require(original.read(name)==built.read(name),'Unexpected upstream edit: '+name)
        require(json.loads(built.read(f'META-INF/datasail-{root.name}-profile.json'))==lock,'Profile provenance mismatch')
    print('Verified source overlay:',lock['profile'],len(compiled),'compiled classes; other upstream bytes unchanged')
else: raise SystemExit('Unknown profile operation')
