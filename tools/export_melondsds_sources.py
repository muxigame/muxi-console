"""Export the actual fixed melonDS DS build inputs, without host paths or inherited environment."""
from pathlib import Path,PurePosixPath
import copy,gzip,hashlib,io,json,tarfile
import expansion_sources as sources
ROOT=Path(__file__).resolve().parents[1]
BASE=ROOT.parent/'test-environment/cache/muxi-console/expansion/melondsds/rebuild'
def sha_bytes(stream):
 h=hashlib.sha256()
 for block in iter(lambda:stream.read(65536),b''):h.update(block)
 return h.hexdigest()
def export():
 lock=json.loads((BASE/'source-lock.json').read_text());pin=json.loads((ROOT/'third-party/cores.json').read_text(encoding='utf-8'))['cores']['melondsds'];dll=BASE/'build/src/libretro/melondsds_libretro.dll'
 if sources.fetch.sha(dll)!=pin['platforms']['windows-x86_64']['librarySha256']:raise ValueError('Actual built DLL differs from reviewed pin')
 target=BASE/'melondsds-corresponding-public-source.tar.gz';temporary=target.with_suffix('.gz.part');public=copy.deepcopy(lock);licenses=[];members=0
 for row in public['dependencies']:
  row['sourceDirectory']='source/'+row['name'];licenses.extend(sources.notices('melondsds--'+row['name'],BASE/(row['name']+'-'+row['commit']+'.tar.gz')))
 notice=BASE/'build/melondsds-LICENSE.txt'
 if not notice.is_file():raise ValueError('Compiled core aggregate license was not generated')
 dest=ROOT/'third-party/licenses/melondsds/melondsds-LICENSE.txt';dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(notice.read_bytes());licenses.append(dest.relative_to(ROOT).as_posix())
 record={'core':'melondsds','repository':'https://github.com/JesseTG/melonds-ds','sourceCommit':lock['dependencies'][0]['commit'],'library':{'name':dll.name,'bytes':dll.stat().st_size,'sha256':sources.fetch.sha(dll)},'dependencies':[{k:v for k,v in row.items() if k!='sourceDirectory'} for row in public['dependencies']],'patchesApplied':False,'compiledFromThesePreferredSources':True,'bitIdenticalRebuildClaimed':False,'toolchain':{'LLVM-MinGW':'20250613-ucrt-x86_64 / Clang20.1.7','CMake':'4.0.2','GNU Make':'4.4.1','cpuParallelism':1,'compilerArchive':'https://github.com/mstorsjo/llvm-mingw/releases/download/20250613/llvm-mingw-20250613-ucrt-x86_64.zip','compilerArchiveSHA256':'45145c035d9246e1de16f1873aa9afa863d93909f4a8f363e2eb38a04031d3c3'},'flags':{'ENABLE_JIT':True,'ENABLE_OPENGL':True,'ENABLE_TESTING':False,'TRACY_ENABLE':False,'CMAKE_BUILD_TYPE':'Release','FETCHCONTENT_FULLY_DISCONNECTED':True},'nativeEvidence':{'scope':'our original DS diagnostic, not commercial compatibility','frames':180,'coreStateBytes':19219831,'MXS1Bytes':19219847,'buttonsAndBottomTouch':True,'normalExit':True,'nativeTemporaryFilesCleaned':True}}
 readme='''Corresponding source for our rebuilt melonDS DS DLL

The original upstream release used a moving dependency branch. This package
instead binds our NEW DLL to every exact dependency commit in source-lock.json.
All source/ trees are preferred upstream code, including original licenses and
build files. No emulator semantic patches were applied. This does not claim the
new DLL is byte-identical to the original upstream DLL, nor that recompilation
at another time is byte-identical. See BUILD-RECORD.json for the actual DLL hash.

Offline Windows x64 rebuild (no Minecraft SDK or Git checkout required):
1. Extract this archive into a normal local directory.
2. Obtain LLVM-MinGW 20250613-ucrt-x86_64 from the URL/SHA in BUILD-RECORD.json,
   GNU Make 4.4.1, CMake 4.0.2 and Git for Windows (Make runtime DLLs).
3. Run Python3: tools/build_melondsds.py --source-tree . --llvm-root <LLVM-root>
   --make <mingw32-make.exe> --cmake <cmake.exe> --git-root <Git-root>
   The supplied source lock uses only local source/ directories; compilation
   explicitly disables all FetchContent network access and uses one CPU job.
4. The result is build/src/libretro/melondsds_libretro.dll.

The upstream core is GPL-3.0-or-later; each dependency keeps its own license.
Our build/export drivers are MIT. LICENSE-FIRST-PARTY covers only those drivers.
BUILD-INFO/history retains the exact first-success driver, not private machine logs.
'''
 def add_bytes(output,name,data):
  info=tarfile.TarInfo(name);info.size=len(data);info.mode=0o644;info.mtime=0;output.addfile(info,io.BytesIO(data))
 try:
  with temporary.open('xb') as raw,gzip.GzipFile(filename='',mode='wb',fileobj=raw,mtime=0) as compressed,tarfile.open(fileobj=compressed,mode='w|') as output:
   for row in lock['dependencies']:
    archive=BASE/(row['name']+'-'+row['commit']+'.tar.gz')
    if sources.fetch.sha(archive)!=row['archive']['sha256']:raise ValueError('Dependency archive changed')
    with tarfile.open(archive) as source:
     original=source.getmembers();prefix=original[0].name.split('/')[0]+'/'
     for member in original:
      if member.name==prefix[:-1]:continue
      if not member.name.startswith(prefix):raise ValueError('Unexpected archive prefix')
      relative=member.name[len(prefix):];parts=PurePosixPath(relative).parts
      if '..' in parts or PurePosixPath(relative).is_absolute():raise ValueError('Unsafe preferred source path')
      if member.isfile():
       actual=Path(row['sourceDirectory'])/relative
       with actual.open('rb') as f:
        if sha_bytes(f)!=sha_bytes(source.extractfile(member)):raise ValueError('Preferred source changed since compilation: '+row['name']+'/'+relative)
      info=copy.copy(member);info.name='source/'+row['name']+'/'+relative;info.uid=info.gid=0;info.uname=info.gname='';info.mtime=0
      output.addfile(info,source.extractfile(member) if member.isfile() else None);members+=1
   add_bytes(output,'source-lock.json',(json.dumps(public,indent=2)+'\n').encode());add_bytes(output,'BUILD-RECORD.json',(json.dumps(record,indent=2)+'\n').encode());add_bytes(output,'BUILDING.md',readme.encode());add_bytes(output,'LICENSE-FIRST-PARTY',(ROOT/'LICENSE').read_bytes());add_bytes(output,'tools/build_melondsds.py',(ROOT/'tools/build_melondsds.py').read_bytes());add_bytes(output,'BUILD-INFO/history/first-success-build_melondsds.py',(BASE/'history/first-success-build_melondsds.py').read_bytes());add_bytes(output,'BUILD-INFO/melondsds-LICENSE.txt',notice.read_bytes())
  temporary.replace(target)
 finally:
  if temporary.exists():temporary.unlink()
 result={'repository':pin['source'],'revision':pin['sourceRevision'],'archive':'rebuilt/melondsds-corresponding-public-source.tar.gz','cachePath':'../expansion/melondsds/rebuild/'+target.name,'bytes':target.stat().st_size,'sha256':sources.fetch.sha(target),'archiveLayout':'source/frontend and source/<dependency>; root source-lock.json/BUILDING.md drive a completely offline reconstruction','patchesApplied':False,'licenseFiles':sorted(set(licenses)),'submodules':[],'rebuildReport':record,'preferredSourceMembersVerifiedAgainstActualBuild':members}
 out=sources.CACHE/'expansion-melondsds-source.json';out.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');print(json.dumps({'sourceRecord':str(out),'archiveBytes':result['bytes'],'sha256':result['sha256'],'verifiedMembers':members},indent=2))
if __name__=='__main__':export()
