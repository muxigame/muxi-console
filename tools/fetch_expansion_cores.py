"""Fetch the three explicitly selected official expansion cores and source artifacts.

Development/provenance acquisition only: never marks a core releaseReady by inference.
"""
from __future__ import annotations
import argparse,hashlib,json,struct,urllib.request,zipfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
CACHE=ROOT.parent/'test-environment/cache/muxi-console/expansion'
SPECS={
 'melondsds':{'repository':'https://github.com/JesseTG/melonds-ds','commit':'f394adbacb5722ee97c1b37c8064da9a25818310','version':'1.4.0','binary':'https://github.com/JesseTG/melonds-ds/releases/download/v1.4.0/melondsds_libretro-win32-x86_64-Release.zip','binarySha256':'f03af0318fee91a83880f438b369a1368fea6b5c1524a09dde849ca8fa68603f','source':'https://codeload.github.com/JesseTG/melonds-ds/tar.gz/f394adbacb5722ee97c1b37c8064da9a25818310','license':'GPL-3.0-or-later','systems':['nds'],'library':'melondsds_libretro.dll'},
 'azahar':{'repository':'https://github.com/azahar-emu/azahar','commit':'219d743a1e698101fe1ab8bef43e6563126c3c27','version':'2126.2','binary':'https://github.com/azahar-emu/azahar/releases/download/2126.2/azahar-libretro-windows-x86_64-2126.2.zip','binarySha256':'d863711cff775ed697ba0cc5a963f2145b9672c4ad9a891367821d7f3cc1f888','source':'https://github.com/azahar-emu/azahar/releases/download/2126.2/azahar-unified-source-2126.2.tar.xz','sourceSha256':'85c8b56fac703df04720125044b8ff887997911858a6cde545dfb1107ba4cd26','license':'GPL-2.0-or-later; per-file vendor licenses','systems':['n3ds'],'library':'azahar_libretro.dll'},
 'dolphin':{'repository':'https://github.com/libretro/dolphin','commit':'32c61f9d0e933331bb7087ba57e96883241b4960','version':'fixed-source-development','binary':'https://buildbot.libretro.com/nightly/windows/x86_64/latest/dolphin_libretro.dll.zip','source':'https://codeload.github.com/libretro/dolphin/tar.gz/32c61f9d0e933331bb7087ba57e96883241b4960','license':'GPL-2.0-or-later; aggregate GPL-3.0-compatible; per-file SPDX','systems':['wii'],'library':'dolphin_libretro.dll'}
}

def sha(path):
 h=hashlib.sha256()
 with path.open('rb') as f:
  for b in iter(lambda:f.read(1048576),b''):h.update(b)
 return h.hexdigest()

def fetch(url,path,expected=None):
 path.parent.mkdir(parents=True,exist_ok=True);origin=path.with_name(path.name+'.origin.json')
 if path.exists():
  if not origin.exists():raise ValueError('Unrecorded existing download')
  record=json.loads(origin.read_text());actual=sha(path)
  if record['url']!=url or record['sha256']!=actual or expected and actual!=expected:raise ValueError('Cached download differs')
  return record
 tmp=path.with_suffix(path.suffix+'.part')
 try:
  with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'MuxiConsole-Expansion/1'}),timeout=60) as response,tmp.open('xb') as out:
   size=0
   while b:=response.read(1048576):
    size+=len(b)
    if size>1024**3:raise ValueError('Expansion artifact exceeds one GiB')
    out.write(b)
  actual=sha(tmp)
  if expected and actual!=expected:raise ValueError('Official digest mismatch')
  tmp.replace(path);record={'url':url,'sha256':actual,'bytes':path.stat().st_size};origin.write_text(json.dumps(record,indent=2)+'\n');return record
 finally:
  if tmp.exists():tmp.unlink()

def acquire(name):
 spec=SPECS[name];cache=CACHE/name;cache.mkdir(parents=True,exist_ok=True)
 archive=cache/'official-core.zip';binary=fetch(spec['binary'],archive,spec.get('binarySha256'))
 source=cache/('official-source.tar.xz' if spec['source'].endswith('.tar.xz') else 'official-source.tar.gz');source_record=fetch(spec['source'],source,spec.get('sourceSha256'))
 with zipfile.ZipFile(archive) as z:
  candidates=[m for m in z.infolist() if Path(m.filename).name==spec['library'] and not m.is_dir()]
  if len(candidates)!=1 or candidates[0].file_size>256*1024**2:raise ValueError('Missing/oversized unique expected core DLL')
  library=cache/spec['library']
  with z.open(candidates[0]) as src,library.open('wb') as out:
   while b:=src.read(1048576):out.write(b)
 with library.open('rb') as f:
  header=f.read(64)
  if not header.startswith(b'MZ'):raise ValueError('Not a PE image')
  f.seek(struct.unpack_from('<I',header,60)[0]);pe=f.read(6)
  if pe[:4]!=b'PE\0\0' or struct.unpack_from('<H',pe,4)[0]!=0x8664:raise ValueError('Not a Win x64 DLL')
 record={'core':name,'spec':spec,'binaryArchive':{'path':str(archive),**binary},'library':{'path':str(library),'name':library.name,'bytes':library.stat().st_size,'sha256':sha(library)},'sourceArchive':{'path':str(source),**source_record},'sourceCorrespondenceVerified':False,'releaseReady':False,'notes':'Official development binary. NDS floating FetchContent dependencies and Dolphin rolling-build correspondence still require closure; Azahar unified source must be audited before distribution.'}
 (cache/'acquisition.json').write_text(json.dumps(record,indent=2)+'\n');return record

if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--core',required=True,choices=SPECS);a=p.parse_args();r=acquire(a.core);print(json.dumps(r,indent=2))
