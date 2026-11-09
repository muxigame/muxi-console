"""Export complete portable corresponding DS link source, with no inherited PATH or private build logs."""
from pathlib import Path
import argparse,copy,gzip,hashlib,json,shutil,tarfile,zipfile
ROOT=Path(__file__).resolve().parents[1]
CACHE=ROOT.parent/'test-environment/cache/muxi-console'
def sha(path):
 h=hashlib.sha256()
 with path.open('rb')as f:
  for block in iter(lambda:f.read(1048576),b''):h.update(block)
 return h.hexdigest()
def main():
 ap=argparse.ArgumentParser();ap.add_argument('--build',type=Path,required=True);ap.add_argument('--output',type=Path,required=True);args=ap.parse_args();build=args.build.resolve();out=args.output.resolve()
 if not build.is_relative_to((CACHE/'rebuild-melondsds-link').resolve())or not out.is_relative_to(build):raise ValueError('Source export remains inside the owned DS rebuild cache')
 out.mkdir(exist_ok=False);package=out/'package';package.mkdir();lock=json.loads((build/'source-lock.json').read_text(encoding='utf-8'));preparation=json.loads((build/'preparation.json').read_text(encoding='utf-8'));resume_path=build/'resume-01/record.json';resume=json.loads(resume_path.read_text(encoding='utf-8'))if resume_path.exists()else None;adapter=build/'source/frontend/src/libretro/muxi_link_adapter.cpp'
 if sha(adapter)!=(resume['actualAdapterSHA256']if resume else preparation['adapterSHA256'])or sha(ROOT/'native/link/melondsds_link_adapter.cpp')!=sha(adapter):raise RuntimeError('Final adapter source changed')
 actual=build/'build/src/libretro/melondsds_libretro.dll';library=out/'muxi_melondsds_link.dll';shutil.copyfile(actual,library)
 shutil.copytree(build/'source',package/'source');tools=package/'tools';tools.mkdir();shutil.copyfile(build/'build-upstream-driver-used.py',tools/'build_melondsds.py');shutil.copyfile(build/('resume-01/driver-used.py'if resume else'build-link-driver-used.py'),tools/'build_ds_link_adapter.py');shutil.copyfile(ROOT/'LICENSE',package/'ADAPTER-LICENSE');patches=package/'patches';patches.mkdir();patch_rows=[]
 for path in [build/'frontend-access.patch',build/'melonds-foreign-fault.patch']:
  if path.is_file():shutil.copyfile(path,patches/path.name);patch_rows.append({'path':'patches/'+path.name,'sha256':sha(path),'license':'GPL-3.0-or-later as modifications to upstream source'})
 for row in lock['dependencies']:row['sourceDirectory']='source/'+row['name']
 lock['purpose']='Complete exact official dependencies, with the explicitly supplied frontend access patch and original MP-state adapter; source-built DS link binary only.'
 (package/'source-lock.json').write_text(json.dumps(lock,indent=2)+'\n',encoding='utf-8')
 archive=out/'muxi_melondsds_link.dll.zip'
 with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED)as z:
  item=zipfile.ZipInfo(library.name,(2026,1,1,0,0,0));item.compress_type=zipfile.ZIP_DEFLATED;z.writestr(item,library.read_bytes())
 baseline=json.loads((CACHE/'rebuild-mgba-link/build-08/build-report.json').read_text(encoding='utf-8'));toolchain=copy.deepcopy(baseline['toolchain']);toolroot=ROOT.parent/'test-environment/toolchains';compiler=next((toolroot/'muxi-console-rebuild-llvm-mingw-20250613').rglob('x86_64-w64-mingw32-clang.exe'));make=next((toolroot/'muxi-console-rebuild-gnu-make-4.4.1').rglob('mingw32-make.exe'))
 for name,path in [('llvmMinGW',compiler),('gnuMake',make),('cmake',Path('C:/Program Files/CMake/bin/cmake.exe'))]:
  if sha(path)!=toolchain[name]['executableSha256']:raise RuntimeError('Actual native tool differs from its recorded fixed toolchain: '+name)
 report={'schemaVersion':1,'core':'melondsds-link','sourceCommit':'f394adbacb5722ee97c1b37c8064da9a25818310','sourceOrigin':'https://github.com/JesseTG/melonds-ds','license':'GPL-3.0-or-later (linked frontend and explicit access patch); original adapter MIT','library':{'path':library.name,'bytes':library.stat().st_size,'sha256':sha(library)},'archive':{'path':archive.name,'bytes':archive.stat().st_size,'sha256':sha(archive)},'buildScript':{'path':'tools/build_melondsds.py','sha256':sha(tools/'build_melondsds.py')},'preparationScript':{'path':'tools/build_ds_link_adapter.py','sha256':sha(tools/'build_ds_link_adapter.py')},'adapter':{'path':'source/frontend/src/libretro/muxi_link_adapter.cpp','sha256':sha(adapter),'license':'MIT'},'patches':[{'path':'patches/frontend-access.patch','sha256':sha(patches/'frontend-access.patch'),'license':'GPL-3.0-or-later as modifications to the upstream frontend'}],'toolchain':toolchain,'build':{'parallelJobs':1,'configuration':'Release; ENABLE_OPENGL=ON; ENABLE_JIT=ON; FETCHCONTENT_FULLY_DISCONNECTED=ON','environmentOverrides':{'PATH':['{LLVM_ROOT}/bin','{GNU_MAKE_DIR}','{GIT_ROOT}/mingw64/bin','{GIT_ROOT}/usr/bin','caller-controlled remaining system PATH, not copied into this record']},'initialFailure':'Missing extern declaration for the existing upstream global Core reference; only the original adapter declaration was corrected. Failed log retained privately, not hidden or represented as a successful old binary.','initialCompileLogSHA256':sha(build/'compile.log'),'successfulResumeCompileLogSHA256':sha(build/'resume-01/compile.log')if resume else None,'bitIdenticalRebuildClaimed':False},'dependencies':[{k:v for k,v in row.items()if k in ['name','repository','commit','archive','gitlinks']}for row in lock['dependencies']]}
 report['patches']=patch_rows
 if not resume:
  report['build'].pop('initialFailure',None);report['build'].pop('successfulResumeCompileLogSHA256',None)
  report['build']['successfulCompileLogSHA256']=report['build'].pop('initialCompileLogSHA256')
 if (build/'melonds-foreign-fault.patch').is_file():report['build']['foreignFaultPassThrough']='Windows foreign access violations return EXCEPTION_CONTINUE_SEARCH before core fault handling and without calling the managed libretro logger; native ARM JIT remains enabled.'
 (package/'BUILD-RECORD.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
 (package/'BUILDING.md').write_text('''# DS link corresponding source\n\nThis is the complete official melonDS DS frontend f394adbacb5722ee97c1b37c8064da9a25818310 and every exact dependency listed in source-lock.json. The 3-hunk access/CMake patch is already applied. Its original adapter exports a bounded DMP1 frontend queue snapshot; it does not replace ordinary Console serialization. The upstream frontend and modifications remain GPL-3.0-or-later, and the separate original adapter is MIT. All vendor license/notice files remain in the complete source trees.\n\nBuild independently, offline, with Python 3, the locked LLVM-MinGW 20250613, GNU Make 4.4.1 and CMake 4.0.2 (official URLs and hashes are in BUILD-RECORD.json). Git for Windows supplies the recorded Make runtime dependencies. Set explicit paths; no Minecraft installation or workspace is needed:\n\n```text\npython -B tools/build_melondsds.py --source-tree <absolute-extracted-root> --llvm-root <absolute-LLVM-install> --make <absolute-mingw32-make.exe> --cmake <absolute-cmake.exe> --git-root <absolute-Git-install>\n```\n\nThe driver consumes the local dependency lock without fetching anything and builds one job at a time. Output: build/src/libretro/melondsds_libretro.dll. This is the same payload packaged under muxi_melondsds_link.dll; renaming does not modify DLL bytes. No original binary correspondence is inferred for the separate ordinary NDS build. Build paths and PE timestamps may differ, so bit-identical output is not claimed.\n\nThe extra preparation script records how the workspace source/patch build was assembled; it is not needed to rebuild this already-patched portable tree. Native diagnostics use only original ARM ROMs. No commercial game, manufacturer BIOS, credential, inherited host PATH, log text or runtime world is included.\n''',encoding='utf-8')
 if (build/'melonds-foreign-fault.patch').is_file():
  with (package/'BUILDING.md').open('a',encoding='utf-8')as instructions:
   instructions.write('\nThe additional already-applied GPL-3.0-or-later patch `patches/melonds-foreign-fault.patch` confines the Windows fast-memory vectored exception handler to the emulator\'s own JIT code. Foreign access violations return to the original handler chain without entering the libretro logger. Both Java C2 and native ARM JIT remain enabled; this is not a JIT-disable workaround. See the explicit diagnostic reports outside this source archive for the scope of actual endurance evidence.\n')
 source=out/'corresponding-source.tar.gz'
 with source.open('xb')as raw,gzip.GzipFile(filename='',mode='wb',fileobj=raw,mtime=0)as gz,tarfile.open(fileobj=gz,mode='w')as tar:
  for path in sorted(package.rglob('*')):
   if not path.is_file()or path.is_symlink():continue
   info=tar.gettarinfo(str(path),arcname='melondsds-link-corresponding-source/'+path.relative_to(package).as_posix());info.uid=info.gid=0;info.uname=info.gname='';info.mtime=0
   with path.open('rb')as stream:tar.addfile(info,stream)
 report['sourceArchive']={'path':source.name,'bytes':source.stat().st_size,'sha256':sha(source),'format':'tar.gz','patchesApplied':True};(out/'rebuild-public-report.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8');print(json.dumps({k:report[k]for k in ['library','archive','sourceArchive']},indent=2))
if __name__=='__main__':main()
