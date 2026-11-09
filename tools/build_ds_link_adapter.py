"""Offline single-job DS link frontend rebuild from exact official source/dependency archives.

The original bounded snapshot adapter is MIT. The linked and patched upstream frontend retains GPL-3.0-or-later.
This driver never replaces the ordinary DS pin or its cached source/build tree.
"""
from pathlib import Path,PurePosixPath
import argparse,difflib,hashlib,importlib.util,json,os,shutil,subprocess,tarfile
ROOT=Path(__file__).resolve().parents[1]
CACHE=ROOT.parent/'test-environment/cache/muxi-console'
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def main():
 ap=argparse.ArgumentParser();ap.add_argument('--output',type=Path,required=True);ap.add_argument('--resume',action='store_true');ap.add_argument('--foreign-fault-pass-through',action='store_true',help='Explicit GPL upstream Windows VEH patch: foreign faults never call Java-backed logging');args=ap.parse_args();out=args.output.resolve()
 if not out.is_relative_to((CACHE/'rebuild-melondsds-link').resolve()):raise ValueError('DS link rebuild must stay in its owned cache')
 if args.resume:
  if args.foreign_fault_pass_through:raise ValueError('The foreign-fault patch needs its own new source/build directory')
  history=out/'resume-01';history.mkdir(exist_ok=False);source=out/'source/frontend/src/libretro/muxi_link_adapter.cpp';adapter=ROOT/'native/link/melondsds_link_adapter.cpp';shutil.copyfile(source,history/'previous-adapter.cpp');shutil.copyfile(adapter,source);shutil.copyfile(Path(__file__),history/'driver-used.py');record={'reason':'The original failed compile had no declaration for the frontend global Core reference; only the original adapter declaration changes. Failed configure/compile logs and preparation remain untouched.','previousAdapterSHA256':sha(history/'previous-adapter.cpp'),'actualAdapterSHA256':sha(adapter),'driverSHA256':sha(Path(__file__))};(history/'record.json').write_text(json.dumps(record,indent=2)+'\n',encoding='utf-8')
  tools=ROOT.parent/'test-environment/toolchains';llvm=next((tools/'muxi-console-rebuild-llvm-mingw-20250613').rglob('x86_64-w64-mingw32-clang.exe'));make=next((tools/'muxi-console-rebuild-gnu-make-4.4.1').rglob('mingw32-make.exe'));git=Path('C:/Program Files/Git');env=dict(os.environ);env['PATH']=os.pathsep.join([str(llvm.parent),str(make.parent),str(git/'mingw64/bin'),str(git/'usr/bin'),env['PATH']]);command=['C:/Program Files/CMake/bin/cmake.exe','--build',str(out/'build'),'--parallel','1']
  with (history/'compile.log').open('w',encoding='utf-8')as log:p=subprocess.run(command,env=env,stdout=log,stderr=subprocess.STDOUT)
  if p.returncode:raise RuntimeError('Resumed native compile failed; original and resumed logs retained separately')
  dlls=list((out/'build').rglob('melondsds_libretro.dll'))
  if len(dlls)!=1 or sha(source)!=record['actualAdapterSHA256']:raise RuntimeError('Resumed adapter identity changed')
  print(json.dumps({'library':str(dlls[0]),'bytes':dlls[0].stat().st_size,'sha256':sha(dlls[0]),'sourceScope':'exact official lock plus explicit access patch and original adapter; initial failure retained; public source export pending'},indent=2));return
 out.mkdir(parents=True,exist_ok=False);original=CACHE/'expansion/melondsds/rebuild';lock=json.loads((original/'source-lock.json').read_text(encoding='utf-8'));patches=[]
 for row in lock['dependencies']:
  archive=original/(row['name']+'-'+row['commit']+'.tar.gz')
  if sha(archive)!=row['archive']['sha256']:raise RuntimeError('Original source archive changed: '+row['name'])
  target=out/'source'/row['name'];target.mkdir(parents=True);row['sourceDirectory']='source/'+row['name']
  with tarfile.open(archive)as source:
   for entry in source:
    path=PurePosixPath(entry.name)
    if path.is_absolute()or'..'in path.parts:raise ValueError('Unsafe source archive')
    if len(path.parts)==1:continue
    entry.name=PurePosixPath(*path.parts[1:]).as_posix();source.extract(entry,target,filter='data')
 def edit(relative,before,after):
  path=out/'source/frontend'/relative;old=path.read_text(encoding='utf-8')
  if old.count(before)!=1:raise RuntimeError('Expected unique source patch context: '+relative)
  new=old.replace(before,after);path.write_text(new,encoding='utf-8');patches.extend(difflib.unified_diff(old.splitlines(True),new.splitlines(True),fromfile='a/'+relative,tofile='b/'+relative))
 edit('src/libretro/core/core.hpp','        bool MpActive() const noexcept;','        bool MpActive() const noexcept;\n        MpState& MuxiMpState() noexcept { return _mpState; }')
 edit('src/libretro/net/mp.hpp','class MpState {\npublic:','class MpState {\n    friend struct MuxiMpSnapshot;\npublic:')
 edit('src/libretro/CMakeLists.txt','add_library(melondsds_libretro ${LIBRARY_TYPE}\n','add_library(melondsds_libretro ${LIBRARY_TYPE}\n    muxi_link_adapter.cpp\n')
 if args.foreign_fault_pass_through:
  path=out/'source/melonds/src/ARMJIT_Memory.cpp';old=path.read_text(encoding='utf-8')
  before='''    u8* curArea = (u8*)(NDS::Current->CurCPU == 0 ? NDS::Current->JIT.Memory.FastMem9Start : NDS::Current->JIT.Memory.FastMem7Start);
    FaultDescription desc {};
    desc.EmulatedFaultAddr = (u8*)exceptionInfo->ExceptionRecord->ExceptionInformation[1] - curArea;'''
  after='''    // Other runtimes use access violations for their own implicit null checks.
    // Never re-enter their managed logger from a foreign exception context.
    auto* current = NDS::Current;
    if (!current || !current->JIT.JITCompiler.IsJITFault((u8*)exceptionInfo->ContextRecord->CONTEXT_PC))
        return EXCEPTION_CONTINUE_SEARCH;

    u8* curArea = (u8*)(current->CurCPU == 0 ? current->JIT.Memory.FastMem9Start : current->JIT.Memory.FastMem7Start);
    FaultDescription desc {};
    desc.EmulatedFaultAddr = (u8*)exceptionInfo->ExceptionRecord->ExceptionInformation[1] - curArea;'''
  logging='    Log(LogLevel::Debug, "it all returns to nothing\\n");\n    return EXCEPTION_CONTINUE_SEARCH;'
  if old.count(before)!=1 or old.count(logging)!=1:raise RuntimeError('Expected exact official Windows VEH patch context')
  new=old.replace(before,after).replace(logging,'    return EXCEPTION_CONTINUE_SEARCH;');path.write_text(new,encoding='utf-8')
  patch=''.join(difflib.unified_diff(old.splitlines(True),new.splitlines(True),fromfile='a/src/ARMJIT_Memory.cpp',tofile='b/src/ARMJIT_Memory.cpp'));(out/'melonds-foreign-fault.patch').write_text(patch,encoding='utf-8')
 adapter=ROOT/'native/link/melondsds_link_adapter.cpp';shutil.copyfile(adapter,out/'source/frontend/src/libretro/muxi_link_adapter.cpp');(out/'source-lock.json').write_text(json.dumps(lock,indent=2)+'\n',encoding='utf-8');(out/'frontend-access.patch').write_text(''.join(patches),encoding='utf-8')
 driver=ROOT/'tools/build_melondsds.py';shutil.copyfile(driver,out/'build-upstream-driver-used.py');shutil.copyfile(Path(__file__),out/'build-link-driver-used.py')
 (out/'preparation.json').write_text(json.dumps({'officialDependencies':lock,'adapterSHA256':sha(adapter),'accessPatchSHA256':sha(out/'frontend-access.patch'),'upstreamDriverSHA256':sha(driver),'originalDriverSHA256':sha(Path(__file__))},indent=2)+'\n',encoding='utf-8')
 spec=importlib.util.spec_from_file_location('owned_melondsds_link_build',driver);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);module.CACHE=out;module.build()
 dlls=list((out/'build').rglob('melondsds_libretro.dll'))
 if len(dlls)!=1:raise RuntimeError('Expected exactly one built DS link frontend')
 if sha(adapter)!=json.loads((out/'preparation.json').read_text())['adapterSHA256']:raise RuntimeError('Adapter changed during native build')
 print(json.dumps({'library':str(dlls[0]),'bytes':dlls[0].stat().st_size,'sha256':sha(dlls[0]),'sourceScope':'exact official lock plus explicit access patch and original adapter; public source export pending'},indent=2))
if __name__=='__main__':main()
