"""Build our Java-only runtime and run an explicitly chosen public libretro core.

No Minecraft launch, old product input, proprietary ROM, or C compiler is used.
The generated mapper-0 battery cartridge is original diagnostic code.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import subprocess


class Assembler:
    def __init__(self):
        self.code = bytearray()
        self.labels = {}
        self.fixups = []

    def emit(self, *values):
        self.code.extend(values)

    def label(self, name):
        self.labels[name] = len(self.code)

    def branch(self, opcode, label):
        self.emit(opcode, 0)
        self.fixups.append((len(self.code) - 1, label, True))

    def jump(self, label):
        self.emit(0x4C, 0, 0)
        self.fixups.append((len(self.code) - 2, label, False))

    def finish(self):
        for position, name, relative in self.fixups:
            target = self.labels[name]
            if relative:
                delta = target - position - 1
                if not -128 <= delta <= 127:
                    raise ValueError("Diagnostic branch out of range")
                self.code[position] = delta & 255
            else:
                address = target + 0x8000
                self.code[position:position + 2] = address.to_bytes(2, "little")
        return bytes(self.code)


def diagnostic_rom(audio: bool = False) -> bytes:
    a = Assembler()
    a.emit(0x78, 0xD8, 0xA2, 0xFF, 0x9A, 0xE8)  # sei/cld/stack/X=0
    for address in (0x2000, 0x2001, 0x4010):
        a.emit(0x8E, address & 255, address >> 8)
    for name in ("warm1", "warm2"):
        a.label(name); a.emit(0x2C, 0x02, 0x20); a.branch(0x10, name)
    if audio:
        # Original diagnostic pulse, independent of either controller's visual/SRAM marker.
        for address, value in ((0x4015, 1), (0x4000, 0xBF), (0x4001, 0x08), (0x4002, 0xFD), (0x4003, 0x08)):
            a.emit(0xA9, value, 0x8D, address & 255, address >> 8)
    a.emit(0xA9, 0, 0x8D, 0x00, 0x60, 0x8D, 0x01, 0x60)
    a.emit(0xA9, 0x11, 0x85, 1, 0xA9, 0x30, 0x85, 2)
    a.emit(0xA9, 0x20, 0x8D, 0x06, 0x20, 0xA9, 0, 0x8D, 0x06, 0x20, 0xA0, 0)
    a.label("row"); a.emit(0xA2, 0)
    a.label("left"); a.emit(0xA9, 1, 0x8D, 0x07, 0x20, 0xE8, 0xE0, 16); a.branch(0xD0, "left")
    a.label("right"); a.emit(0xA9, 2, 0x8D, 0x07, 0x20, 0xE8, 0xE0, 32); a.branch(0xD0, "right")
    a.emit(0xC8, 0xC0, 30); a.branch(0xD0, "row")
    a.emit(0xA2, 64, 0xA9, 0)
    a.label("attributes"); a.emit(0x8D, 0x07, 0x20, 0xCA); a.branch(0xD0, "attributes")
    a.emit(0xA9, 0x0A, 0x8D, 0x01, 0x20)
    a.label("frame"); a.emit(0x2C, 0x02, 0x20); a.branch(0x10, "frame")
    a.emit(0xA9, 1, 0x8D, 0x16, 0x40, 0x4A, 0x8D, 0x16, 0x40)
    for port, color, variable, marker in ((0x16, 0x16, 1, 1), (0x17, 0x1A, 2, 2)):
        a.emit(0xAD, port, 0x40, 0x29, 1); a.branch(0xF0, f"skip{variable}")
        a.emit(0xA9, color, 0x85, variable, 0xA9, marker, 0x8D, variable - 1, 0x60)
        a.label(f"skip{variable}")
    a.emit(0xA9, 0x3F, 0x8D, 0x06, 0x20, 0xA9, 0, 0x8D, 0x06, 0x20)
    a.emit(0xA9, 0x0F, 0x8D, 0x07, 0x20, 0xA5, 1, 0x8D, 0x07, 0x20, 0xA5, 2, 0x8D, 0x07, 0x20)
    # Palette writes leave the temporary PPU address in the palette nametable;
    # restore nametable zero before committing the scroll registers.
    a.emit(0xA9, 0, 0x8D, 0x00, 0x20, 0x8D, 0x05, 0x20, 0x8D, 0x05, 0x20)
    a.jump("frame")
    program = bytearray([0xEA] * 16384)
    code = a.finish(); program[:len(code)] = code
    program[-6:] = b"\x00\x80" * 3
    character = bytearray(8192)
    character[16:24] = b"\xff" * 8
    character[40:48] = b"\xff" * 8
    header = b"NES\x1a" + bytes([1, 1, 2, 0, 1, 0, 0, 0, 0, 0, 0, 0])
    return header + program + character


def main():
    repo = Path(__file__).resolve().parents[1]
    workspace = repo.parent
    parser = argparse.ArgumentParser(description=__doc__)
    target = parser.add_mutually_exclusive_group(required=True)
    target.add_argument("--core", type=Path)
    target.add_argument("--probe-manifest", action="store_true", help="Check public exports in a fresh process per pinned core, without loading any game")
    parser.add_argument("--diagnostic", action="store_true", help="Generate and test our original two-port NES cartridge")
    parser.add_argument("--java-home", type=Path, default=workspace / "test-environment/toolchains/java-21")
    parser.add_argument("--jna", type=Path, default=workspace / "test-environment/resources/client/game/libraries/net/java/dev/jna/jna/5.14.0/jna-5.14.0.jar")
    args = parser.parse_args()
    output = repo / "build/runtime-smoke"
    output.mkdir(parents=True, exist_ok=True)
    sources = sorted((repo / "common/src/main/java/net/muxigame/console/runtime").glob("*.java")) + sorted(
        path for path in (repo / "tests/runtime").glob("*.java") if path.name not in {"MediaLaunchSmoke.java", "MediaAdmissionChecks.java"})
    classes = output / "classes"; classes.mkdir(exist_ok=True)
    java = args.java_home / "bin/java.exe"
    libraries = workspace / "test-environment/resources/client/game/libraries"
    jars = [args.jna]
    for module in ("lwjgl", "lwjgl-glfw", "lwjgl-opengl"):
        jars.extend(libraries / f"org/lwjgl/{module}/3.3.3/{module}-3.3.3{suffix}.jar" for suffix in ("", "-natives-windows"))
    import os
    subprocess.run([str(args.java_home / "bin/javac.exe"), "-J-Xmx512m", "-J-XX:ActiveProcessorCount=1", "--release", "21", "-cp", os.pathsep.join(map(str,jars)), "-d", str(classes), *map(str, sources)], check=True)
    import os
    classpath = os.pathsep.join(map(str, (classes.resolve(), *(jar.resolve() for jar in jars))))
    if args.probe_manifest:
        manifest = json.loads((repo / "third-party/cores.json").read_text(encoding="utf-8"))
        results = []
        for name, entry in manifest["cores"].items():
            platform = entry["platforms"]["windows-x86_64"]
            library = workspace / Path(platform["archive"]).parent / platform["libraryName"]
            if hashlib.sha256(library.read_bytes()).hexdigest() != platform["librarySha256"]:
                raise ValueError("Pinned library hash mismatch: " + name)
            result = subprocess.run([str(java), "-Xmx256m", "-XX:ActiveProcessorCount=1", "-cp", classpath,
                                     "net.muxigame.console.runtime.CoreProbe", str(library)], capture_output=True, text=True, timeout=20)
            results.append({"core": name, "librarySha256": platform["librarySha256"], "exitCode": result.returncode, "stdout": result.stdout.strip(), "stderr": result.stderr.strip()})
            print(name + ": " + ("PASS " if result.returncode == 0 else "FAIL ") + result.stdout.strip())
        (output / "core-export-probes.json").write_text(json.dumps(results, ensure_ascii=False, indent=2), encoding="utf-8")
        raise SystemExit(0 if all(row["exitCode"] == 0 for row in results) else 1)
    command = [str(java), "-Xmx256m", "-XX:ActiveProcessorCount=1", "-cp", classpath, "net.muxigame.console.runtime.RuntimeSmoke", str(java.resolve()), classpath, str(args.core.resolve()), str(output.resolve())]
    record = {"core": str(args.core.resolve()), "coreSha256": hashlib.sha256(args.core.read_bytes()).hexdigest()}
    if args.diagnostic:
        rom = output / "two-port-battery.nes"; raw = diagnostic_rom(); rom.write_bytes(raw)
        record.update(rom=str(rom), romSha256=hashlib.sha256(raw).hexdigest())
        command.append(str(rom.resolve()))
    result = subprocess.run(command, capture_output=True, text=True, timeout=120)
    record.update(exitCode=result.returncode, stdout=result.stdout, stderr=result.stderr)
    (output / ("diagnostic-result.json" if args.diagnostic else "no-content-result.json")).write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
    print(result.stdout, end=""); print(result.stderr, end="")
    raise SystemExit(result.returncode)


if __name__ == "__main__":
    main()
