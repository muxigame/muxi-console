"""Original ARM7TDMI GBA register diagnostic and isolated mGBA runtime check.

Hardware reference: https://mgba-emu.github.io/gbatek/ (public GBATEK mirror).
Uses mode 3 / BG2, KEYINPUT and SRAM. No manufacturer logo, game or BIOS bytes.
Only the pinned independently downloaded official mGBA library is accepted.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess


class Arm:
    def __init__(self):
        self.words = []
        self.labels = {}
        self.branches = []
        self.literals = []

    def emit(self, value):
        self.words.append(value)

    def label(self, name):
        self.labels[name] = len(self.words)

    def branch(self, name, condition=14):
        self.branches.append((len(self.words), name, condition))
        self.emit(0)

    def literal(self, register, value, condition=14):
        self.literals.append((len(self.words), register, value, condition))
        self.emit(0)

    def alu(self, opcode, destination, source, value, flags=False, condition=14):
        for rotation in range(16):
            shift = rotation * 2
            immediate = ((value << shift) | (value >> ((32 - shift) % 32))) & 0xFFFFFFFF
            if immediate <= 255:
                self.emit((condition << 28) | 0x02000000 | (opcode << 21) | (int(flags) << 20) |
                          (source << 16) | (destination << 12) | (rotation << 8) | immediate)
                return
        raise ValueError("Value is not an ARM rotated immediate")

    def halfword(self, register, base, offset=0, load=False, condition=14):
        if not 0 <= offset <= 255:
            raise ValueError("Halfword displacement out of range")
        self.emit((condition << 28) | 0x01C000B0 | (int(load) << 20) | (base << 16) |
                  (register << 12) | ((offset & 0xF0) << 4) | (offset & 15))

    def byte_store(self, register, base, offset=0, condition=14):
        self.emit((condition << 28) | 0x05C00000 | (base << 16) | (register << 12) | offset)

    def finish(self):
        for position, label, condition in self.branches:
            self.words[position] = (condition << 28) | 0x0A000000 | ((self.labels[label] - position - 2) & 0xFFFFFF)
        constants = {}
        for position, register, value, condition in self.literals:
            if value not in constants:
                constants[value] = len(self.words)
                self.words.append(value)
            displacement = (constants[value] - position) * 4 - 8
            if not 0 <= displacement <= 4095:
                raise ValueError("Literal pool out of ARM range")
            self.words[position] = (condition << 28) | 0x059F0000 | (register << 12) | displacement
        return b"".join(struct.pack("<I", word) for word in self.words)


def diagnostic_rom():
    arm = Arm()
    arm.literal(0, 0x04000000)  # DISPCNT
    arm.literal(1, 0x0403)     # mode 3 and BG2 enabled
    arm.halfword(1, 0)
    arm.literal(0, 0x04000020)  # Explicit BG2 affine identity, without BIOS assumptions.
    arm.literal(1, 0x100)
    arm.halfword(1, 0, 0)
    arm.halfword(1, 0, 6)
    arm.alu(13, 1, 0, 0)       # mov r1,#0
    arm.halfword(1, 0, 2)
    arm.halfword(1, 0, 4)
    arm.emit(0xE5801008)        # str r1,[r0,#8]   BG2X
    arm.emit(0xE580100C)        # str r1,[r0,#12]  BG2Y
    arm.literal(2, 0x04000130)  # KEYINPUT, active low
    arm.literal(8, 0x0E000000)  # byte-addressed battery SRAM
    arm.literal(4, 0x7C00)      # blue RGB555
    arm.label("poll")
    arm.halfword(3, 2, load=True)
    arm.alu(8, 0, 3, 1, flags=True)             # tst r3,#A
    arm.alu(13, 4, 0, 0x1F, condition=0)       # moveq r4,#red
    arm.alu(13, 5, 0, 0xA1, condition=0)
    arm.byte_store(5, 8, 0, condition=0)
    arm.alu(8, 0, 3, 8, flags=True)             # tst r3,#Start
    arm.literal(4, 0x03E0, condition=0)         # ldreq r4,=green
    arm.alu(13, 5, 0, 0x51, condition=0)
    arm.byte_store(5, 8, 1, condition=0)
    arm.literal(6, 0x06000000)  # VRAM
    arm.literal(7, 240 * 160)
    arm.label("paint")
    arm.halfword(4, 6)
    arm.alu(4, 6, 6, 2)         # add r6,r6,#2
    arm.alu(2, 7, 7, 1, flags=True)
    arm.branch("paint", condition=1)  # bne
    arm.branch("poll")
    code = arm.finish()
    rom = bytearray(65536)
    struct.pack_into("<I", rom, 0, 0xEA00003E)  # branch from header to our 0x100 entry
    rom[0xA0:0xAC] = b"MUXI GBA IO ".ljust(12, b" ")
    rom[0xAC:0xB0] = b"MXDG"
    rom[0xB0:0xB2] = b"00"
    rom[0xB2] = 0x96
    rom[0xBD] = (-sum(rom[0xA0:0xBD]) - 0x19) & 255
    rom[0x100:0x100 + len(code)] = code
    rom[0x400:0x40A] = b"SRAM_V113\x00"
    assert not any(rom[4:0xA0]), "Manufacturer logo area must stay empty"
    assert (sum(rom[0xA0:0xBE]) + 0x19) & 255 == 0
    return bytes(rom)


def main():
    repo = Path(__file__).resolve().parents[1]
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", type=Path, default=repo / "build/independent-06")
    parser.add_argument("--generate-only", action="store_true")
    args = parser.parse_args()
    output = repo / "build/gba-diagnostic"; output.mkdir(parents=True, exist_ok=True)
    raw = diagnostic_rom(); rom = output / "muxi-register-diagnostic.gba"; rom.write_bytes(raw)
    manifest = json.loads((repo / "third-party/cores.json").read_text(encoding="utf-8"))
    pin = manifest["cores"]["mgba"]["platforms"]["windows-x86_64"]
    core = repo.parent / Path(pin["archive"]).parent / pin["libraryName"]
    digest = lambda data: hashlib.sha256(data).hexdigest()
    if digest(core.read_bytes()) != pin["librarySha256"]:
        raise ValueError("mGBA does not match this product's official core pin")
    report = {"rom": str(rom), "romSha256": digest(raw), "romBytes": len(raw), "manufacturerLogoIncluded": False,
              "biosIncluded": False, "core": str(core), "coreSha256": pin["librarySha256"], "reference": "https://mgba-emu.github.io/gbatek/"}
    if args.generate_only:
        print(json.dumps(report, indent=2)); return
    java_home = repo.parent / "test-environment/toolchains/java-21"
    jna = repo.parent / "test-environment/resources/client/game/libraries/net/java/dev/jna/jna/5.14.0/jna-5.14.0.jar"
    classes = output / "classes"; classes.mkdir(exist_ok=True)
    runtime_classpath = os.pathsep.join(str(path.resolve()) for path in (args.sdk / "classes", jna))
    runtime_sources = sorted((repo / "common/src/main/java/net/muxigame/console/runtime").glob("*.java"))
    subprocess.run([str(java_home / "bin/javac.exe"), "-J-Xmx128m", "-J-XX:ActiveProcessorCount=1", "--release", "21", "-encoding", "UTF-8", "-proc:none",
                    "-cp", runtime_classpath, "-d", str(classes), *map(str, runtime_sources), str(repo / "tests/runtime/GbaSmoke.java")], check=True)
    classpath = str(classes.resolve()) + os.pathsep + runtime_classpath
    command = [str(java_home / "bin/java.exe"), "-Xmx256m", "-XX:ActiveProcessorCount=1", "-cp", classpath,
               "net.muxigame.console.runtime.GbaSmoke", str(java_home / "bin/java.exe"), classpath, str(core), str(rom), str(output)]
    result = subprocess.run(command, capture_output=True, text=True, timeout=120)
    report.update(exitCode=result.returncode, stdout=result.stdout, stderr=result.stderr)
    (output / "result.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(result.stdout, end=""); print(result.stderr, end="")
    raise SystemExit(result.returncode)


if __name__ == "__main__":
    main()
