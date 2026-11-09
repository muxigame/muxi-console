#!/usr/bin/env python3
# SPDX-License-Identifier: MIT
"""Build fixed Dolphin preferred sources plus the GPL SD checkpoint patch, one job.

This does not update a core pin and does not infer the source of a rolling DLL.
An exported source tree builds offline with the same explicit dependency lock.
"""
from pathlib import Path, PurePosixPath
import argparse
import copy
import difflib
import hashlib
import json
import os
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT.parent / "test-environment/cache/muxi-console"
REVISION = "32c61f9d0e933331bb7087ba57e96883241b4960"
SOURCE_SHA = "d5d5131a5da1dd2eae2950c894292a72546aa2cb87f645b89c30aabc8f679432"
PATCH = "native/patches/dolphin-sd-checkpoint-sharing.patch"
SD_FILE = "Source/Core/Core/IOS/SDIO/SDIOSlot0.cpp"
PORTABILITY_PATCH="native/patches/dolphin-mingw-constexpr.patch"
SOCKET_FILE="Source/Core/Core/IOS/Network/Socket.cpp"


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1048576), b""):
            digest.update(block)
    return digest.hexdigest()


def record(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def extract(archive, directory):
    directory.mkdir(parents=True, exist_ok=True)
    with tarfile.open(archive) as package:
        members = package.getmembers()
        prefix = PurePosixPath(members[0].name).parts[0]
        for member in members:
            name = PurePosixPath(member.name)
            if name.is_absolute() or ".." in name.parts or name.parts[0] != prefix:
                raise ValueError("Unsafe or inconsistent preferred source path")
            if len(name.parts) == 1:
                continue
            member.name = PurePosixPath(*name.parts[1:]).as_posix()
            package.extract(member, directory, filter="data")


def prepare(out, manifest, patch):
    lock = json.loads(manifest.read_text(encoding="utf-8"))
    if lock["revision"] != REVISION or lock["sha256"] != SOURCE_SHA:
        raise ValueError("Exact reviewed Dolphin dependency lock is required")
    out.mkdir(parents=True, exist_ok=False)
    sources = CACHE / "third-party-source"
    rows = []

    def install(node, relative):
        archive = sources / node["archive"]
        if archive.stat().st_size != node["bytes"] or sha(archive) != node["sha256"]:
            raise ValueError("Preferred source archive differs: " + node["archive"])
        destination = out / "source" / relative
        extract(archive, destination)
        rows.append({"directory": relative or ".", "repository": node["repository"],
            "revision": node["revision"], "archiveSha256": node["sha256"],
            "archiveBytes": node["bytes"], "url": node["url"],
            "excludedOptionalGitlinks": node.get("excludedOptionalGitlinks", [])})
        for child in node["submodules"]:
            child_path = PurePosixPath(relative) / child["installPath"]
            if child_path.is_absolute() or ".." in child_path.parts:
                raise ValueError("Unsafe recursive submodule location")
            install(child, child_path.as_posix())

    install(lock, "")
    target = out / "source" / SD_FILE
    before = target.read_text(encoding="utf-8")
    open_call = 'm_card.Open(filename, "r+b");'
    if before.count(open_call) != 2:
        raise ValueError("Expected exactly two upstream SD opens")
    after = before.replace(open_call, 'm_card.Open(filename, "r+b", File::SharedAccess::Read);')
    context = "void SDIOSlot0Device::DoState(PointerWrap& p)\n{\n"
    if after.count(context) != 1:
        raise ValueError("Expected exact upstream SD serialization context")
    after = after.replace(context, context + "  // Checkpoint readers copy the paused machine's complete SD image.\n"
        "  if (p.IsWriteMode() && m_card.IsOpen())\n    m_card.Flush();\n\n")
    difference = "".join(difflib.unified_diff(before.splitlines(True), after.splitlines(True),
        fromfile="a/" + SD_FILE, tofile="b/" + SD_FILE))
    # The checked-in patch must describe every semantic change to upstream code.
    if difference != patch.read_text(encoding="utf-8"):
        raise ValueError("Checked-in SD patch differs from exact generated source diff")
    target.write_text(after, encoding="utf-8", newline="\n")
    shutil.copyfile(patch, out / "dolphin-sd-checkpoint-sharing.patch")
    shutil.copyfile(Path(__file__), out / "build-driver-used.py")
    record(out / "source-lock.json", {"schemaVersion": 1, "sourceRevision": REVISION,
        "dependencies": rows, "patchSha256": sha(patch),
        "patchedFiles": [{"path": SD_FILE, "beforeSha256": hashlib.sha256(before.encode()).hexdigest(),
                          "afterSha256": sha(target)}],
        "nativeSerializationLayoutChanged": False,
        "stateCompatibilityValidated": False,
        "driverSha256": sha(Path(__file__))})


def windows_import_portability(out):
    target=out/"source"/SOCKET_FILE;before=target.read_text(encoding="utf-8")
    old="  constexpr int (*get_errno)() = &WSAGetLastError;"
    new="  int (*const get_errno)() = &WSAGetLastError;"
    lock_path=out/"source-lock.json";lock=json.loads(lock_path.read_text(encoding="utf-8"))
    if new in before:
        if not any(row["path"]==SOCKET_FILE for row in lock["patchedFiles"]):raise ValueError("Unrecorded Windows import portability edit")
        return
    if before.count(old)!=1:raise ValueError("Expected one exact Windows imported API pointer context")
    after=before.replace(old,new)
    difference="".join(difflib.unified_diff(before.splitlines(True),after.splitlines(True),fromfile="a/"+SOCKET_FILE,tofile="b/"+SOCKET_FILE))
    patch=ROOT/PORTABILITY_PATCH
    if difference!=patch.read_text(encoding="utf-8"):raise ValueError("Explicit Windows portability patch differs")
    history=out/"history-before-mingw-constexpr";history.mkdir()
    shutil.copyfile(lock_path,history/"source-lock.json")
    target.write_text(after,encoding="utf-8",newline="\n");shutil.copyfile(patch,out/patch.name)
    lock["patchedFiles"].append({"path":SOCKET_FILE,"beforeSha256":hashlib.sha256(before.encode()).hexdigest(),"afterSha256":sha(target)})
    lock["patches"]=[{"path":"dolphin-sd-checkpoint-sharing.patch","sha256":lock["patchSha256"],"license":"GPL-2.0-or-later"},{"path":patch.name,"sha256":sha(patch),"license":"GPL-2.0-or-later"}]
    record(lock_path,lock)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--source-tree", type=Path, help="Portable prepared tree; fully offline")
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--refresh-compiler-options",help="Explicit reason for preserving and replacing an earlier compiler-only override")
    parser.add_argument("--build-subdir", default="build", help="New relative build directory when preserving a failed configure")
    parser.add_argument("--llvm-root", type=Path)
    parser.add_argument("--make", type=Path)
    parser.add_argument("--cmake", type=Path, default=Path("C:/Program Files/CMake/bin/cmake.exe"))
    parser.add_argument("--git-root", type=Path, default=Path("C:/Program Files/Git"))
    args = parser.parse_args()
    out = args.output.resolve()
    if not args.build_subdir or any(c not in "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_" for c in args.build_subdir):
        raise ValueError("Build subdirectory must be a simple relative name")
    build_directory = out / args.build_subdir
    if args.source_tree:
        prepared = args.source_tree.resolve()
        if not (prepared / "source-lock.json").is_file():
            raise ValueError("Missing explicit offline preferred source lock")
        out.mkdir(parents=True, exist_ok=args.resume)
    else:
        prepared = out
        if not args.resume:
            prepare(out, CACHE / "third-party-source/expansion-dolphin-source.json", ROOT / PATCH)
        windows_import_portability(out)
    lock = json.loads((prepared / "source-lock.json").read_text(encoding="utf-8"))
    if lock["sourceRevision"] != REVISION:
        raise ValueError("Prepared source revision differs")
    for changed in lock["patchedFiles"]:
        if sha(prepared / "source" / changed["path"]) != changed["afterSha256"]:
            raise ValueError("Reviewed SD source changed")
    if args.prepare_only:
        print(str(prepared / "source-lock.json"))
        return
    toolchains = ROOT.parent / "test-environment/toolchains"
    llvm = args.llvm_root or next((toolchains / "muxi-console-rebuild-llvm-mingw-20250613/install").glob("llvm-*"))
    compiler = llvm / "bin/x86_64-w64-mingw32-clang.exe"
    make = args.make or next((toolchains / "muxi-console-rebuild-gnu-make-4.4.1").rglob("mingw32-make.exe"))
    tools = [compiler, compiler.with_name("x86_64-w64-mingw32-clang++.exe"),
        llvm / "bin/x86_64-w64-mingw32-windres.exe", make, args.cmake]
    if any(not tool.is_file() for tool in tools):
        raise ValueError("Explicit LLVM-MinGW/CMake/Make tools are missing")
    attempt = 1
    while (out / ("attempt-%02d" % attempt)).exists():
        attempt += 1
    logs = out / ("attempt-%02d" % attempt)
    logs.mkdir()
    environment = dict(os.environ)
    environment["PATH"] = os.pathsep.join([str(llvm / "bin"), str(make.parent),
        str(args.git_root / "mingw64/bin"), str(args.git_root / "usr/bin"), environment.get("PATH", "")])
    environment["CMAKE_BUILD_PARALLEL_LEVEL"] = "1"
    # Match the accepted Windows/MSVC conversion in the unused D3D11 backend;
    # keep the upstream file untouched and avoid changing every C++ target.
    compiler_options=out/"clang-coff-options.cmake"
    compiler_text='''if(CMAKE_CURRENT_SOURCE_DIR STREQUAL CMAKE_SOURCE_DIR AND CMAKE_CXX_COMPILER_ID STREQUAL "Clang")
  cmake_language(DEFER CALL target_compile_options videod3d PRIVATE -Wno-c++11-narrowing)
  cmake_language(DEFER CALL set_property SOURCE "${CMAKE_SOURCE_DIR}/Source/Core/Common/Image.cpp" DIRECTORY "${CMAKE_SOURCE_DIR}/Source/Core/Common" APPEND PROPERTY COMPILE_OPTIONS -fno-exceptions)
endif()
'''
    if compiler_options.exists() and compiler_options.read_text()!=compiler_text:
        if not args.refresh_compiler_options:raise ValueError("Explicit compiler portability options changed; retain the old configuration with a stated reason")
        history=out/("compiler-options-history-%02d"%attempt);history.mkdir();shutil.copyfile(compiler_options,history/compiler_options.name)
        record(history/"change.json",{"reason":args.refresh_compiler_options,"beforeSha256":sha(compiler_options),"afterSha256":hashlib.sha256(compiler_text.encode()).hexdigest()})
    compiler_options.write_text(compiler_text,encoding="utf-8",newline="\n")
    options = [str(args.cmake), "-S", str(prepared / "source"), "-B", str(build_directory),
        "-G", "MinGW Makefiles", "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_SYSTEM_NAME=Windows",
        "-DCMAKE_SYSTEM_PROCESSOR=x86_64", "-DCMAKE_C_COMPILER=" + tools[0].as_posix(),
        "-DCMAKE_CXX_COMPILER=" + tools[1].as_posix(), "-DCMAKE_RC_COMPILER=" + tools[2].as_posix(),
        "-DCMAKE_MAKE_PROGRAM=" + make.as_posix(), "-DLIBRETRO=ON", "-DUSE_SYSTEM_LIBS=OFF",
        "-DENABLE_LTO=OFF", "-DENABLE_VULKAN=OFF", "-DDISTRIBUTOR=MuxiConsole",
        "-DCMAKE_DISABLE_FIND_PACKAGE_Git=TRUE", "-DDOLPHIN_WC_REVISION="+REVISION,
        "-DDOLPHIN_WC_DESCRIBE=MuxiConsole-SD-checkpoint-32c61f9d", "-DDOLPHIN_WC_BRANCH=source-rebuild",
        "-DCMAKE_PROJECT_INCLUDE="+compiler_options.as_posix(),
        "-DFETCHCONTENT_FULLY_DISCONNECTED=ON", "-DCMAKE_POLICY_VERSION_MINIMUM=3.5"]
    command = [str(args.cmake), "--build", str(build_directory), "--target", "dolphin_libretro", "--parallel", "1"]
    shutil.copyfile(Path(__file__), logs / "build-driver-used.py")
    record(logs / "commands.json", {"configure": options, "compile": command, "cpuParallelism": 1,"driverSha256":sha(Path(__file__)),
        "compilerPortabilityOptions":{"file":compiler_options.name,"sha256":sha(compiler_options),"scope":"Clang D3D11: -Wno-c++11-narrowing; Common/Image.cpp: restore project default -fno-exceptions to match UniqueBuffer function-template ABI across callers; no additional upstream source edit"},
        "toolchain": {tool.name: {"bytes": tool.stat().st_size, "sha256": sha(tool)} for tool in tools},
        "environment": "LLVM_BIN; MAKE_BIN; Git mingw64/bin and usr/bin; inherited caller environment (not recorded)"})
    for name, invocation in [("configure", options), ("compile", command)]:
        with (logs / (name + ".log")).open("w", encoding="utf-8") as log:
            result = subprocess.run(invocation, env=environment, stdout=log, stderr=subprocess.STDOUT)
        if result.returncode:
            raise RuntimeError(name + " failed; see retained attempt log")
    libraries = list(build_directory.rglob("dolphin_libretro.dll"))
    if len(libraries) != 1:
        raise ValueError("Expected exactly one linked Dolphin DLL")
    library = libraries[0]
    record(logs / "result.json", {"complete": True, "sourceRevision": REVISION,
        "library": {"path": str(library), "bytes": library.stat().st_size, "sha256": sha(library)},
        "sourceLockSha256": sha(prepared / "source-lock.json"), "driverSha256": sha(Path(__file__)),
        "runtimeValidated": False, "bitIdenticalRebuildClaimed": False})
    print(str(logs / "result.json"))


if __name__ == "__main__":
    main()
