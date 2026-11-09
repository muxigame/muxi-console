"""Export the exact complete source and build recipe for one private 3DS Room DLL.

Output deliberately excludes local logs, inherited environment and build caches.
No public core pin is changed by this tool; runtime and UDS evidence is separate.
"""
from pathlib import Path
import argparse
import copy
import gzip
import hashlib
import json
import shutil
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT.parent / "test-environment/cache/muxi-console/rebuild-azahar-link"


def sha(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for piece in iter(lambda: stream.read(1048576), b""):
            value.update(piece)
    return value.hexdigest()


def write(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--diagnostic", type=Path, help="Optional successful exact-DLL original UDS/cold proof")
    args = parser.parse_args()
    build, out = args.build.resolve(), args.output.resolve()
    if not build.is_relative_to(CACHE.resolve()) or not out.is_relative_to(build) or out.exists():
        raise ValueError("Choose a new source export inside the owned 3DS rebuild")
    reports = sorted(build.glob("attempt-*/result.json"))
    if not reports:
        raise ValueError("A successful actual compile report is required")
    report = json.loads(reports[-1].read_text(encoding="utf-8"))
    preparation = json.loads((build / "preparation.json").read_text(encoding="utf-8"))
    if report.get("complete") is not True:
        raise ValueError("The build is incomplete")
    actual = Path(report["library"]["path"]).resolve()
    if not actual.is_relative_to(build) or sha(actual) != report["library"]["sha256"]:
        raise ValueError("Built DLL identity differs")
    adapter = build / "source/src/citra_libretro/muxi_azahar_link_adapter.cpp"
    radio = preparation.get("radioState")
    original_adapter_sha = preparation.get("sourceAdapterSha256", sha(adapter))
    if sha(adapter) != report["adapterSha256"] or original_adapter_sha != sha(ROOT / "native/link/azahar_link_adapter.cpp"):
        raise ValueError("The successfully compiled adapter differs")
    if radio and (radio["profileStateAbi"] != 2 or
                  sha(build / "source/src/core/hle/service/nwm/muxi_nwm_state.inc") != radio["serializerSha256"] or
                  sha(build / "azahar-radio-state.patch") != radio["patchSha256"]):
        raise ValueError("The separately versioned complete-radio source differs")
    out.mkdir()
    package = out / "package"
    package.mkdir()
    # The source is complete, including all vendored library notices. Only VCS metadata is irrelevant.
    shutil.copytree(build / "source", package / "source", ignore=shutil.ignore_patterns(".git"))
    (package / "tools").mkdir()
    shutil.copyfile(ROOT / "tools/build_3ds_link_adapter.py", package / "tools/build_3ds_link_adapter.py")
    shutil.copyfile(ROOT / "tools/export_3ds_link_sources.py", package / "tools/export_3ds_link_sources.py")
    shutil.copyfile(ROOT / "LICENSE", package / "ADAPTER-LICENSE")
    (package / "patches").mkdir()
    shutil.copyfile(build / "azahar-room-and-paths.patch", package / "patches/azahar-room-and-paths.patch")
    portability = build / "portability-fstat/fix.patch"
    if portability.is_file():
        shutil.copyfile(portability, package / "patches/llvm-mingw-fstat.patch")
    link_portability = build / "portability-mingw-link/fix.patch"
    if link_portability.is_file():
        shutil.copyfile(link_portability, package / "patches/llvm-mingw-link.patch")
    if radio:
        shutil.copyfile(build / "azahar-radio-state.patch", package / "patches/azahar-radio-state.patch")
    commands = json.loads((reports[-1].parent / "commands.json").read_text(encoding="utf-8"))
    baseline = json.loads((ROOT.parent / "test-environment/cache/muxi-console/rebuild-mgba-link/build-08/build-report.json").read_text(encoding="utf-8"))
    toolchain = copy.deepcopy(baseline["toolchain"])
    for name, executable in [("llvmMinGW", "x86_64-w64-mingw32-clang.exe"), ("gnuMake", "mingw32-make.exe"), ("cmake", "cmake.exe")]:
        if commands["tools"][executable]["sha256"] != toolchain[name]["executableSha256"]:
            raise ValueError("Actual native tool differs from the portable toolchain record: " + name)
    library = out / "muxi_azahar_link.dll"
    shutil.copyfile(actual, library)
    binary_archive = out / "muxi_azahar_link.dll.zip"
    with zipfile.ZipFile(binary_archive, "x", zipfile.ZIP_DEFLATED) as archive:
        info = zipfile.ZipInfo(library.name, (2026, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        with library.open("rb") as source, archive.open(info, "w") as target:
            shutil.copyfileobj(source, target, 1048576)
    public = {
        "schemaVersion": 1, "core": "azahar-link", "sourceCommit": preparation["sourceRevision"],
        "sourceOrigin": "https://github.com/azahar-emu/azahar",
        "upstreamArchive": dict(preparation["sourceArchive"], url="https://github.com/azahar-emu/azahar/releases/download/2126.2/azahar-unified-source-2126.2.tar.xz"),
        "license": "GPL-2.0-or-later for linked frontend and upstream changes; original C ABI adapter MIT; vendor licenses retained",
        "library": {"path": library.name, "bytes": library.stat().st_size, "sha256": sha(library)},
        "archive": {"path": binary_archive.name, "bytes": binary_archive.stat().st_size, "sha256": sha(binary_archive)},
        "adapter": {"path": "source/src/citra_libretro/muxi_azahar_link_adapter.cpp", "sha256": sha(adapter), "license": "MIT"},
        "patches": [{"path": "patches/azahar-room-and-paths.patch", "sha256": sha(package / "patches/azahar-room-and-paths.patch"), "license": "GPL-2.0-or-later"}],
        "buildScript": {"path": "tools/build_3ds_link_adapter.py", "sha256": sha(package / "tools/build_3ds_link_adapter.py")},
        "exportScript": {"path": "tools/export_3ds_link_sources.py", "sha256": sha(package / "tools/export_3ds_link_sources.py")},
        "toolchainExecutables": commands["tools"],
        "toolchain": toolchain,
        "build": {"parallelJobs": 1, "configuration": "Release; ENABLE_LIBRETRO=ON; ENABLE_OPENGL=ON; ENABLE_VULKAN=OFF; ENABLE_LTO=OFF; all dependencies bundled; no network fetch", "bitIdenticalRebuildClaimed": False},
        "nativeSerializationChanged": True, "ordinaryCoreCompatibilityClaimed": False,
        "nativeProfileStateAbi": 2 if radio else 1, "roomQueuesCheckpointed": False,
        "runtimeValidated": False, "udsGameExchangeValidated": False, "releaseReady": False,
    }
    if portability.is_file():
        public["patches"].append({"path": "patches/llvm-mingw-fstat.patch", "sha256": sha(portability), "license": "GPL-2.0-or-later"})
    if link_portability.is_file():
        public["patches"].append({"path": "patches/llvm-mingw-link.patch", "sha256": sha(link_portability), "license": "GPL-2.0-or-later"})
    if radio:
        public["patches"].append({"path": "patches/azahar-radio-state.patch", "sha256": radio["patchSha256"], "license": "GPL-2.0-or-later"})
        public["radioSerializer"] = {"path": "source/src/core/hle/service/nwm/muxi_nwm_state.inc",
                                     "sha256": radio["serializerSha256"], "license": "GPL-2.0-or-later"}
    if args.diagnostic:
        proof = json.loads(args.diagnostic.read_text(encoding="utf-8"))
        if proof.get("complete") is not True or proof.get("coreSha256") != sha(library) or not proof.get("checks") or any(item.get("pass") is not True for item in proof["checks"]):
            raise ValueError("Native diagnostic is incomplete or bound to another DLL")
        if len(proof.get("workers", [])) < 3 or any(item.get("complete") is not True or item.get("signaled") is not True or item.get("forced") is not False for item in proof["workers"]):
            raise ValueError("Native proof lacks actual natural Windows process closure")
        if len(proof.get("udsEvidence", [])) != 2 or any(item.get("stage") != 3 or item.get("received", 0) < 1 for item in proof["udsEvidence"]):
            raise ValueError("Native proof lacks actual two-core guest UDS exchange")
        public["runtimeValidated"] = public["udsGameExchangeValidated"] = True
        public["diagnosticEvidence"] = {"sha256": sha(args.diagnostic), "checks": len(proof["checks"]), "workers": len(proof["workers"]), "originalHomebrewOnly": True, "minecraftTested": False}
        if radio and proof.get("radioRestore"):
            resumed = proof["radioRestore"]
            public["udsRadioContextRestoreValidated"] = (resumed.get("queuesRestored") is False and
                resumed.get("freshChallengeXor") == 0x6d39a7c2 and
                len(resumed.get("after", [])) == 2 and
                all(item["received"] > resumed["before"][i]["received"] and
                    item["sent"] > resumed["before"][i]["sent"] and
                    item["peerChallenge"] == ([0x319ac76d, 0xb6502fe4][1-i] ^ 0x6d39a7c2)
                    for i, item in enumerate(resumed["after"])))
    write(package / "BUILD-RECORD.json", public)
    (package / "BUILDING.md").write_text('''# Private 3DS Room corresponding source

This delivery contains the complete official Azahar 2126.2 unified source and all bundled dependencies. The explicitly included GPL-2.0-or-later patches initialize networking before NWM construction, count actual frontend WiFi packets, serialize native host paths using upstream FileUtil::Path placeholders, use the matching Windows _fstat64 API with LLVM-MinGW UCRT, and restrict the ELF -Bsymbolic option to non-Windows targets. Guest paths are unchanged. The separate original C ABI adapter is MIT. All upstream and vendor licenses remain in the source tree.

The source is already patched. Build independently and offline with Python 3, the pinned LLVM-MinGW 20250613, GNU Make 4.4.1, CMake and Git for Windows. Executable hashes are in BUILD-RECORD.json; no inherited private PATH is recorded. Supply explicit installed paths:

```text
python -B tools/build_3ds_link_adapter.py --source-tree <absolute-package/source> --output <new-build-folder> --llvm-root <LLVM-install> --make <mingw32-make.exe> --cmake <cmake.exe> --git-root <Git-install>
```

The only compiler job produces build/bin/Release/azahar_libretro.dll, packaged without byte changes under muxi_azahar_link.dll. PE timestamps and build roots may differ; bit-identical recompilation is not claimed. The original source preparation branch is workspace-specific and is unnecessary for this complete already-patched delivery.

The C ABI has init, loopback-only password-protected two-member host, join, fixed-size status, leave and shutdown. Initialization runs before the first Core::System access. State and WiFi counters describe native Room transport, not proof a particular game has joined a UDS network. Real homebrew UDS exchange and cold restore require separate verification. The native path serialization changes require this DLL's own fingerprint; old ordinary Azahar state compatibility is not claimed. No commercial ROM, manufacturer BIOS, runtime world, credential or private log is included.
''', encoding="utf-8")
    if radio:
        with (package / "BUILDING.md").open("a", encoding="utf-8") as notice:
            notice.write("\nThis separate ABI2 also pauses RoomMember callback delivery around complete Core/Kernel serialization and saves bounded NWM radio state, kernel event references, channel bindings and already-delivered guest packet queues. Runtime mutexes/callbacks are reconstructed; ENet transport and in-flight packets are not checkpointed. ABI1/e83 native snapshots are a different format and must never be blindly loaded into ABI2. The native profile capability reports 2, and the exact DLL fingerprint is mandatory. A fresh frontend Room is distinct from a restored transport queue; only independently recorded application-packet tests can establish that new game traffic resumes.\n")
    source_archive = out / "corresponding-source.tar.gz"
    with source_archive.open("xb") as raw, gzip.GzipFile(filename="", mode="wb", fileobj=raw, mtime=0) as gz, tarfile.open(fileobj=gz, mode="w") as archive:
        for path in sorted(package.rglob("*")):
            if path.is_symlink():
                raise ValueError("Unexpected symlink in portable source package")
            if not path.is_file():
                continue
            info = archive.gettarinfo(str(path), arcname="azahar-link-corresponding-source/" + path.relative_to(package).as_posix())
            info.uid = info.gid = 0
            info.uname = info.gname = ""
            info.mtime = 0
            with path.open("rb") as stream:
                archive.addfile(info, stream)
    public["sourceArchive"] = {"path": source_archive.name, "bytes": source_archive.stat().st_size,
        "sha256": sha(source_archive), "format": "tar.gz", "patchesApplied": True}
    write(out / "rebuild-public-report.json", public)
    print(json.dumps({name: public[name] for name in ["library", "archive", "sourceArchive"]}, indent=2))


if __name__ == "__main__":
    main()
