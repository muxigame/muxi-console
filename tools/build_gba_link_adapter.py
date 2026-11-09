"""Build our cooperative GBA cable adapter against the already pinned official mGBA tree."""
from __future__ import annotations
import argparse
import ctypes
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import zipfile

ROOT=Path(__file__).resolve().parents[1]
REVISION='7a12d6d4b9acb14c0ae62c9166b6a2f3d08007f6'
SOURCE_SHA='5cbf639e527fb586bf33e14d59037eab35f78f45154e0b79c784fff474c3bc37'
LLVM_LOCK={'url':'https://github.com/mstorsjo/llvm-mingw/releases/download/20250613/llvm-mingw-20250613-ucrt-x86_64.zip','sha256':'45145c035d9246e1de16f1873aa9afa863d93909f4a8f363e2eb38a04031d3c3'}
MAKE_LOCK={'url':'https://mirror.msys2.org/mingw/mingw64/mingw-w64-x86_64-make-4.4.1-5-any.pkg.tar.zst','sha256':'c19e7caf09bbc89b2730556b2da73004118d4e1f967a685cc48524c7ff80d864'}
CMAKE_LOCK={'url':'https://github.com/Kitware/CMake/releases/download/v4.0.2/cmake-4.0.2-windows-x86_64.zip','sha256':'109ec7de10416d6d78991bab9714d2cb1ccb71d1b436dff42ec978dd283c29fc'}

