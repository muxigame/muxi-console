#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Build two pinned third-party cores without replacing any product or upstream pin.

This original driver is MIT. Native sources and any patches retain the upstream
licenses; building a new binary does not establish the provenance of an old one.
Only dedicated shared test-environment toolchain/cache directories are written.
"""
from __future__ import annotations
import argparse
import concurrent.futures
import ctypes
import datetime
import gzip
import hashlib
import io
import json
import os
import re
from pathlib import Path
import shutil
import struct
import subprocess
import sys
import tarfile
import time
import urllib.request
import zipfile

REPO = Path(__file__).resolve().parents[1]
WORKSPACE = REPO.parent
ENVIRONMENT = WORKSPACE / "test-environment"
LLVM = {"name":"llvm-mingw-20250613-ucrt-x86_64.zip", "size":152446205,
        "url":"https://github.com/mstorsjo/llvm-mingw/releases/download/20250613/llvm-mingw-20250613-ucrt-x86_64.zip",
        "sha256":"45145c035d9246e1de16f1873aa9afa863d93909f4a8f363e2eb38a04031d3c3"}
MAKE = {"name":"mingw-w64-x86_64-make-4.4.1-5-any.pkg.tar.zst",
        "url":"https://mirror.msys2.org/mingw/mingw64/mingw-w64-x86_64-make-4.4.1-5-any.pkg.tar.zst",
        "sha256":"c19e7caf09bbc89b2730556b2da73004118d4e1f967a685cc48524c7ff80d864"}
CORES = {
    "blastem":{"repository":"https://github.com/libretro/blastem.git",
               "commit":"ab743653ed110eeb7b1fe64d00cc5f9279c2bfec", "output":"libblastem.dll", "library":"blastem_libretro.dll"},
    "pce":{"repository":"https://github.com/libretro/beetle-pce-fast-libretro.git",
           "commit":"2de1ce1c3f237b161c126c54ba407990bb86d1e9", "output":"mednafen_pce_fast_libretro.dll", "library":"mednafen_pce_fast_libretro.dll"},
}
EXPORTS = ("retro_api_version", "retro_init", "retro_deinit", "retro_get_system_info", "retro_get_system_av_info",
           "retro_set_environment", "retro_set_video_refresh", "retro_set_audio_sample", "retro_set_audio_sample_batch",
           "retro_set_input_poll", "retro_set_input_state", "retro_set_controller_port_device", "retro_reset", "retro_run",
           "retro_serialize_size", "retro_serialize", "retro_unserialize", "retro_cheat_reset", "retro_cheat_set",
           "retro_load_game", "retro_load_game_special", "retro_unload_game", "retro_get_region", "retro_get_memory_data", "retro_get_memory_size")


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024*1024), b""): digest.update(chunk)
    return digest.hexdigest()


def describe(path):
    path=Path(path)
    return {"path":str(path),"size":path.stat().st_size,"sha256":sha(path)}


def write_json(path, value):
    Path(path).write_text(json.dumps(value, ensure_ascii=False, indent=2)+"\n", encoding="utf-8")


def download(pin, folder):
    folder.mkdir(parents=True, exist_ok=True)
    target=folder/pin["name"]
    if target.exists():
        if sha(target)!=pin["sha256"]: raise ValueError("Existing tool archive differs from reviewed lock: "+str(target))
        return target
    partial=target.with_name(target.name+".part")
    if partial.exists(): raise ValueError("Preserve incomplete download before an explicit new attempt: "+str(partial))
    request=urllib.request.Request(pin["url"],headers={"User-Agent":"MuxiConsole-ReproducibleCoreBuild/1","Accept-Encoding":"identity"})
    digest=hashlib.sha256(); size=0; announced=0
    with urllib.request.urlopen(request, timeout=45) as response, partial.open("xb") as stream:
        while chunk:=response.read(1024*1024):
            size+=len(chunk)
            if size>300*1024*1024: raise ValueError("Tool archive exceeds fixed download limit")
            stream.write(chunk); digest.update(chunk)
            if size-announced>=16*1024*1024:
                print(pin["name"], size//1048576,"MiB",flush=True); announced=size
    if digest.hexdigest()!=pin["sha256"] or "size" in pin and size!=pin["size"]: raise ValueError("Tool archive failed its exact upstream lock")
    partial.rename(target)
    print("verified",target.name,size,"bytes",flush=True)
    return target


def safe_member(folder, name):
    path=Path(name)
    if path.is_absolute() or ".." in path.parts or "\\" in name or ":" in name: raise ValueError("Unsafe tool archive member")
    result=folder/path
    if not result.resolve().is_relative_to(folder.resolve()): raise ValueError("Archive member escapes dedicated folder")
    return result


def tools():
    llvm_root=ENVIRONMENT/"toolchains/muxi-console-rebuild-llvm-mingw-20250613"
    make_root=ENVIRONMENT/"toolchains/muxi-console-rebuild-gnu-make-4.4.1"
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        a=pool.submit(download,LLVM,llvm_root/"downloads")
        b=pool.submit(download,MAKE,make_root/"downloads")
        llvm_zip,make_zst=a.result(),b.result()
    llvm_bin=llvm_root/"install/llvm-mingw-20250613-ucrt-x86_64/bin"
    if not (llvm_root/"extraction-complete.json").exists():
        with zipfile.ZipFile(llvm_zip) as archive:
            for info in archive.infolist():
                path=safe_member(llvm_root/"install",info.filename)
                if info.is_dir():path.mkdir(parents=True,exist_ok=True)
                else:
                    path.parent.mkdir(parents=True,exist_ok=True)
                    with archive.open(info) as source,path.open("wb") as target:shutil.copyfileobj(source,target)
        write_json(llvm_root/"extraction-complete.json",LLVM)
    if not (make_root/"extraction-complete.json").exists():
        import zstandard
        with make_zst.open("rb") as compressed,zstandard.ZstdDecompressor().stream_reader(compressed) as stream,tarfile.open(fileobj=stream,mode="r|") as archive:
            for info in archive:
                path=safe_member(make_root/"install",info.name)
                if info.isdir():path.mkdir(parents=True,exist_ok=True)
                elif info.isfile():
                    path.parent.mkdir(parents=True,exist_ok=True)
                    with archive.extractfile(info) as source,path.open("wb") as target:shutil.copyfileobj(source,target)
                else:raise ValueError("Unexpected linked member in GNU make package")
        write_json(make_root/"extraction-complete.json",MAKE)
    make_bin=make_root/"install/mingw64/bin"
    env=dict(os.environ)
    git=Path(shutil.which("git")).resolve().parents[1]
    env["PATH"]=os.pathsep.join(map(str,[llvm_bin,make_bin,git/"usr/bin",git/"mingw64/bin",Path(sys.executable).parent]))+os.pathsep+env.get("PATH","")
    env["MSYS2_ARG_CONV_EXCL"]="*"
    shell=git/"usr/bin/sh.exe"
    buf=ctypes.create_unicode_buffer(32768)
    if ctypes.windll.kernel32.GetShortPathNameW(str(shell),buf,len(buf)):shell=Path(buf.value)
    env["SHELL"]=shell.as_posix()
    make=make_bin/"mingw32-make.exe"
    versions=[]
    for command in ([make,"--version"],[llvm_bin/"x86_64-w64-mingw32-clang.exe","--version"]):
        row=subprocess.run(list(map(str,command)),env=env,capture_output=True,text=True,timeout=20)
        if row.returncode:raise RuntimeError("Tool did not start: "+row.stdout+row.stderr)
        print(row.stdout.splitlines()[0],flush=True)
        versions.append(row.stdout.splitlines()[0])
    return llvm_bin,make,env,[dict(LLVM,archive=describe(llvm_zip),version=versions[1],executable=describe(llvm_bin/"clang.exe")),dict(MAKE,archive=describe(make_zst),version=versions[0],executable=describe(make))]


def run(command,cwd,env,log):
    print("run",subprocess.list2cmdline(list(map(str,command))),flush=True)
    with Path(log).open("xb") as stream:
        process=subprocess.run(list(map(str,command)),cwd=cwd,env=env,stdout=stream,stderr=subprocess.STDOUT)
    if process.returncode:raise RuntimeError(f"Native build command failed ({process.returncode}); retained {log}")


def git_output(source,*args):
    return subprocess.check_output(["git","-c","core.autocrlf=false","-C",str(source),*args]).decode("utf-8").strip()


def source_tree(root, spec, env):
    source=root/"source"
    if not source.exists():
        source.mkdir()
        run(["git","init",source],root,env,root/"git-init.log")
        run(["git","-C",source,"remote","add","origin",spec["repository"]],root,env,root/"git-remote.log")
    if not (source/".git").is_dir():raise ValueError("Existing source folder is not an isolated checkout")
    attempt=max(len(list(root.glob("git-fetch-*.log"))),len(list(root.glob("git-submodules-*.log"))))+1
    try:revision=git_output(source,"rev-parse","HEAD")
    except subprocess.CalledProcessError:revision=None
    if revision!=spec["commit"]:
        if revision is not None:raise ValueError("Refusing to change an existing native source revision")
        run(["git","-c","http.sslVerify=true","-C",source,"fetch","--depth=1","origin",spec["commit"]],root,env,root/f"git-fetch-{attempt}.log")
        run(["git","-c","core.autocrlf=false","-C",source,"checkout","--detach",spec["commit"]],root,env,root/f"git-checkout-{attempt}.log")
    run(["git","-c","http.sslVerify=true","-C",source,"submodule","update","--init","--recursive","--depth=1"],root,env,root/f"git-submodules-{attempt}.log")
    if git_output(source,"rev-parse","HEAD")!=spec["commit"]:raise ValueError("Native source revision changed")
    subs=git_output(source,"submodule","status","--recursive")
    if any(line.startswith(("-","+","U"))for line in subs.splitlines()):raise ValueError("Native submodule differs from its recorded gitlink")
    return source,subs


def pe_info(path):
    data=Path(path).read_bytes(); pe=struct.unpack_from("<I",data,0x3c)[0]
    if data[:2]!=b"MZ" or data[pe:pe+4]!=b"PE\0\0" or struct.unpack_from("<H",data,pe+4)[0]!=0x8664:raise ValueError("Rebuilt DLL is not PE x86_64")
    return {"machine":"0x8664","format":"PE32+","timestamp":struct.unpack_from("<I",data,pe+8)[0]}


def probe(path):
    class SystemInfo(ctypes.Structure):
        _fields_=[("name",ctypes.c_char_p),("version",ctypes.c_char_p),("extensions",ctypes.c_char_p),("need_fullpath",ctypes.c_bool),("block_extract",ctypes.c_bool)]
    core=ctypes.CDLL(str(path)); exports=[]
    for name in EXPORTS:getattr(core,name); exports.append(name)
    core.retro_api_version.restype=ctypes.c_uint
    if core.retro_api_version()!=1:raise ValueError("Unsupported rebuilt libretro ABI")
    info=SystemInfo();core.retro_get_system_info.argtypes=[ctypes.POINTER(SystemInfo)];core.retro_get_system_info(ctypes.byref(info))
    if not info.name:raise ValueError("Rebuilt core lacks its library identity")
    return {"apiVersion":1,"name":info.name.decode(),"version":info.version.decode()if info.version else "","extensions":info.extensions.decode()if info.extensions else "","exports":exports,"gameCompatibilityTested":False}


def source_archive(root,source,report):
    destination=root/(report["core"]+"-corresponding-source.tar.gz")
    entries=subprocess.check_output(["git","-C",str(source),"ls-files","--stage","--recurse-submodules","-z"]).decode("utf-8").split("\0")
    with destination.open("xb")as raw,gzip.GzipFile(filename="",mode="wb",fileobj=raw,mtime=0)as compressed,tarfile.open(fileobj=compressed,mode="w")as archive:
        def add(name,payload,mode=0o644):
            info=tarfile.TarInfo(name); info.size=len(payload);info.mode=mode;info.mtime=0;archive.addfile(info,io.BytesIO(payload))
        for entry in sorted(filter(None,entries)):
            prefix,name=entry.split("\t",1);mode=prefix.split()[0]
            if mode=="160000":raise ValueError("Unexpanded submodule in corresponding source archive")
            path=safe_member(source,name)
            if path.is_symlink():raise ValueError("Source archive symlink requires an explicit audited representation")
            add("source/"+name,path.read_bytes(),0o755 if mode=="100755"else 0o644)
        add("rebuild_cores.py",Path(__file__).read_bytes(),0o755)
        add("BUILD.json",(json.dumps(report,ensure_ascii=False,indent=2)+"\n").encode())
        for name in ("rebuild-report.json","build-driver.py","retained-artifacts.json"):
            historical=root/"initial-success"/name
            if historical.exists():add("history/"+name,historical.read_bytes())
        add("BUILDING.md",("# Rebuild this exact third-party core\n\n"
            "The source/ directory contains the complete preferred source form, including vendored dependencies and original licenses. "
            "No git checkout or network source fetch is needed for the offline command below. Native sources retain their own licenses; "
            "rebuild_cores.py is original MIT code. No semantic native source patch is applied.\n\n"
            "Use Windows x86_64, Python 3, Git for Windows sh/sed/tr/mkdir and the exact LLVM-MinGW/GNU make archives locked in BUILD.json. "
            "Extract the tool archives and supply the resulting bin/executable paths; GNU make's gettext dependency must be available "
            "alongside make or via Git's mingw64/bin. Only the subprocess environment is changed.\n\n"
            "From this extracted archive directory, run (replace only the two tool paths):\n\n"
            "```powershell\npython -B ./rebuild_cores.py --core "+report["core"]+
            " --source-tree ./source --llvm-bin C:/tools/llvm-mingw/bin --make C:/tools/mingw64/bin/mingw32-make.exe\n```\n\n"
            "The driver recreates any explicitly recorded data-embedding step, uses -j1 and the exact BUILD.json flags, "
            "and writes the upstream DLL target inside source/. The BlastEm byte embedding preserves all input bytes while "
            "avoiding Windows native-make/MSYS shell backslash requoting; its preferred data sources and generator are included. "
            "PCE's build variables omit the GNU ELF version-script for the LLVM PE/COFF linker. "
            "Both links explicitly disable PE insertion timestamps. All these adaptations are build steps, not upstream runtime logic changes.\n\n"
            "Check the resulting DLL SHA256 against BUILD.json library.sha256. It is a new build of the recorded commit, "
            "and does not establish the source revision of any previous rolling upstream DLL.\n").encode("utf-8"))
        for patch in root.glob("*.patch"):add("patches/"+patch.name,patch.read_bytes())
    return dict(describe(destination),format="tar.gz",patchesApplied=bool(report["patches"]),
                completePreferredSource=True,buildInstructions="BUILDING.md",sourceDirectory="source")


def embed_blastem_data(source):
    """Avoid native make/MSYS shell backslash re-quoting; preserve every data byte."""
    result=[]
    for name in ("rom.db","systems.cfg"):
        original=source/name; data=original.read_bytes()
        symbol=name.replace(".","_")+"_data"
        lines=[f"const char {symbol}[] ="]
        for offset in range(0,len(data),64):
            lines.append('"'+''.join(f"\\{value:03o}" for value in data[offset:offset+64])+'"')
        lines.append(";")
        generated=source/(name+".c")
        generated.write_bytes(('\n'.join(lines)+'\n').encode('ascii'))
        result.append({"operation":"Byte-exact C string embedding before make; no source or runtime semantic patch",
                       "generator":"embed_blastem_data in rebuild_cores.py", "input":describe(original), "generated":describe(generated)})
    return result


def variables_for(name,llvm_bin,shell,python):
    variables={"SHELL":shell,"CC":(llvm_bin/"x86_64-w64-mingw32-clang.exe").as_posix()}
    if name=="blastem":variables.update(OS="Windows",CPU="x86_64",LIBRETRO="1",NOLTO="1",PYTHON=Path(python).as_posix(),LIBLDFLAGS="-Wl,--no-insert-timestamp")
    else:variables.update(platform="win",CXX=(llvm_bin/"x86_64-w64-mingw32-clang++.exe").as_posix(),SHARED="-shared -Wl,--no-undefined -Wl,--no-insert-timestamp",GIT_VERSION=CORES[name]["commit"][:7])
    return variables


def build(name,llvm_bin,make,env,toolchain,relink=False):
    root=ENVIRONMENT/("cache/muxi-console/rebuild-"+name); root.mkdir(parents=True,exist_ok=True)
    if (root/"rebuild-report.json").exists():raise ValueError("Preserve successful build; do not overwrite its evidence")
    spec=CORES[name]; source,submodules=source_tree(root,spec,env)
    env=dict(env);env["SOURCE_DATE_EPOCH"]=git_output(source,"show","-s","--format=%ct","HEAD")
    preparation=embed_blastem_data(source) if name=="blastem"else []
    variables=variables_for(name,llvm_bin,env["SHELL"],sys.executable)
    target=[spec["output"]]if name=="blastem"else []
    force=["-W","link.T"if name=="blastem"else "libretro.o"]if relink else []
    command=[make,"-j1",*force,*[key+"="+value for key,value in variables.items()],*target]
    attempt=len(list(root.glob("build-*.log")))+1;log=root/f"build-{attempt}.log"
    run(command,source,env,log)
    output=source/spec["output"]; library=root/spec["library"]
    if library.exists():raise ValueError("Preserve existing rebuilt DLL")
    shutil.copyfile(output,library)
    pe=pe_info(library)
    if pe["timestamp"]!=0:raise ValueError("Deterministic PE timestamp was not applied")
    inspection=subprocess.run([str(llvm_bin/"llvm-readobj.exe"),"--coff-imports","--coff-exports",str(library)],env=env,capture_output=True,text=True,timeout=30)
    if inspection.returncode:raise ValueError("PE import/export inspection failed")
    pe["imports"]=re.findall(r"Import \{.*?Name: ([^\r\n]+)",inspection.stdout,re.S)
    if any(not (name.lower().startswith("api-ms-win-")or name.lower()in {"kernel32.dll","shell32.dll","ws2_32.dll","ole32.dll","comdlg32.dll","user32.dll","gdi32.dll","advapi32.dll","ucrtbase.dll","msvcrt.dll"})for name in pe["imports"]):
        raise ValueError("Rebuilt DLL has an unexpected non-system runtime dependency: "+str(pe["imports"]))
    (root/"pe-imports-exports.txt").write_text(inspection.stdout,encoding="utf-8")
    result=subprocess.run([sys.executable,str(Path(__file__)),"--probe",str(library)],env=env,capture_output=True,text=True,timeout=30)
    (root/"api-probe.log").write_text(result.stdout+result.stderr,encoding="utf-8")
    if result.returncode:raise ValueError("Rebuilt core API probe failed")
    archive=root/(spec["library"]+".zip")
    with zipfile.ZipFile(archive,"x",compression=zipfile.ZIP_DEFLATED,compresslevel=9)as zipped:
        info=zipfile.ZipInfo(spec["library"],(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;zipped.writestr(info,library.read_bytes())
    report={"schemaVersion":1,"core":name,"repository":spec["repository"],"sourceCommit":spec["commit"],"submodules":submodules.splitlines(),
            "provenanceScope":"This build only; no correspondence is claimed for the old rolling DLL.","buildScript":dict(describe(__file__),license="MIT"),"toolchain":toolchain,
            "build":{"command":list(map(str,command)),"variables":variables,"preparationSteps":preparation,"environmentOverrides":{"PATH":env["PATH"],"SHELL":env["SHELL"],"MSYS2_ARG_CONV_EXCL":env["MSYS2_ARG_CONV_EXCL"],"SOURCE_DATE_EPOCH":env["SOURCE_DATE_EPOCH"]},"attemptLogs":[describe(p)for p in sorted(root.glob("build-*.log"))]},
            "patches":[describe(p)for p in sorted(root.glob("*.patch"))],"library":dict(describe(library),name=library.name),"archive":describe(archive),"pe":pe,"probe":dict(json.loads(result.stdout),normalExit=True),"createdUtc":datetime.datetime.now(datetime.timezone.utc).isoformat()}
    if relink:
        historical=root/"initial-success"
        report["history"]={"stage":"Deterministic relink of objects compiled in the preserved initial successful build; no runtime source or compile-flag change",
                           "initialReport":describe(historical/"rebuild-report.json"),"initialDriver":describe(historical/"build-driver.py"),"retainedArtifacts":describe(historical/"retained-artifacts.json")}
    report["sourceArchive"]=source_archive(root,source,report)
    write_json(root/"rebuild-report.json",report)
    print("SUCCESS",name,json.dumps(report["library"]),flush=True)


def offline_build(args):
    if args.core=="all"or not args.llvm_bin or not args.make:raise ValueError("Offline rebuild requires one --core, --llvm-bin and --make")
    source=args.source_tree.resolve(strict=True);llvm=args.llvm_bin.resolve(strict=True);make=args.make.resolve(strict=True)
    if not (source/"Makefile").is_file():raise ValueError("Extracted source directory lacks its upstream Makefile")
    git=Path(shutil.which("git")).resolve().parents[1]
    env=dict(os.environ);env["PATH"]=os.pathsep.join(map(str,[llvm,make.parent,git/"usr/bin",git/"mingw64/bin",Path(sys.executable).parent]))+os.pathsep+env.get("PATH","")
    shell=git/"usr/bin/sh.exe";buf=ctypes.create_unicode_buffer(32768)
    if ctypes.windll.kernel32.GetShortPathNameW(str(shell),buf,len(buf)):shell=Path(buf.value)
    env["SHELL"]=shell.as_posix();env["MSYS2_ARG_CONV_EXCL"]="*"
    record=json.loads((source.parent/"BUILD.json").read_text(encoding="utf-8"))
    if record["sourceCommit"]!=CORES[args.core]["commit"]:raise ValueError("Offline archive does not match the selected exact commit")
    env["SOURCE_DATE_EPOCH"]=record["build"]["environmentOverrides"]["SOURCE_DATE_EPOCH"]
    if args.core=="blastem":embed_blastem_data(source)
    variables=variables_for(args.core,llvm,env["SHELL"],sys.executable)
    target=[CORES[args.core]["output"]]if args.core=="blastem"else []
    subprocess.run([str(make),"-j1",*[key+"="+value for key,value in variables.items()],*target],cwd=source,env=env,check=True)
    artifact=source/CORES[args.core]["output"]
    if sha(artifact)!=record["library"]["sha256"]:raise ValueError("Offline rebuild completed but DLL differs from the recorded exact bytes")
    print(json.dumps(describe(artifact)))


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--core",choices=("blastem","pce","all"),default="all")
    parser.add_argument("--tools-only",action="store_true")
    parser.add_argument("--relink",action="store_true",help="Force the final link while retaining all existing objects")
    parser.add_argument("--source-tree",type=Path,help="Offline build from the source/ directory in a corresponding-source archive; never fetches source or tools")
    parser.add_argument("--llvm-bin",type=Path)
    parser.add_argument("--make",type=Path)
    parser.add_argument("--probe",type=Path,help=argparse.SUPPRESS)
    args=parser.parse_args()
    if args.probe:print(json.dumps(probe(args.probe)));return
    if args.source_tree:offline_build(args);return
    llvm_bin,make,env,toolchain=tools()
    if args.tools_only:return
    for name in CORES if args.core=="all"else [args.core]:build(name,llvm_bin,make,env,toolchain,args.relink)


if __name__=="__main__":main()
