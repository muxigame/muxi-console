"""Prepare a task-owned portable devkitPro SDK from official fixed package-index SHA pins.
No global install, registry, credentials or system PATH changes.
"""
from __future__ import annotations
import concurrent.futures,json,re,tarfile
from pathlib import Path,PurePosixPath
import zstandard
from fetch_expansion_cores import CACHE,ROOT,fetch,sha

TOOLCHAIN=ROOT.parent/'test-environment/toolchains/muxi-console-expansion-devkitpro'
INDEXES=[('https://pkg.devkitpro.org/packages/windows/x86_64/',CACHE/'sdk-index/index-0.db'),('https://pkg.devkitpro.org/packages/',CACHE/'sdk-index/libs-0.db')]
NAMES=['devkitarm-binutils','devkitarm-gcc','devkitarm-newlib','devkitarm-crtls','devkitarm-rules','devkitppc-binutils','devkitppc-gcc','devkitppc-newlib','devkitppc-crtls','devkitppc-rules','general-tools','3dstools','ndstool','gamecube-tools','libctru','libnds','calico','libogc']

def wii_diagnostic_compatibility():
 """Use the fixed Dolphin revision's compatible official WPAD diagnostic SDK.

 libogc 3.2.1 boots but its WPAD consumer reads zero where the identical
 2.12.0 program reads actual emulated buttons. Keep this separate from the
 current SDK; this is not an emulator or commercial-game patch.
 """
 base=TOOLCHAIN.parent/'muxi-console-expansion-libogc2';install=base/'locked-install'
 packages=[('https://pkg.devkitpro.org/packages/libogc-2.12.0-1-any.pkg.tar.zst','libogc-2.12.0-1-any.pkg.tar.zst','d710d006578984c9e9524d26100587458d09704f8da9cc44775186553644c718'),('https://codeload.github.com/devkitPro/devkitppc-crtls/tar.gz/9d2163605cbb2b89f7ac1186d2ae3ac447a35adc','crtls-v1.tar.gz','6b8358f78a8babceb8d4c41fb175b486ef5d46426d4f569a50aac97096275270')]
 records=[]
 for url,name,expected in packages:
  path=base/'locked-downloads'/name;records.append(fetch(url,path,expected));marker=base/('locked-'+expected)
  if marker.exists():continue
  def unpack(tar):
   for member in tar:
    relative=PurePosixPath(member.name)
    if relative.is_absolute() or '..' in relative.parts:raise ValueError('Unsafe compatibility SDK member')
    if relative.parts and relative.parts[0].startswith('.'):continue
    tar.extract(member,install,filter='data')
  install.mkdir(parents=True,exist_ok=True)
  if name.endswith('.zst'):
   with path.open('rb') as f,zstandard.ZstdDecompressor().stream_reader(f) as decoded,tarfile.open(fileobj=decoded,mode='r|') as tar:unpack(tar)
  else:
   with tarfile.open(path,'r:gz') as tar:unpack(tar)
  marker.write_text(name+'\n')
 return {'libogc':install/'opt/devkitpro/libogc','linkScript':install/'devkitppc-crtls-9d2163605cbb2b89f7ac1186d2ae3ac447a35adc/rvl.ld','packages':records}


def prepare():
 available={}
 for base,path in INDEXES:
  with tarfile.open(path) as index:
   for member in index:
    if not member.isfile() or not member.name.endswith('/desc'):continue
    fields={k:v.strip().splitlines() for k,v in re.findall(r'%([A-Z0-9_]+)%\n(.*?)(?=\n%|\Z)',index.extractfile(member).read().decode(),re.S)}
    name=fields['NAME'][0];available[name]={'name':name,'version':fields['VERSION'][0],'file':fields['FILENAME'][0],'url':base+fields['FILENAME'][0],'sha256':fields['SHA256SUM'][0],'bytes':int(fields['CSIZE'][0]),'licenses':fields.get('LICENSE',[]),'upstream':fields.get('URL',[]),'indexSha256':sha(path)}
 def obtain(name):
  record=available[name];path=TOOLCHAIN/'downloads'/record['file'];fetch(record['url'],path,record['sha256']);print('verified '+name,flush=True);return record,path
 with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:packages=list(pool.map(obtain,NAMES))
 install=TOOLCHAIN/'install';install.mkdir(parents=True,exist_ok=True)
 for record,path in packages:
  marker=TOOLCHAIN/('installed-'+record['sha256'])
  if marker.exists():continue
  with path.open('rb') as source,zstandard.ZstdDecompressor().stream_reader(source) as decoded,tarfile.open(fileobj=decoded,mode='r|') as tar:
   for member in tar:
    relative=PurePosixPath(member.name)
    if relative.is_absolute() or '..' in relative.parts:raise ValueError('Unsafe SDK archive path')
    if relative.parts and relative.parts[0].startswith('.'):continue
    target=(install/member.name).resolve()
    if not target.is_relative_to(install.resolve()):raise ValueError('SDK archive escaped owned installation')
    tar.extract(member,install,filter='data')
  marker.write_text(record['name']+'\n')
 compatibility=wii_diagnostic_compatibility()
 report={'scope':'Portable official SDK for original diagnostic compilation only; no third-party game ROM or BIOS','install':str(install),'indexes':[{'url':base,'sha256':sha(path)} for base,path in INDEXES],'packages':[record for record,path in packages],'wiiDiagnosticCompatibility':{k:str(v) if isinstance(v,Path) else v for k,v in compatibility.items()}}
 (TOOLCHAIN/'sdk-lock.json').write_text(json.dumps(report,indent=2)+'\n');print(json.dumps({'install':str(install),'packages':len(packages)},indent=2));return report

if __name__=='__main__':prepare()