def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def descriptor(path,base):return {'path':Path(path).relative_to(base).as_posix(),'bytes':Path(path).stat().st_size,'sha256':sha(path)}

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--source-archive',type=Path);parser.add_argument('--llvm',type=Path);parser.add_argument('--make',type=Path);parser.add_argument('--cmake',type=Path);parser.add_argument('--git-root',type=Path)
    parser.add_argument('--standalone',action='store_true',help='Explicit portable source-package build, with no workspace/cache discovery')
    args=parser.parse_args()
    cache=ROOT.parent/'test-environment/cache/muxi-console/rebuild-mgba-link'
    out=args.output.resolve()
    if args.standalone and not all([args.source_archive,args.llvm,args.make,args.cmake,args.git_root]):raise RuntimeError('Portable build requires explicit source, LLVM, GNU Make, CMake and Git paths')
    if not args.standalone and cache.resolve() not in out.parents:raise RuntimeError('Output must remain in the registered GBA rebuild cache')
    out.mkdir(parents=True,exist_ok=False)
    source=(args.source_archive or ROOT.parent/f'test-environment/cache/muxi-console/third-party-source/archives/mgba-{REVISION}.tar.gz').resolve()
    if sha(source)!=SOURCE_SHA:raise RuntimeError('Official pinned source archive differs')
    with tarfile.open(source) as archive:
        epoch=int(archive.getmembers()[0].mtime);archive.extractall(out/'source',filter='data')
    tree=out/'source'/f'mgba-{REVISION}'
    llvm_root=(args.llvm or ROOT.parent/'test-environment/toolchains/muxi-console-rebuild-llvm-mingw-20250613/install').resolve()
    compilers=list(llvm_root.rglob('x86_64-w64-mingw32-clang.exe'))
    if len(compilers)!=1:raise RuntimeError('Existing isolated LLVM compiler is missing or ambiguous')
    llvm=compilers[0].parent
    make=(args.make or ROOT.parent/'test-environment/toolchains/muxi-console-rebuild-gnu-make-4.4.1/install/mingw64/bin/mingw32-make.exe').resolve()
    cmake=(args.cmake or Path(shutil.which('cmake') or '')).resolve()
    if not cmake.is_file() or not make.is_file():raise RuntimeError('Existing CMake/GNU Make missing; no download attempted')
    adapter=ROOT/'native/link/mgba_link_adapter.c';shutil.copyfile(adapter,out/'mgba_link_adapter.c')
    driver=Path(__file__).resolve();shutil.copyfile(driver,out/'build-driver-used.py');inputs={'driver':sha(driver),'adapter':sha(adapter)}
    wrapper='''cmake_minimum_required(VERSION 3.21)
project(MuxiGBACable C)
set(LIBMGBA_ONLY ON CACHE BOOL "" FORCE)
set(M_CORE_GBA ON CACHE BOOL "" FORCE)
set(M_CORE_GB OFF CACHE BOOL "" FORCE)
set(ENABLE_DEBUGGERS OFF CACHE BOOL "" FORCE)
set(ENABLE_SCRIPTING OFF CACHE BOOL "" FORCE)
set(BUILD_GL OFF CACHE BOOL "" FORCE)
set(BUILD_GLES2 OFF CACHE BOOL "" FORCE)
set(BUILD_GLES3 OFF CACHE BOOL "" FORCE)
add_compile_definitions(DISABLE_THREADING _GNU_SOURCE ENABLE_VFS ENABLE_DIRECTORIES)
add_subdirectory("source/mgba-'''+REVISION+'''" upstream)
add_library(muxi_mgba_link SHARED mgba_link_adapter.c)
target_include_directories(muxi_mgba_link PRIVATE "source/mgba-'''+REVISION+'''/include" "build/upstream/include")
target_link_libraries(muxi_mgba_link PRIVATE mgba)
set_target_properties(muxi_mgba_link PROPERTIES PREFIX "")
'''
    (out/'CMakeLists.txt').write_text(wrapper,encoding='utf-8')
    git=(args.git_root or Path(shutil.which('git')).resolve().parents[1]).resolve()
    env=os.environ.copy();env['PATH']=os.pathsep.join(map(str,[llvm,make.parent,git/'mingw64/bin',git/'usr/bin',cmake.parent]))+os.pathsep+env['PATH'];env['SOURCE_DATE_EPOCH']=str(epoch)
    env['GIT_CEILING_DIRECTORIES']=str(tree.parent)
    # Every child inherits one allowed CPU. This changes only this short-lived build process.
    kernel=ctypes.windll.kernel32;process=kernel.GetCurrentProcess();mask=ctypes.c_size_t();system=ctypes.c_size_t()
    if kernel.GetProcessAffinityMask(process,ctypes.byref(mask),ctypes.byref(system)):kernel.SetProcessAffinityMask(process,ctypes.c_size_t(mask.value&-mask.value))
    configure=[str(cmake),'-S',str(out),'-B',str(out/'build'),'-G','MinGW Makefiles',f'-DCMAKE_MAKE_PROGRAM={make.as_posix()}',f'-DCMAKE_C_COMPILER={(llvm/"x86_64-w64-mingw32-clang.exe").as_posix()}',f'-DCMAKE_CXX_COMPILER={(llvm/"x86_64-w64-mingw32-clang++.exe").as_posix()}','-DCMAKE_BUILD_TYPE=Release','-DCMAKE_SH=CMAKE_SH-NOTFOUND']
    commands=[configure,[str(cmake),'--build',str(out/'build'),'--target','muxi_mgba_link','--parallel','1']]
    record={'sourceRevision':REVISION,'sourceArchiveSHA256':SOURCE_SHA,'adapterSHA256':sha(adapter),'commands':commands,'thirdPartyLicense':'MPL-2.0','firstPartyLicense':'MIT'}
    (out/'preparation.json').write_text(json.dumps(record,indent=2),encoding='utf-8')
    for i,command in enumerate(commands):
        with (out/f'build-{i}.log').open('w',encoding='utf-8') as log:result=subprocess.run(command,env=env,cwd=out,stdout=log,stderr=subprocess.STDOUT)
        print(f'phase {i} exit {result.returncode}',flush=True)
        if result.returncode:raise SystemExit(result.returncode)
    if inputs!={'driver':sha(driver),'adapter':sha(adapter)}:raise RuntimeError('Build inputs changed; successful binary retained but no provenance export produced')
    dll=out/'build/muxi_mgba_link.dll';binary_zip=out/'muxi_mgba_link.dll.zip'
    with zipfile.ZipFile(binary_zip,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
        info=zipfile.ZipInfo(dll.name,(2000,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;z.writestr(info,dll.read_bytes())
    def version(exe):return subprocess.run([str(exe),'--version'],env=env,capture_output=True,text=True,check=True).stdout.splitlines()[0]
    tools={'llvmMinGW':dict(LLVM_LOCK,version=version(compilers[0]),executableSha256=sha(compilers[0])),
           'gnuMake':dict(MAKE_LOCK,version=version(make),executableSha256=sha(make)),
           'cmake':{'version':version(cmake),'executableSha256':sha(cmake)}}
    if tools['cmake']['version']=='cmake version 4.0.2':tools['cmake'].update(CMAKE_LOCK)
    dependencies={}
    for name in ['libintl-8.dll','libiconv-2.dll']:
        path=git/'mingw64/bin'/name
        if path.is_file():dependencies[name]=sha(path)
    tools['gitForWindows']={'version':subprocess.run([str(git/'cmd/git.exe'),'--version'],env=env,capture_output=True,text=True,check=True).stdout.strip(),'buildDependencyDllSha256':dependencies}
    report={'schemaVersion':1,'core':'mgba-link','sourceCommit':REVISION,'sourceOrigin':'https://github.com/libretro/mgba',
            'upstreamArchive':{'url':f'https://codeload.github.com/libretro/mgba/tar.gz/{REVISION}','sha256':SOURCE_SHA},
            'library':descriptor(dll,out),'archive':descriptor(binary_zip,out),'buildScript':{'path':'tools/build_gba_link_adapter.py','sha256':inputs['driver']},
            'adapter':{'path':'native/link/mgba_link_adapter.c','sha256':inputs['adapter'],'license':'MIT'},'toolchain':tools,
            'build':{'sourceDateEpoch':epoch,'configuration':'Release','parallelJobs':1,'cpuAffinity':'one allowed CPU',
                     'defines':['DISABLE_THREADING','_GNU_SOURCE','ENABLE_VFS','ENABLE_DIRECTORIES'],
                     'upstreamConfiguration':{'LIBMGBA_ONLY':True,'M_CORE_GBA':True,'M_CORE_GB':False,'ENABLE_DEBUGGERS':False,'ENABLE_SCRIPTING':False,'BUILD_GL':False,'BUILD_GLES2':False,'BUILD_GLES3':False},
                     'pathComponents':['LLVM/bin','GNU-Make/bin','Git/mingw64/bin','Git/usr/bin','CMake/bin','inherited system PATH (not distributed)']},
            'patches':[],'thirdPartyLicense':'MPL-2.0 and original bundled notices','nativeGameplayTested':False,'minecraftTested':False,'bitIdenticalRebuildClaimed':False}
    instructions='''# Building the original GBA cable adapter\n\nThis package contains the complete fixed official mGBA source archive, its original licenses/notices, and our MIT adapter/build frontend. No ROM or proprietary BIOS is included. No upstream patch is applied.\n\nBuild on Windows x86-64 with Python 3.12+, LLVM-MinGW 20250613 UCRT (Clang 20.1.7), GNU Make 4.4.1, CMake 4.0.2, and Git for Windows. Exact official tool URLs/hashes and actual executable hashes are in BUILD-RECORD.json. Git's mingw64 libintl/iconv DLLs satisfy the MSYS2 GNU Make executable's dependencies; they are build tools, not included in the emulator DLL.\n\nFrom this package root, with explicit existing tool paths:\n\n```powershell\npython -B tools/build_gba_link_adapter.py --standalone --source-archive upstream/mgba-'''+REVISION+'''.tar.gz --llvm C:/tools/llvm-mingw --make C:/tools/mingw64/bin/mingw32-make.exe --cmake C:/tools/cmake/bin/cmake.exe --git-root C:/tools/Git --output C:/build/muxi-gba-link\n```\n\nThe output directory must not exist. The driver never downloads tools or game data, never changes global environment variables, and builds with one job/CPU. The only emulated system is GBA. Software video and mGBA's real lockstep coordinator are compiled into muxi_mgba_link.dll. The native API steps both CPU boards cooperatively and exports each single-machine MXS1 separately from native combined MGL1. The application adds physical machine/content/actual-library identities outside the native MGL1 container.\n\nCorresponding-source provenance is asserted for the distributed DLL. Byte-identical independent rebuilds and commercial-game compatibility are not asserted. Build logs and gameplay probe records are separate evidence, not hidden build inputs.\n'''
    entries={'LICENSE':(ROOT/'LICENSE').read_bytes(),'BUILDING.md':instructions.encode(),'BUILD-RECORD.json':json.dumps(report,indent=2).encode(),
             'tools/build_gba_link_adapter.py':(out/'build-driver-used.py').read_bytes(),'native/link/mgba_link_adapter.c':(out/'mgba_link_adapter.c').read_bytes(),
             'upstream/'+source.name:source.read_bytes(),'CMakeLists-used.txt':wrapper.encode()}
    archive_path=out/'corresponding-source.tar.gz'
    with archive_path.open('wb') as raw,gzip.GzipFile(fileobj=raw,mode='wb',mtime=0) as compressed,tarfile.open(fileobj=compressed,mode='w') as archive:
        import io
        for name,data in sorted(entries.items()):
            info=tarfile.TarInfo(name);info.size=len(data);info.mtime=0;info.mode=0o644;archive.addfile(info,io.BytesIO(data))
    report['sourceArchive']=dict(descriptor(archive_path,out),format='tar.gz',patchesApplied=False)
    (out/'build-report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps({'library':report['library'],'archive':report['archive'],'sourceArchive':report['sourceArchive']}),flush=True)

if __name__=='__main__':main()
