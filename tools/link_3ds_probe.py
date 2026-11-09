"""Offline original UDS homebrew and exact native Room/relocation diagnostics.

Every Windows worker retains its actual process handle until signaled. Reported
exit codes alone never count as termination, and forced cleanup stays a failure.
"""
from pathlib import Path
import argparse
import ctypes
from concurrent.futures import ThreadPoolExecutor
from ctypes import wintypes
import hashlib
import importlib.util
import json
import os
import queue
import secrets
import shutil
import struct
import subprocess
import threading
import time
import traceback

ROOT = Path(__file__).resolve().parents[1]
WORKSPACE = ROOT.parent
SDK = WORKSPACE / "test-environment/toolchains/muxi-console-expansion-devkitpro/install/opt/devkitpro"


def sha(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1048576), b""):
            value.update(block)
    return value.hexdigest()


def write(path, value):
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def tree(path):
    return {file.relative_to(path).as_posix(): {"bytes": file.stat().st_size, "sha256": sha(file)}
            for file in sorted(path.rglob("*")) if file.is_file() and not file.is_symlink()}


def compile_inputs(out):
    shutil.copyfile(Path(__file__), out / "driver-used.py")
    source = ROOT / "native/link/diagnostics/azahar_uds.c"
    compiler = SDK / "devkitARM/bin/arm-none-eabi-gcc.exe"
    packager = SDK / "tools/bin/3dsxtool.exe"
    flags = ["-O2", "-march=armv6k", "-mtune=mpcore", "-mfloat-abi=hard", "-mfpu=vfp",
             "-mword-relocations", "-D__3DS__", "-I" + str(SDK / "libctru/include")]
    environment = dict(os.environ, DEVKITPRO=str(SDK), DEVKITARM=str(SDK / "devkitARM"))
    originals = []
    for role in range(2):
        commands = [[str(compiler), *flags, "-DMUXI_UDS_ROLE=" + str(role), "-c", str(source), "-o", str(out / ("role%d.o" % role))],
                    [str(compiler), *flags, "-specs=3dsx.specs", str(out / ("role%d.o" % role)), "-L" + str(SDK / "libctru/lib"), "-lctru", "-lm", "-o", str(out / ("role%d.elf" % role))],
                    [str(packager), str(out / ("role%d.elf" % role)), str(out / ("original-uds-%d.3dsx" % role))]]
        for index, command in enumerate(commands):
            result = subprocess.run(command, cwd=out, env=environment, capture_output=True, text=True, timeout=120)
            (out / ("rom-%d-%d.log" % (role, index))).write_text(result.stdout + result.stderr, encoding="utf-8")
            if result.returncode:
                raise RuntimeError("Original UDS diagnostic compile failed")
        rom = out / ("original-uds-%d.3dsx" % role)
        originals.append({"role": role, "path": rom.name, "bytes": rom.stat().st_size, "sha256": sha(rom)})
    spec = importlib.util.spec_from_file_location("owned_worker_build", ROOT / "tools/build_worker.py")
    worker = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(worker)
    jars = []
    for dependency in worker.dependencies():
        candidates = [WORKSPACE / "test-environment/cache/muxi-console/dependencies" / dependency["file"]]
        candidates.extend((WORKSPACE / "test-environment/resources/client/game/libraries").rglob(dependency["file"]))
        jar = next((file for file in candidates if file.is_file() and file.stat().st_size == dependency["bytes"] and sha(file) == dependency["sha256"]), None)
        if jar is None:
            raise ValueError("Missing existing exact worker dependency: " + dependency["file"])
        jars.append(jar)
    gson = list((WORKSPACE / "test-environment/resources/client/game/libraries/com/google/code/gson/gson/2.10.1").glob("*.jar"))
    if len(gson) != 1:
        raise ValueError("Expected existing Gson 2.10.1 from the shared client resource")
    jars += gson
    source_files = sorted((ROOT / "common/src/main/java/net/muxigame/console/runtime").glob("*.java"))
    source_files.append(ROOT / "tests/link/native/Link3dsRoomWorker.java")
    source_files.append(ROOT / "tests/link/native/Link3dsRuntimeSmoke.java")
    source_files.append(ROOT / "tests/link/native/Link3dsContractTest.java")
    source_files.append(ROOT / "tests/link/native/Link3dsPortableSmoke.java")
    # Capture exact input bytes before compiling: other owners may continue unrelated runtime work.
    snapshot = out / "compile-inputs"
    copied = []
    hashes = {}
    for file in source_files:
        relative = file.relative_to(ROOT)
        target = snapshot / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(file, target)
        copied.append(target)
        hashes[relative.as_posix()] = sha(target)
    classes = out / "classes"
    classes.mkdir()
    java = WORKSPACE / "test-environment/toolchains/java-21/bin/java.exe"
    javac = java.with_name("javac.exe")
    classpath = os.pathsep.join(map(str, jars))
    result = subprocess.run([str(javac), "-J-Xmx512m", "-J-XX:ActiveProcessorCount=1", "--release", "21", "-encoding", "UTF-8", "-proc:none", "-cp", classpath, "-d", str(classes), *map(str, copied)], capture_output=True, text=True, timeout=120)
    (out / "compile.log").write_text(result.stdout + result.stderr, encoding="utf-8")
    if result.returncode:
        raise RuntimeError("Diagnostic helper compile failed")
    write(out / "inputs.json", {"firstPartyRom": {"source": str(source.relative_to(ROOT)), "sha256": sha(source)},
        "roms": originals, "runtimeSources": hashes, "driverSha256": sha(Path(__file__)),
        "sdkLockSha256": sha(SDK.parents[2] / "sdk-lock.json"),
        "dependencies": [{"file": jar.name, "bytes": jar.stat().st_size, "sha256": sha(jar)} for jar in jars],
        "compiler": {"sha256": sha(compiler)}, "packager": {"sha256": sha(packager)}})
    return java, os.pathsep.join([str(classes), classpath])


