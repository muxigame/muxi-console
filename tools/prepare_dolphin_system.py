"""Deterministically package only Dolphin's official, fixed-revision Sys resources."""
from pathlib import Path,PureWindowsPath
import hashlib,json,tarfile,zipfile
ROOT=Path(__file__).resolve().parents[1]
CACHE=ROOT.parent/'test-environment/cache/muxi-console/expansion/dolphin'
SOURCE_SHA='d5d5131a5da1dd2eae2950c894292a72546aa2cb87f645b89c30aabc8f679432'
PREFIX='dolphin-32c61f9d0e933331bb7087ba57e96883241b4960/Data/Sys/'
def prepare():
 archive=CACHE/'official-source.tar.gz'
 if hashlib.sha256(archive.read_bytes()).hexdigest()!=SOURCE_SHA:raise ValueError('Fixed official source archive differs')
 target=CACHE/'dolphin-sys.zip';temp=CACHE/'dolphin-sys.zip.preparing';count=total=0
 try:
  with tarfile.open(archive) as source,zipfile.ZipFile(temp,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as output:
   for member in sorted(source.getmembers(),key=lambda m:PureWindowsPath(m.name)):
    if not member.name.startswith(PREFIX):continue
    if member.isdir():continue
    name=member.name[len(PREFIX):]
    if not member.isfile() or not name or '..' in name.split('/') or ':' in name or '\\' in name:raise ValueError('Unexpected Sys source member')
    count+=1;total+=member.size
    if count>20000 or total>64*1024**2:raise ValueError('Sys resources exceed their extraction budget')
    info=zipfile.ZipInfo('dolphin-emu/Sys/'+name,date_time=(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16
    with source.extractfile(member) as input:output.writestr(info,input.read(),compresslevel=9)
  digest=hashlib.sha256(temp.read_bytes()).hexdigest()
  if target.exists() and hashlib.sha256(target.read_bytes()).hexdigest()!=digest:raise ValueError('Existing Sys export differs; retained without overwrite')
  if not target.exists():temp.replace(target)
  result={'sourceCommit':'32c61f9d0e933331bb7087ba57e96883241b4960','sourceArchiveSha256':SOURCE_SHA,'path':str(target),'sha256':digest,'bytes':target.stat().st_size,'files':count,'expandedBytes':total,'proprietaryBiosIncluded':False,'sourceScope':'The unmodified tracked Data/Sys tree; upstream per-file notices remain in corresponding source.'}
  (CACHE/'system-resources.json').write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');print(json.dumps(result,indent=2))
 finally:
  if temp.exists():temp.unlink()
if __name__=='__main__':prepare()
