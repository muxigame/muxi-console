"""Build the original private-room adapter against exact, complete Azahar source.

No ordinary core pin or extracted upstream tree is modified. One offline compiler job.
The source tree also receives explicit native-path serialization fixes; these change
the native serialization ABI and require an independently identified core fingerprint.
"""
from pathlib import Path, PurePosixPath
import argparse
import difflib
import hashlib
import json
import os
import shutil
import subprocess
import tarfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT.parent / "test-environment/cache/muxi-console"
SOURCE_SHA = "85c8b56fac703df04720125044b8ff887997911858a6cde545dfb1107ba4cd26"
REVISION = "219d743a1e698101fe1ab8bef43e6563126c3c27"


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1048576), b""):
            digest.update(block)
    return digest.hexdigest()


def record(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")


def portability(out, source):
    """Use the matching Windows _stat64 descriptor API with LLVM-MinGW UCRT."""
    path = source / "src/common/file_util.cpp"
    old = path.read_text(encoding="utf-8")
    before = "    if (fstat(fd, &buf) != 0) {"
    after = "#ifdef _WIN32\n    if (_fstat64(fd, &buf) != 0) {\n#else\n" + before + "\n#endif"
    if after in old:
        return
    if old.count(before) != 1:
        raise ValueError("Expected one exact upstream fstat context")
    new = old.replace(before, after)
    directory = out / "portability-fstat"
    directory.mkdir()
    (directory / "previous-file_util.cpp").write_text(old, encoding="utf-8", newline="\n")
    path.write_text(new, encoding="utf-8", newline="\n")
    (directory / "fix.patch").write_text("".join(difflib.unified_diff(old.splitlines(True), new.splitlines(True),
        fromfile="a/src/common/file_util.cpp", tofile="b/src/common/file_util.cpp")), encoding="utf-8")
    record(directory / "record.json", {"reason": "LLVM-MinGW UCRT fstat expects stat*, while the upstream Windows file_stat_t is _stat64; use the matching _fstat64 API, retaining 64-bit file size.",
        "beforeSha256": hashlib.sha256(old.encode()).hexdigest(), "afterSha256": sha(path),
        "patchSha256": sha(directory / "fix.patch"), "driverSha256": sha(Path(__file__))})


def link_portability(out, source):
    path = source / "src/citra_libretro/CMakeLists.txt"
    old = path.read_text(encoding="utf-8")
    before = 'else()\n  target_link_libraries(citra_libretro PRIVATE "-Wl,-Bsymbolic")\nendif()'
    after = before.replace("else()", "elseif(NOT WIN32)", 1)
    if after in old:
        return
    if old.count(before) != 1:
        raise ValueError("Expected one exact upstream ELF linker option context")
    new = old.replace(before, after)
    directory = out / "portability-mingw-link"
    directory.mkdir()
    (directory / "previous-CMakeLists.txt").write_text(old, encoding="utf-8", newline="\n")
    path.write_text(new, encoding="utf-8", newline="\n")
    (directory / "fix.patch").write_text("".join(difflib.unified_diff(old.splitlines(True), new.splitlines(True),
        fromfile="a/src/citra_libretro/CMakeLists.txt", tofile="b/src/citra_libretro/CMakeLists.txt")), encoding="utf-8")
    record(directory / "record.json", {"reason": "-Bsymbolic is an ELF linker option unsupported by Windows COFF lld; retain the existing ELF behavior and omit only on WIN32.",
        "beforeSha256": hashlib.sha256(old.encode()).hexdigest(), "afterSha256": sha(path),
        "patchSha256": sha(directory / "fix.patch"), "driverSha256": sha(Path(__file__))})


def radio_state(out, source):
    """Separate native ABI: save complete bounded guest NWM state, not ENet queues."""
    patches = []

    def edit(relative, before, after):
        path = source / relative
        old = path.read_text(encoding="utf-8")
        if old.count(before) != 1:
            raise ValueError("Expected exact radio-state patch context: " + relative)
        new = old.replace(before, after)
        path.write_text(new, encoding="utf-8", newline="\n")
        patches.extend(difflib.unified_diff(old.splitlines(True), new.splitlines(True),
            fromfile="a/" + relative, tofile="b/" + relative))

    # LoadStateBuffer destroys/reconstructs NWM and rebinds its callback on the
    # calling frontend thread. Recursive ownership permits those operations while
    # excluding the independent ENet delivery thread throughout all Kernel IO.
    edit("src/network/room_member.h", "#include <memory>\n", "#include <memory>\n#include <mutex>\n")
    edit("src/network/room_member.h", "    RoomMember();\n",
         "    // Frontend owns this only outside emulation, before Core serialization.\n"
         "    std::unique_lock<std::recursive_mutex> LockCallbacksForSnapshot();\n\n    RoomMember();\n")
    edit("src/network/room_member.cpp", "    std::mutex callback_mutex;",
         "    std::recursive_mutex callback_mutex;")
    edit("src/network/room_member.cpp", "// RoomMember\n",
         "// RoomMember\nstd::unique_lock<std::recursive_mutex> RoomMember::LockCallbacksForSnapshot() {\n"
         "    return std::unique_lock<std::recursive_mutex>(room_member_impl->callback_mutex);\n}\n\n")
    edit("src/citra_libretro/citra_libretro.cpp", '#include "core/memory.h"\n',
         '#include "core/memory.h"\n#include "network/network.h"\n')
    edit("src/citra_libretro/citra_libretro.cpp", "        savestate = system.SaveStateBuffer();",
         "        auto member = Network::GetRoomMember().lock();\n"
         "        auto callbacks = member ? member->LockCallbacksForSnapshot()\n"
         "                                : std::unique_lock<std::recursive_mutex>{};\n"
         "        savestate = system.SaveStateBuffer();")
    edit("src/citra_libretro/citra_libretro.cpp", "        return system.LoadStateBuffer(std::move(buffer));",
         "        auto member = Network::GetRoomMember().lock();\n"
         "        auto callbacks = member ? member->LockCallbacksForSnapshot()\n"
         "                                : std::unique_lock<std::recursive_mutex>{};\n"
         "        return system.LoadStateBuffer(std::move(buffer));")
    edit("src/core/hle/service/nwm/nwm_uds.cpp", "#include <cstring>\n",
         "#include <cstring>\n#include <stdexcept>\n#include <type_traits>\n"
         "#include <boost/serialization/array_wrapper.hpp>\n")
    edit("src/core/hle/service/nwm/nwm_uds.cpp",
         "    DEBUG_SERIALIZATION_POINT;\n"
         "    ar& boost::serialization::base_object<Kernel::SessionRequestHandler>(*this);\n"
         "    ar & node_map;\n    ar & connection_event;\n    ar & received_beacons;\n"
         "    // wifi_packet_received set in constructor\n",
         '#include "core/hle/service/nwm/muxi_nwm_state.inc"\n')
    edit("src/core/hle/service/nwm/nwm_uds.h", "    NodeInfo current_node;", "    NodeInfo current_node{};")
    edit("src/core/hle/service/nwm/nwm_uds.h", "    ConnectionType conn_type;",
         "    ConnectionType conn_type = ConnectionType::Client;")
    edit("src/core/hle/service/nwm/nwm_uds.h", "    NetworkInfo network_info;", "    NetworkInfo network_info{};")
    edit("src/citra_libretro/muxi_azahar_link_adapter.cpp",
         "muxi_azahar_profile_state_abi() noexcept { return 1; }",
         "muxi_azahar_profile_state_abi() noexcept { return 2; }")
    state_source = ROOT / "native/link/azahar_nwm_state.inc"
    shutil.copyfile(state_source, source / "src/core/hle/service/nwm/muxi_nwm_state.inc")
    (out / "azahar-radio-state.patch").write_text("".join(patches), encoding="utf-8", newline="\n")
    return {"profileStateAbi": 2, "roomQueueCheckpoint": False,
            "patchSha256": sha(out / "azahar-radio-state.patch"),
            "serializerSha256": sha(state_source)}


def prepare(out, archive, adapter, radio=False):
    if archive.stat().st_size != 43224348 or sha(archive) != SOURCE_SHA:
        raise ValueError("The exact complete official source archive is required")
    out.mkdir(parents=True, exist_ok=False)
    source = out / "source"
    source.mkdir()
    with tarfile.open(archive, "r:xz") as package:
        for entry in package:
            name = PurePosixPath(entry.name)
            if name.is_absolute() or ".." in name.parts or name.parts[0] != "azahar-unified-source-2126.2":
                raise ValueError("Unsafe or unexpected upstream archive member")
            if len(name.parts) == 1:
                continue
            entry.name = PurePosixPath(*name.parts[1:]).as_posix()
            package.extract(entry, source, filter="data")
    patches = []

    def edit(relative, before, after):
        path = source / relative
        old = path.read_text(encoding="utf-8")
        if old.count(before) != 1:
            raise ValueError("Expected one exact source patch context: " + relative)
        new = old.replace(before, after)
        path.write_text(new, encoding="utf-8", newline="\n")
        patches.extend(difflib.unified_diff(old.splitlines(True), new.splitlines(True),
                                          fromfile="a/" + relative, tofile="b/" + relative))

    edit("src/citra_libretro/CMakeLists.txt", "        citra_libretro.cpp\n",
         "        citra_libretro.cpp\n        muxi_azahar_link_adapter.cpp\n")
    edit("src/citra_libretro/citra_libretro.cpp", "void retro_init() {",
         'extern "C" int muxi_azahar_link_init() noexcept;\n'
         'extern "C" void muxi_azahar_link_shutdown() noexcept;\n\nvoid retro_init() {')
    edit("src/citra_libretro/citra_libretro.cpp", '    // Set up LLE cores\n',
         '    // Initialize RoomMember before Core::System can construct NWM_UDS.\n'
         '    if (!muxi_azahar_link_init()) {\n'
         '        LOG_ERROR(Frontend, "Private-room network initialization failed");\n    }\n\n'
         '    // Set up LLE cores\n')
    edit("src/citra_libretro/citra_libretro.cpp", "    LibRetro::Input::Shutdown();\n",
         "    LibRetro::Input::Shutdown();\n    muxi_azahar_link_shutdown();\n")
    edit("src/network/room_member.cpp", "namespace Network {\n",
         'extern "C" void muxi_azahar_link_wifi_sent(std::uint32_t, std::size_t) noexcept;\n\n'
         "namespace Network {\n")
    edit("src/network/room_member.cpp", "void RoomMember::SendWifiPacket(const WifiPacket& wifi_packet) {\n",
         "void RoomMember::SendWifiPacket(const WifiPacket& wifi_packet) {\n"
         "    muxi_azahar_link_wifi_sent(static_cast<std::uint32_t>(wifi_packet.type), wifi_packet.data.size());\n")
    fields = {
        "archive_sdmc.h": ["mount_point", "sdmc_directory"],
        "archive_sdmcwriteonly.h": ["sdmc_directory"],
        "archive_nand.h": ["mount_point", "nand_directory"],
        "archive_extsavedata.h": ["mount_point"],
        "archive_source_sd_savedata.h": ["mount_point"],
        "archive_systemsavedata.h": ["base_path"],
        "savedata_archive.h": ["mount_point"],
        "layered_fs.h": ["patch_path", "patch_ext_path"],
    }
    for filename, members in fields.items():
        relative = "src/core/file_sys/" + filename
        text = (source / relative).read_text(encoding="utf-8")
        if '#include "common/file_util.h"' not in text:
            edit(relative, "#pragma once\n", '#pragma once\n\n#include "common/file_util.h"\n')
        for member in members:
            edit(relative, "ar & " + member + ";", "ar & FileUtil::Path::make(" + member + ");")
    shutil.copyfile(adapter, source / "src/citra_libretro/muxi_azahar_link_adapter.cpp")
    radio_metadata = radio_state(out, source) if radio else None
    (out / "azahar-room-and-paths.patch").write_text("".join(patches), encoding="utf-8", newline="\n")
    shutil.copyfile(Path(__file__), out / "build-driver-used.py")
    record(out / "preparation.json", {
        "schemaVersion": 1, "sourceRevision": REVISION,
        "sourceArchive": {"sha256": SOURCE_SHA, "bytes": archive.stat().st_size},
        "adapterSha256": sha(source / "src/citra_libretro/muxi_azahar_link_adapter.cpp"),
        "sourceAdapterSha256": sha(adapter), "driverSha256": sha(Path(__file__)),
        "patchSha256": sha(out / "azahar-room-and-paths.patch"),
        "nativeSerializationChanged": True, "ordinaryCoreUnchanged": True,
        "radioState": radio_metadata,
    })


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--source-archive", type=Path)
    parser.add_argument("--source-tree", type=Path, help="Already patched portable delivery tree; no preparation")
    parser.add_argument("--llvm-root", type=Path)
    parser.add_argument("--make", type=Path)
    parser.add_argument("--cmake", type=Path, default=Path("C:/Program Files/CMake/bin/cmake.exe"))
    parser.add_argument("--git-root", type=Path, default=Path("C:/Program Files/Git"))
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--refresh-adapter", help="Explicit reason for a separately recorded adapter-only retry")
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--radio-state", action="store_true", help="New ABI2 bounded NWM serialization; never mutates ABI1 builds")
    args = parser.parse_args()
    out = args.output.resolve()
    if not args.source_tree and not out.is_relative_to((CACHE / "rebuild-azahar-link").resolve()):
        raise ValueError("Workspace rebuild must remain in its owned cache")
    tools = ROOT.parent / "test-environment/toolchains"
    llvm = args.llvm_root or next((tools / "muxi-console-rebuild-llvm-mingw-20250613").glob("install/*"))
    compiler = llvm / "bin/x86_64-w64-mingw32-clang.exe"
    cxx = llvm / "bin/x86_64-w64-mingw32-clang++.exe"
    make = args.make or next((tools / "muxi-console-rebuild-gnu-make-4.4.1").rglob("mingw32-make.exe"))
    adapter = ROOT / "native/link/azahar_link_adapter.cpp"
    if args.source_tree:
        source = args.source_tree.resolve()
        out.mkdir(parents=True, exist_ok=args.resume)
    else:
        source = out / "source"
        if not args.resume:
            prepare(out, args.source_archive or CACHE / "expansion/azahar/official-source.tar.xz", adapter, args.radio_state)
        saved = json.loads((out / "preparation.json").read_text(encoding="utf-8"))
        if bool(saved.get("radioState")) != args.radio_state:
            raise ValueError("Radio-state ABI selection differs from this prepared build")
        updates = sorted(out.glob("adapter-update-*/record.json"))
        expected = json.loads(updates[-1].read_text(encoding="utf-8"))["adapterSha256"] if updates else saved["adapterSha256"]
        if args.refresh_adapter:
            if saved.get("radioState"):
                raise ValueError("ABI2 adapter changes require a fresh prepared build")
            if not args.resume:
                raise ValueError("Adapter refresh requires an explicitly resumed, stopped build")
            update = out / ("adapter-update-%02d" % (len(updates) + 1))
            update.mkdir()
            installed = source / "src/citra_libretro/muxi_azahar_link_adapter.cpp"
            if sha(installed) != expected:
                raise ValueError("Previously prepared adapter identity changed")
            shutil.copyfile(installed, update / "previous-adapter.cpp")
            shutil.copyfile(adapter, installed)
            shutil.copyfile(Path(__file__), update / "driver-used.py")
            record(update / "record.json", {"reason": args.refresh_adapter,
                "previousAdapterSha256": expected, "adapterSha256": sha(adapter),
                "driverSha256": sha(Path(__file__))})
            expected = sha(adapter)
        original_expected = saved.get("sourceAdapterSha256", expected) if saved.get("radioState") else expected
        if sha(adapter) != original_expected or sha(source / "src/citra_libretro/muxi_azahar_link_adapter.cpp") != expected:
            raise ValueError("Prepared adapter changed; use a new build name")
    if args.prepare_only:
        print(str(out / "preparation.json"))
        return
    portability(out, source)
    link_portability(out, source)
    for tool in [compiler, cxx, make, args.cmake, llvm / "bin/clang-format.exe"]:
        if not tool.is_file():
            raise ValueError("Missing explicit native build tool: " + str(tool))
    attempt = 1
    while (out / ("attempt-%02d" % attempt)).exists():
        attempt += 1
    logs = out / ("attempt-%02d" % attempt)
    logs.mkdir()
    environment = dict(os.environ)
    environment["PATH"] = os.pathsep.join([str(llvm / "bin"), str(make.parent),
        str(args.git_root / "mingw64/bin"), str(args.git_root / "usr/bin"), environment.get("PATH", "")])
    environment["CMAKE_BUILD_PARALLEL_LEVEL"] = "1"
    options = [str(args.cmake), "-S", str(source), "-B", str(out / "build"), "-G", "MinGW Makefiles",
        "-DCMAKE_BUILD_TYPE=Release", "-DCMAKE_SYSTEM_NAME=Windows", "-DCMAKE_SYSTEM_PROCESSOR=AMD64",
        "-DCMAKE_C_COMPILER=" + str(compiler), "-DCMAKE_CXX_COMPILER=" + str(cxx),
        "-DCMAKE_MAKE_PROGRAM=" + str(make), "-DCLANG_FORMAT=" + str(llvm / "bin/clang-format.exe"),
        "-DENABLE_LIBRETRO=ON", "-DLIBRETRO_STATIC=ON", "-DENABLE_TESTS=OFF", "-DENABLE_LTO=OFF",
        "-DENABLE_OPENGL=ON", "-DENABLE_VULKAN=OFF", "-DENABLE_SOFTWARE_RENDERER=ON",
        "-DCITRA_WARNINGS_AS_ERRORS=OFF", "-DCITRA_USE_PRECOMPILED_HEADERS=OFF",
        "-DFETCHCONTENT_FULLY_DISCONNECTED=ON", "-DDYNARMIC_USE_BUNDLED_EXTERNALS=ON",
        "-DCMAKE_POLICY_VERSION_MINIMUM=3.5"]
    record(logs / "commands.json", {"configure": options, "jobs": 1,
        "tools": {tool.name: {"bytes": tool.stat().st_size, "sha256": sha(tool)}
                  for tool in [compiler, cxx, make, args.cmake]},
        "environment": "Explicit toolchain directories followed by caller PATH; private PATH not recorded"})
    with (logs / "configure.log").open("w", encoding="utf-8") as log:
        process = subprocess.run(options, env=environment, stdout=log, stderr=subprocess.STDOUT)
    if process.returncode:
        raise RuntimeError("Configure failed; retained " + str(logs / "configure.log"))
    with (logs / "compile.log").open("w", encoding="utf-8") as log:
        process = subprocess.run([str(args.cmake), "--build", str(out / "build"),
            "--target", "citra_libretro", "--parallel", "1"], env=environment,
            stdout=log, stderr=subprocess.STDOUT)
    if process.returncode:
        raise RuntimeError("Compile failed; retained " + str(logs / "compile.log"))
    libraries = list((out / "build").rglob("azahar_libretro.dll"))
    if len(libraries) != 1:
        raise ValueError("Expected exactly one linked frontend DLL")
    library = libraries[0]
    record(logs / "result.json", {"complete": True, "sourceRevision": REVISION,
        "library": {"path": str(library), "bytes": library.stat().st_size, "sha256": sha(library)},
        "adapterSha256": sha(source / "src/citra_libretro/muxi_azahar_link_adapter.cpp"),
        "runtimeValidated": False, "bitIdenticalRebuildClaimed": False})
    print(str(logs / "result.json"))


if __name__ == "__main__":
    main()