class Worker:
    def __init__(self, java, classpath, core, root, llvm):
        self.root, self.queue, self.forced = root, queue.Queue(), False
        root.mkdir()
        self.native = root / "native"
        self.native.mkdir()
        self.error = (root / "stderr.log").open("w", encoding="utf-8")
        environment = dict(os.environ)
        environment["PATH"] = str(llvm / "bin") + os.pathsep + environment.get("PATH", "")
        self.process = subprocess.Popen([str(java), "-Xmx256m", "-XX:ActiveProcessorCount=1", "-Djna.tmpdir=" + str(self.native), "-Dorg.lwjgl.system.SharedLibraryExtractPath=" + str(self.native), "-Djava.io.tmpdir=" + str(self.native), "-cp", classpath,
            "net.muxigame.console.runtime.Link3dsRoomWorker", str(core)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.error, text=True, encoding="utf-8", env=environment, bufsize=1)
        self.kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        self.kernel.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
        self.kernel.WaitForSingleObject.restype = wintypes.DWORD
        self.kernel.GetProcessTimes.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.FILETIME), ctypes.POINTER(wintypes.FILETIME), ctypes.POINTER(wintypes.FILETIME), ctypes.POINTER(wintypes.FILETIME)]
        self.kernel.GetExitCodeProcess.argtypes = [wintypes.HANDLE, ctypes.POINTER(wintypes.DWORD)]
        self.kernel.TerminateProcess.argtypes = [wintypes.HANDLE, wintypes.UINT]
        self.handle = wintypes.HANDLE(int(self.process._handle))
        self.creation = self.times()[0]
        def read():
            for line in self.process.stdout:
                self.queue.put(line)
            self.queue.put(None)
        threading.Thread(target=read, daemon=True).start()

    def times(self):
        values = [wintypes.FILETIME() for _ in range(4)]
        if not self.kernel.GetProcessTimes(self.handle, *[ctypes.byref(value) for value in values]):
            raise OSError(ctypes.get_last_error(), "GetProcessTimes")
        return [value.dwLowDateTime | value.dwHighDateTime << 32 for value in values]

    def command(self, action, **fields):
        self.process.stdin.write(json.dumps(dict(action=action, **fields)) + "\n")
        self.process.stdin.flush()
        deadline = time.monotonic() + 120
        while time.monotonic() < deadline:
            line = self.queue.get(timeout=max(.1, deadline - time.monotonic()))
            if line is None:
                raise RuntimeError("Native worker stdout closed; process is not assumed terminated")
            with (self.root / "replies.jsonl").open("a", encoding="utf-8") as log:
                log.write(line)
            try:
                result = json.loads(line)
            except json.JSONDecodeError:
                continue
            if result.get("complete") is not True:
                raise RuntimeError("Native worker command rejected: " + result.get("error", "unknown"))
            return result
        raise TimeoutError("Native worker reply deadline")

    def close(self):
        complete, quit_error = False, None
        try:
            if self.kernel.WaitForSingleObject(self.handle, 0) != 0:
                self.command("quit")
        except Exception as error:
            quit_error = str(error)
        self.process.stdin.close()
        wait = self.kernel.WaitForSingleObject(self.handle, 30000)
        reported_before_force = None
        if wait == 258:
            if self.times()[0] != self.creation:
                raise RuntimeError("Owned native process identity changed")
            reported = wintypes.DWORD()
            if not self.kernel.GetExitCodeProcess(self.handle, ctypes.byref(reported)):
                raise OSError(ctypes.get_last_error(), "GetExitCodeProcess before owned cleanup")
            reported_before_force = reported.value
            termination_code = reported.value if reported.value not in (0, 259) else 73
            if not self.kernel.TerminateProcess(self.handle, termination_code):
                raise OSError(ctypes.get_last_error(), "Owned native cleanup rejected")
            self.forced = True
            wait = self.kernel.WaitForSingleObject(self.handle, 10000)
        code = wintypes.DWORD()
        if not self.kernel.GetExitCodeProcess(self.handle, ctypes.byref(code)):
            raise OSError(ctypes.get_last_error(), "GetExitCodeProcess")
        creation, exited, _, _ = self.times()
        signaled = wait == 0
        if signaled:
            self.process.wait(timeout=1)
            self.error.close()
            for file in self.native.iterdir():
                if file.is_file() and not file.is_symlink():
                    file.unlink()
            complete = not self.forced and code.value == 0 and exited != 0 and not list(self.native.iterdir())
        result = {"pid": self.process.pid, "creationFiletime": creation, "exitFiletime": exited,
            "signaled": signaled, "exitCode": code.value, "forced": self.forced,
            "reportedExitCodeBeforeForce": reported_before_force,
            "nativeClean": not list(self.native.iterdir()), "complete": complete, "quitError": quit_error}
        write(self.root / "closure.json", result)
        return result


