#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Export the exact assembled Dolphin preferred-source tree and SD sharing patch.

No binary pin/release-ready flag is updated. Public records contain portable
paths, not the compiler's inherited environment or private diagnostic logs.
"""
from pathlib import Path, PurePosixPath
import argparse
import copy
import gzip
import hashlib
import io
import json
import tarfile
import zipfile
import build_dolphin as build

ROOT = build.ROOT
CACHE = build.CACHE


def encoded(value):
    return (json.dumps(value, indent=2) + "\n").encode()


def export(out, result_path, public_directory):
    result = json.loads(result_path.read_text(encoding="utf-8"))
    lock = json.loads((out / "source-lock.json").read_text(encoding="utf-8"))
    if not result["complete"] or lock["sourceRevision"] != build.REVISION:
        raise ValueError("A successful exact fixed-source build is required")
    if result["sourceLockSha256"] != build.sha(out / "source-lock.json"):
        raise ValueError("Build source lock changed")
    library = Path(result["library"]["path"])
    if library.stat().st_size != result["library"]["bytes"] or build.sha(library) != result["library"]["sha256"]:
        raise ValueError("Actual built DLL changed")
    patch = out / "dolphin-sd-checkpoint-sharing.patch"
    if build.sha(patch) != lock["patchSha256"]:
        raise ValueError("Actual applied GPL patch changed")
    changed = {row["path"]: row for row in lock["patchedFiles"]}
    patch_rows=lock.get("patches",[{"path":patch.name,"sha256":lock["patchSha256"],"license":"GPL-2.0-or-later"}])
    for row in patch_rows:
        if Path(row["path"]).name!=row["path"] or row["license"]!="GPL-2.0-or-later" or build.sha(out/row["path"])!=row["sha256"]:raise ValueError("Actual GPL patch/license record differs")
    for row in changed.values():
        if build.sha(out / "source" / row["path"]) != row["afterSha256"]:
            raise ValueError("Actual patched preferred source changed")
    upstream = json.loads((CACHE / "third-party-source/expansion-dolphin-source.json").read_text(encoding="utf-8"))
    rows = []

    def sources(node, relative=""):
        rows.append((node, relative))
        for child in node["submodules"]:
            sources(child, (PurePosixPath(relative) / child["installPath"]).as_posix())
    sources(upstream)
    expected_lock = [(n["revision"], n["sha256"], d or ".") for n, d in rows]
    actual_lock = [(n["revision"], n["archiveSha256"], n["directory"]) for n in lock["dependencies"]]
    if expected_lock != actual_lock:
        raise ValueError("Recursive actual-build source closure differs")
    commands = json.loads((result_path.parent / "commands.json").read_text(encoding="utf-8"))
    if not public_directory.resolve().is_relative_to(CACHE.resolve()):raise ValueError("Public native exports stay in the owned shared cache")
    public_directory.mkdir(parents=True,exist_ok=False)
    binary_archive=public_directory/"dolphin_libretro.dll.zip"
    if binary_archive.exists():raise ValueError("Preserve the frozen native binary archive")
    entry=zipfile.ZipInfo("dolphin_libretro.dll",(1980,1,1,0,0,0));entry.compress_type=zipfile.ZIP_DEFLATED;entry.external_attr=0o644<<16
    with zipfile.ZipFile(binary_archive,"x",compression=zipfile.ZIP_DEFLATED,compresslevel=9) as package:
        package.writestr(entry,library.read_bytes(),compresslevel=9)
    public = {"schemaVersion": 1, "core": "dolphin", "repository": upstream["repository"],
        "sourceCommit": build.REVISION, "library": {"path":"dolphin_libretro.dll","name":"dolphin_libretro.dll",**{k:v for k,v in result["library"].items() if k != "path"}},
        "archive":{"path":binary_archive.name,"bytes":binary_archive.stat().st_size,"sha256":build.sha(binary_archive)},
        "upstreamArchive": {"sha256":upstream["sha256"],"bytes":upstream["bytes"],"url":upstream["url"]},
        "sourceLockSha256": result["sourceLockSha256"], "dependencies": lock["dependencies"],
        "patches": [{**row,"path":"patches/"+row["path"]} for row in patch_rows],
        "preferredSourceComplete": True, "nativeSerializationLayoutChanged": False,
        "stateCompatibilityValidated": False, "runtimeValidated": False,
        "bitIdenticalRebuildClaimed": False, "cpuParallelism": 1,
        "toolchain": commands["toolchain"], "driverSha256": result["driverSha256"],
        "compilerPortabilityOptions":commands.get("compilerPortabilityOptions",{}),
        "environment": "LLVM_BIN; MAKE_BIN; Git mingw64/bin and usr/bin; Python; Windows system directories. No inherited PATH is published."}
    instructions = """Corresponding source for this new Dolphin libretro DLL

The exact upstream revision and recursively fixed submodule revisions are in
source-lock.json. source/ contains every preferred upstream source file from
those archives. Optional Qt and FFmpeg-bin gitlinks are omitted only because
the exact LIBRETRO configuration force-disables their targets; the lock records
the parent gitlinks and reason. All upstream licenses and vendor notices remain.

The GPL-2.0-or-later SD patch changes two Windows file opens to allow readers
while still denying concurrent writers, and flushes the native SD handle before
writing a checkpoint. It changes no serialized fields. Runtime and old-snapshot
compatibility claims are separate from this exact source correspondence record.
An additional GPL portability patch keeps the same WSAGetLastError function
pointer but uses a const pointer instead of constexpr, because a Windows DLL
import address is not a Clang C++ constant expression. It changes no call or
serialized field.

Offline Windows x64 rebuild, using one compiler job:
1. Extract this source package. No Git checkout, fetch, or emulator BIOS is needed.
2. Obtain LLVM-MinGW 20250613 UCRT x86_64 (Clang 20.1.7), GNU Make 4.4.1,
   CMake 4.0.2, Python3, and Git for Windows (Make runtime directories).
   Actual compiler/tool binary SHA256 values are in BUILD-RECORD.json.
   LLVM-MinGW archive URL:
   https://github.com/mstorsjo/llvm-mingw/releases/download/20250613/llvm-mingw-20250613-ucrt-x86_64.zip
   archive SHA256: 45145c035d9246e1de16f1873aa9afa863d93909f4a8f363e2eb38a04031d3c3
3. Run: python -B tools/build_dolphin.py --source-tree . --output <new-build-dir>
   --llvm-root <LLVM-root> --make <mingw32-make.exe> --cmake <cmake.exe>
   --git-root <Git-root>
   The supplied tree already has the reviewed SD patch applied. Dependencies
   are local and FETCHCONTENT_FULLY_DISCONNECTED is explicit. The build target
   is dolphin_libretro and compilation is limited to one job.
   The build driver explicitly disables Git discovery outside the preferred
   source package and fixes the source revision string. Its Clang-only D3D11
   target flag accepts the existing MSVC-compatible UINT conversion without
   changing the upstream D3D source or other target compiler flags.
   Image.cpp explicitly retains the project's default -fno-exceptions so that
   libc++'s function-template ABI matches LoadPNG callers; watcher's exception
   option otherwise leaks onto Common and changes that public template type.