def evidence(profile):
    files = list(profile.rglob("muxi-original-uds-evidence.bin"))
    if len(files) != 1:
        return None
    raw = files[0].read_bytes()
    # The guest's ordinary fopen/fwrite/fclose spans emulated service scheduling.
    # A visible newly truncated file is not a completed guest evidence record.
    if len(raw) < 48:
        return None
    if len(raw) > 48:
        raise ValueError("Original UDS evidence size differs")
    names = ["magic", "role", "stage", "frame", "sent", "received", "peerRole", "peerSequence", "peerChallenge", "result", "localNode", "totalNodes"]
    return dict(zip(names, struct.unpack("<12I", raw)), path=str(files[0]), sha256=sha(files[0]))


def runtime_probe(out, java, classpath, core, core_sha, input_rom, ordinary_only, portable_only, prior_state=None, prior_sha=None):
    if input_rom.stat().st_size != 67752 or sha(input_rom) != "d4aaf097980e4515ccd7d1f7082d5fb758809b869fb498a4c3b84786388f642f":
        raise ValueError("Product-runtime input probe requires the pinned original expansion diagnostic 12")
    contract = subprocess.run([str(java), "-Xmx128m", "-XX:ActiveProcessorCount=1", "-cp", classpath,
        "net.muxigame.console.runtime.Link3dsContractTest", str(out)], capture_output=True, text=True, timeout=30)
    (out / "contract.log").write_text(contract.stdout + contract.stderr, encoding="utf-8")
    if contract.returncode:
        raise RuntimeError("3DS runtime public-contract regression failed")
    native = out / "parent-native"
    native.mkdir()
    command = [str(java), "-Xmx256m", "-XX:ActiveProcessorCount=1", "-Djna.tmpdir=" + str(native),
        "-Dorg.lwjgl.system.SharedLibraryExtractPath=" + str(native), "-cp", classpath,
        "net.muxigame.console.runtime.Link3dsRuntimeSmoke", str(java), str(core), core_sha,
        str(out / "original-uds-0.3dsx"), str(out / "original-uds-1.3dsx"), str(input_rom), str(out)]
    if ordinary_only:
        command.append("ordinary-only")
    if portable_only:
        command = command[:command.index("net.muxigame.console.runtime.Link3dsRuntimeSmoke")] + [
            "net.muxigame.console.runtime.Link3dsPortableSmoke", str(java), str(core), core_sha, str(input_rom), str(out)]
        if prior_state:
            command += [str(prior_state.resolve()), prior_sha]
    with (out / "run.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen(command, stdout=log, stderr=subprocess.STDOUT)
        print("Owned 3DS runtime diagnostic parent PID", process.pid, flush=True)
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.WaitForSingleObject.argtypes = [wintypes.HANDLE, wintypes.DWORD]
        kernel.WaitForSingleObject.restype = wintypes.DWORD
        signaled = kernel.WaitForSingleObject(wintypes.HANDLE(int(process._handle)), 600000) == 0
        if not signaled:
            raise RuntimeError("Runtime diagnostic parent termination not confirmed; exact-owned process retained, no cleanup claimed")
        code = process.wait(timeout=1)
    for file in native.iterdir():
        if file.is_file() and not file.is_symlink():
            file.unlink()
    write(out / "parent-closure.json", {"pid": process.pid, "signaled": signaled, "exitCode": code,
        "nativeEmpty": not list(native.iterdir()), "inputsSHA256": sha(out / "inputs.json")})
    print(str(out / "result.json"))
    if code:
        raise SystemExit(code)


def restore_radio(out, java, classpath, core, llvm, port, password, profiles, warm_roms,
                  before, checkpoints, result, workers, check):
    """New native ABI2 only: guest radio context survives a new frontend Room.

    This intentionally does not checkpoint ENet, room queues or in-flight packets.
    Both guest CPUs must produce and receive *new* application packets after load.
    """
    relocated = [out / ("cold-radio-left" if role == 0 else "cold-radio-right") for role in range(2)]
    old_trees = [tree(profile) for profile in profiles]
    for role in range(2):
        shutil.copytree(profiles[role], relocated[role])
        workers.append(Worker(java, classpath, core, out / ("worker-radio-cold-%d" % role), llvm))
    workers[0].command("host", port=port, password=password)
    for role, worker in enumerate(workers):
        worker.command("join", port=port, password=password, role=role)
    deadline = time.monotonic() + 15
    while time.monotonic() < deadline:
        statuses = [worker.command("status")["status"] for worker in workers]
        if all(value["memberState"] in (3, 4) for value in statuses):
            break
        time.sleep(.1)
    check("two fresh native frontend members joined with ABI2 and no restored transport counters",
          all(value["memberState"] in (3, 4) and value["profileStateAbi"] == 2 and
              value["txDataPackets"] == value["rxDataPackets"] == 0 for value in statuses))
    for role, worker in enumerate(workers):
        worker.command("core", rom=str(warm_roms[role]), profile=str(relocated[role]))
        worker.command("step", count=2)
        worker.command("native_load", path=str(checkpoints[role]))
    # Real guest B input rotates both application challenges after loading.
    # A packet already stored in either CPU snapshot cannot match this nonce.
    nonce = 0x6d39a7c2
    with ThreadPoolExecutor(max_workers=2) as execution:
        for future in [execution.submit(worker.command, "step", count=12, buttons=1) for worker in workers]:
            future.result()
    after = [None, None]
    with ThreadPoolExecutor(max_workers=2) as execution:
        for _ in range(180):
            pending = [execution.submit(worker.command, "step", count=6) for worker in workers]
            for future in pending:
                future.result()
            after = [evidence(profile) for profile in relocated]
            if all(value and value["received"] > before[role]["received"] and value["sent"] > before[role]["sent"] and
                   value["peerChallenge"] == ([0x319ac76d, 0xb6502fe4][1-role] ^ nonce) for role, value in enumerate(after)):
                break
    result["radioRestore"] = {"before": before, "after": after, "queuesRestored": False, "freshChallengeXor": nonce}
    check("both restored guest UDS contexts receive newly generated opposite challenge packets",
          all(value and value["received"] > before[role]["received"] and value["sent"] > before[role]["sent"] and
              value["peerChallenge"] == ([0x319ac76d, 0xb6502fe4][1-role] ^ nonce) and value["totalNodes"] == 2
              for role, value in enumerate(after)))
    statuses = []
    for role, worker in enumerate(workers):
        reply = worker.command("step", count=12, picture=str(out / ("radio-cold-%d.png" % role)))
        statuses.append(reply["status"])
    result["radioRestore"]["statuses"] = statuses
    check("fresh native transport really carries data both ways after radio-context restore",
          all(value["txDataPackets"] > 0 and value["rxDataPackets"] > 0 for value in statuses))
    for worker in workers:
        result["workers"].append(worker.close())
    workers.clear()
    check("both restored radio workers naturally exit with real process signals and no native residue",
          all(value["complete"] for value in result["workers"][-2:]))
    check("radio-context cold restore leaves both closed original profile trees unchanged",
          all(tree(profile) == old_trees[role] for role, profile in enumerate(profiles)))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--core", type=Path)
    parser.add_argument("--core-sha")
    parser.add_argument("--port", type=int, default=26311)
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--rom-relocation", action="store_true", help="Cold restore with a new ROM path while the former actual ROM path is unavailable")
    parser.add_argument("--radio-restore", action="store_true", help="New ABI2 only: restore both radio contexts into a new Room and require new UDS application traffic")
    parser.add_argument("--runtime", action="store_true", help="Use the real product Link3dsRuntime/Worker and ordinary single-core export")
    parser.add_argument("--input-rom", type=Path, help="Pinned original expansion-diagnostics-12/muxi-input.3dsx")
    parser.add_argument("--ordinary-only", action="store_true", help="Keep the known failed active-UDS restore separate; verify ordinary save/input only")
    parser.add_argument("--portable-only", action="store_true", help="Product MXS3 export/cold restore with both profile and actual ROM path changed")
    parser.add_argument("--prior-format-state", type=Path, help="Preserved real older-format MXS3; product worker must refuse it before native restore")
    parser.add_argument("--prior-format-sha")
    args = parser.parse_args()
    if args.runtime and args.radio_restore:
        raise ValueError("--radio-restore is a separate raw native ABI2 proof, not a product-wrapper claim")
    if not args.runtime and (args.ordinary_only or args.portable_only):
        raise ValueError("Product-wrapper scope flags require --runtime")
    if args.prior_format_state or args.prior_format_sha:
        if not args.runtime or not args.portable_only or not args.prior_format_state or not args.prior_format_sha or sha(args.prior_format_state) != args.prior_format_sha:
            raise ValueError("Prior-format proof needs exact preserved bytes and product --runtime --portable-only")
    out = args.output.resolve()
    if not out.is_relative_to((ROOT / "build").resolve()):
        raise ValueError("Use a new repository build directory")
    if os.name != "nt":
        raise ValueError("This native diagnostic requires real Windows process-handle auditing")
    if not args.prepare_only and (not args.core or not args.core_sha or sha(args.core) != args.core_sha):
        raise ValueError("Runtime test requires the exact newly built DLL pin")
    out.mkdir(exist_ok=False)
    java, classpath = compile_inputs(out)
    if args.prepare_only:
        print(str(out / "inputs.json"))
        return
    if args.runtime:
        if args.rom_relocation or not args.input_rom:
            raise ValueError("Runtime mode needs --input-rom and is separate from the raw native ROM-path proof")
        runtime_probe(out, java, classpath, args.core.resolve(), args.core_sha, args.input_rom.resolve(), args.ordinary_only, args.portable_only, args.prior_format_state, args.prior_format_sha)
        return
    llvm = next((WORKSPACE / "test-environment/toolchains/muxi-console-rebuild-llvm-mingw-20250613").glob("install/*"))
    core = args.core.resolve()
    result = {"complete": False, "coreSha256": sha(core), "inputsSha256": sha(out / "inputs.json"), "checks": [], "workers": []}
    workers = []

    def check(name, condition):
        result["checks"].append({"name": name, "pass": bool(condition)})
        if not condition:
            raise AssertionError(name)

    try:
        password = secrets.token_hex(16)
        profiles = [out / "machine-left", out / "machine-right"]
        warm_roms = [out / ("original-uds-%d.3dsx" % role) for role in range(2)]
        if args.rom_relocation:
            warm = out / "actual-warm-content"
            warm.mkdir()
            for role in range(2):
                copy = warm / ("role-%d.3dsx" % role)
                shutil.copyfile(warm_roms[role], copy)
                warm_roms[role] = copy
        for profile in profiles:
            profile.mkdir()
        for role in range(2):
            workers.append(Worker(java, classpath, core, out / ("worker-%d" % role), llvm))
        workers[0].command("host", port=args.port, password=password)
        for role, worker in enumerate(workers):
            worker.command("join", port=args.port, password=password, role=role)
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            states = [worker.command("status")["status"] for worker in workers]
            if all(value["memberState"] in (3, 4) for value in states):
                break
            time.sleep(.1)
        check("two actual native Room members joined", all(value["memberState"] in (3, 4) for value in states))
        for role, worker in enumerate(workers):
            worker.command("core", rom=str(warm_roms[role]), profile=str(profiles[role]))
        # Let both independent cores execute while either guest blocks on a UDS service event.
        with ThreadPoolExecutor(max_workers=2) as execution:
            for batch in range(360):
                pending = [execution.submit(worker.command, "step", count=6) for worker in workers]
                for future in pending:
                    future.result()
                values = [evidence(profile) for profile in profiles]
                if all(value and value["received"] > 0 and value["stage"] == 3 for value in values):
                    break
        result["udsEvidence"] = values
        check("both homebrew guest UDS APIs receive their opposite role challenge", all(value and value["received"] > 0 and value["peerRole"] == 1 - role and value["peerChallenge"] == [0x319ac76d, 0xb6502fe4][1-role] for role, value in enumerate(values)))
        check("actual guest UDS node membership contains two distinct nodes", all(value["totalNodes"] == 2 for value in values) and {value["localNode"] for value in values} == {1, 2})
        statuses = []
        pictures = []
        for role, worker in enumerate(workers):
            path = out / ("actual-uds-%d.png" % role)
            reply = worker.command("step", count=30, picture=str(path))
            statuses.append(reply["status"])
            from PIL import Image
            with Image.open(path) as image:
                image.load()
                pixel = image.convert("RGB").getpixel((160, 360))
                pictures.append({"path": path.name, "sha256": sha(path), "size": list(image.size), "actualBottomPixel": list(pixel),
                                 "pcmSamples": reply["pcmSamples"], "nonzeroPcmSamples": reply["nonzeroPcmSamples"],
                                 "sampleRate": reply["sampleRate"]})
        result["pictures"] = pictures
        check("two real independent bottom surfaces display challenge received", all(item["size"] == [400, 480] and abs(item["actualBottomPixel"][0] - 24) <= 3 and abs(item["actualBottomPixel"][1] - 224) <= 3 and abs(item["actualBottomPixel"][2] - [32, 192][role]) <= 3 for role, item in enumerate(pictures)))
        result["nativeStatus"] = statuses
        check("both actual frontend WiFi data transmit and receive counters advanced", all(value["txDataPackets"] > 0 and value["rxDataPackets"] > 0 for value in statuses))
        native = out / "native-checkpoint.bin"
        workers[0].command("native_save", path=str(native))
        result["nativeCheckpoint"] = {"bytes": native.stat().st_size, "sha256": sha(native)}
        if args.radio_restore:
            check("requested separate complete-radio native serialization ABI2", all(value["profileStateAbi"] == 2 for value in statuses))
            right_native = out / "native-right-checkpoint.bin"
            workers[1].command("native_save", path=str(right_native))
            # Read the final guest-generated counters after both CPU checkpoints.
            # No guest CPU runs between these saves and this read.
            radio_before = [evidence(profile) for profile in profiles]
            result["nativeRightCheckpoint"] = {"bytes": right_native.stat().st_size, "sha256": sha(right_native)}
        for worker in workers:
            result["workers"].append(worker.close())
        workers.clear()
        check("first two actual process handles signaled with natural zero exit", all(value["complete"] for value in result["workers"]))
        if args.radio_restore:
            restore_radio(out, java, classpath, core, llvm, args.port, secrets.token_hex(16), profiles,
                          warm_roms, radio_before, [native, right_native], result, workers, check)
        original_files = tree(profiles[0])
        relocated = out / "different-cold-machine-root"
        shutil.copytree(profiles[0], relocated)
        cold_rom = out / "original-uds-0.3dsx"
        if args.rom_relocation:
            old_rom = warm_roms[0]
            retained = out / "retired-warm-content"
            retained.mkdir()
            moved = retained / "original-role0.3dsx"
            old_rom.rename(moved)
            cold_directory = out / "different-cold-content"
            cold_directory.mkdir()
            cold_rom = cold_directory / "relocated-role0.3dsx"
            shutil.copyfile(moved, cold_rom)
            result["romRelocation"] = {"originalActualPath": str(old_rom), "retainedOriginal": str(moved),
                "coldActualPath": str(cold_rom), "sha256": sha(cold_rom), "oldPathAvailable": old_rom.exists()}
            check("old actual ROM path is unavailable and cold ROM bytes are identical", not old_rom.exists() and sha(cold_rom) == sha(moved) == sha(out / "original-uds-0.3dsx"))
        cold = Worker(java, classpath, core, out / "worker-cold", llvm)
        workers.append(cold)
        cold.command("core", rom=str(cold_rom), profile=str(relocated))
        cold.command("step", count=2)
        cold.command("native_load", path=str(native))
        cold.command("step", count=12, buttons=256)
        cold.command("step", count=35)
        writes = list(relocated.rglob("muxi-original-relocation.bin"))
        check("new native state cold restore writes only current machine profile", len(writes) == 1 and writes[0].read_bytes() == struct.pack("<3I", 0x52454c31, 1, 0))
        result["workers"].append(cold.close())
        workers.clear()
        check("old machine directory remains byte-for-byte unchanged after cross-root native restore", tree(profiles[0]) == original_files)
        check("cold actual process handle signaled natural zero and native cleanup", result["workers"][-1]["complete"])
        check("exact native DLL remains the requested SHA throughout the proof", sha(core) == args.core_sha)
        result["oldProfile"] = original_files
        result["relocatedProfile"] = tree(relocated)
        result["complete"] = True
    except Exception as error:
        result["error"] = str(error)
        (out / "failure.log").write_text(traceback.format_exc(), encoding="utf-8")
    finally:
        for worker in workers:
            try:
                result["workers"].append(worker.close())
            except Exception as error:
                result.setdefault("cleanupFailures", []).append(str(error))
        if any(not value["complete"] for value in result["workers"]) or result.get("cleanupFailures"):
            result["complete"] = False
        write(out / "result.json", result)
    print(str(out / "result.json"))
    if not result["complete"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