The complete upstream COPYING/LICENSES and per-file SPDX terms apply to Dolphin
and its dependencies; the SD patch is GPL-2.0-or-later. Our original Python build
and export drivers are MIT (LICENSE-FIRST-PARTY). No user firmware, keys, game
content, test worlds, binary compiler toolchains, or private machine logs ship.
"""
    package = public_directory / "dolphin-corresponding-public-source.tar.gz"
    temporary = package.with_suffix(".gz.part")
    if package.exists() or temporary.exists():
        raise ValueError("Choose a new export directory; do not overwrite frozen public source")
    verified = 0
    names = set()
    notices = set()

    def add_bytes(target, name, data, mode=0o644):
        if name in names:
            raise ValueError("Duplicate public source member: " + name)
        names.add(name)
        info = tarfile.TarInfo(name);info.size=len(data);info.mode=mode;info.mtime=0
        target.addfile(info, io.BytesIO(data))

    with temporary.open("xb") as raw, gzip.GzipFile(filename="",mode="wb",fileobj=raw,mtime=0) as compressed, tarfile.open(fileobj=compressed,mode="w|") as output:
        for node, relative in rows:
            archive = CACHE / "third-party-source" / node["archive"]
            if build.sha(archive) != node["sha256"]:
                raise ValueError("Source dependency archive changed")
            notices.update(node["licenseFiles"])
            with tarfile.open(archive) as original:
                for member in original:
                    parts = PurePosixPath(member.name).parts
                    if len(parts) < 2:
                        continue
                    subpath = PurePosixPath(relative, *parts[1:])
                    if subpath.is_absolute() or ".." in subpath.parts:
                        raise ValueError("Unsafe preferred source member")
                    # Child gitlink archives supply their own directory; no empty duplicate dirs.
                    if member.isdir():
                        continue
                    path = subpath.as_posix();actual = out / "source" / path
                    if member.isfile():
                        data = actual.read_bytes()
                        if path not in changed:
                            expected = hashlib.sha256(original.extractfile(member).read()).hexdigest()
                            if hashlib.sha256(data).hexdigest() != expected:
                                raise ValueError("Unexpected native source edit: " + path)
                        add_bytes(output,"source/"+path,data,member.mode)
                    elif member.issym():
                        name = "source/"+path
                        if name in names:
                            raise ValueError("Duplicate source symlink")
                        names.add(name);item=copy.copy(member);item.name=name;item.mtime=0;item.uid=item.gid=0;item.uname=item.gname="";output.addfile(item)
                    else:
                        raise ValueError("Unexpected preferred source member type")
                    verified += 1
        add_bytes(output,"source-lock.json",encoded(lock))
        add_bytes(output,"BUILD-RECORD.json",encoded(public))
        add_bytes(output,"BUILDING.md",instructions.encode())
        add_bytes(output,"LICENSE-FIRST-PARTY",(ROOT/"LICENSE").read_bytes())
        for row in patch_rows:add_bytes(output,"patches/"+row["path"],(out/row["path"]).read_bytes())
        add_bytes(output,"patches/README.md",(ROOT/"native/patches/README.md").read_bytes())
        driver=(result_path.parent/"build-driver-used.py").read_bytes()
        if hashlib.sha256(driver).hexdigest()!=result["driverSha256"]:raise ValueError("Actual successful build driver changed")
        add_bytes(output,"tools/build_dolphin.py",driver)
        add_bytes(output,"tools/export_dolphin_sources.py",Path(__file__).read_bytes())
        add_bytes(output,"BUILD-INFO/build-driver-used.py",(result_path.parent/"build-driver-used.py").read_bytes())
        add_bytes(output,"BUILD-INFO/preparation-driver-used.py",(out/"build-driver-used.py").read_bytes())
    temporary.replace(package)
    public["sourceArchive"]={"path":package.name,"bytes":package.stat().st_size,"sha256":build.sha(package),"format":"tar.gz","completePreferredSource":True}
    public["preferredSourceMembersVerifiedAgainstActualBuild"]=verified
    report=public_directory/"rebuild-public-report.json";report.write_bytes(encoded(public))
    record={"repository":upstream["repository"],"revision":build.REVISION,"archive":"rebuilt/"+package.name,
        "cachePath":package.relative_to(CACHE).as_posix(),"bytes":package.stat().st_size,"sha256":build.sha(package),
        "archiveLayout":"Complete assembled source/ with every fixed submodule, actual applied GPL patch, offline build driver and BUILDING.md",
        "patchesApplied":True,"licenseFiles":sorted(notices),"submodules":[],"rebuildReport":public,"rebuildReportSha256":build.sha(report),
        "preferredSourceMembersVerifiedAgainstActualBuild":verified}
    # Locator is relative to the source-delivery cache, matching its established contract.
    record["cachePath"]="../"+record["cachePath"]
    destination=CACHE/"third-party-source/expansion-dolphin-rebuilt-source.json"
    destination.write_bytes(encoded(record))
    print(json.dumps({"report":str(report),"sourceRecord":str(destination),"bytes":package.stat().st_size,"sha256":build.sha(package),"verifiedMembers":verified},indent=2))


if __name__ == "__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build",type=Path,required=True)
    parser.add_argument("--result",type=Path,required=True)
    parser.add_argument("--output",type=Path,help="New public export directory, default <build>/public-01")
    args=parser.parse_args()
    export(args.build.resolve(),args.result.resolve(),args.output.resolve() if args.output else args.build.resolve()/"public-01")
