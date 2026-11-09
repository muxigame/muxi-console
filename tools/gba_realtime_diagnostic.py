"""Original GBA VBlank/input/PSG timing diagnostic; existing ROMs are unchanged.

The software baseline samples native button levels once per emulated frame. It
does not claim that every 10 ms wall-clock pulse intersects that sampling point.
Minecraft callback/edge preservation is measured separately by latency_review.
Hardware reference: https://mgba-emu.github.io/gbatek/
"""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import struct
import subprocess
import zipfile
from gba_diagnostic import Arm

ROOT = Path(__file__).resolve().parents[1]
PUBLISHED_SHA256 = "3159e379e49db8446dea43896cbcb60d6a229f1b0c96d02a06a4488c70fe2149"
FIELDS = ("frame", "downs", "ups", "keys", "lastDownFrame", "beat", "signature")
SIGNATURE = 0xA65C


def diagnostic_rom() -> bytes:
    a = Arm()
    a.literal(0, 0x04000000); a.literal(1, 0x403); a.halfword(1, 0)
    a.literal(0, 0x04000020); a.literal(1, 0x100); a.halfword(1, 0); a.halfword(1, 0, 6)
    a.alu(13, 1, 0, 0); a.halfword(1, 0, 2); a.halfword(1, 0, 4)
    a.emit(0xE5801008); a.emit(0xE580100C)  # BG2 X/Y, no BIOS assumptions.
    a.literal(0, 0x06000000); a.literal(1, 0x2108); a.literal(2, 240 * 160)
    a.label("background"); a.halfword(1, 0); a.alu(4, 0, 0, 2); a.alu(2, 2, 2, 1, flags=True); a.branch("background", 1)
    # Channel 2: quiet continuous 512 Hz pilot on left. Channel 1: 2048 Hz
    # metronome on right for two VBlanks every 30 actual guest VBlanks.
    a.literal(0, 0x04000084); a.alu(13, 1, 0, 0x80); a.halfword(1, 0)
    a.literal(0, 0x04000080); a.literal(1, 0x2177); a.halfword(1, 0)
    a.alu(13, 1, 0, 2); a.halfword(1, 0, 2)  # Full PSG mixing ratio.
    a.literal(0, 0x04000060); a.alu(13, 1, 0, 0); a.halfword(1, 0); a.halfword(1, 0, 2)
    a.literal(0, 0x04000068); a.literal(1, 0x3080); a.halfword(1, 0)
    a.literal(1, 0x8000 | 1792); a.halfword(1, 0, 4)
    for reg in range(4, 12): a.alu(13, reg, 0, 0)
    a.alu(13, 11, 0, 29)
    a.label("visible"); a.literal(0, 0x04000006); a.halfword(1, 0, load=True)
    a.alu(10, 0, 1, 160, flags=True); a.branch("visible", 2)
    a.label("blank"); a.halfword(1, 0, load=True); a.alu(10, 0, 1, 160, flags=True); a.branch("blank", 3)
    a.alu(4, 4, 4, 1)
    a.literal(0, 0x04000130); a.halfword(5, 0, load=True)
    a.emit(0xE1E05005); a.literal(1, 0x3FF); a.emit(0xE0055001)  # ~KEYINPUT & 1023.
    a.alu(8, 0, 6, 1, flags=True); a.branch("was_down", 1)
    a.alu(8, 0, 5, 1, flags=True); a.branch("key_done", 0)
    a.alu(4, 7, 7, 1); a.emit(0xE1A09004); a.branch("key_done")
    a.label("was_down"); a.alu(8, 0, 5, 1, flags=True); a.branch("key_done", 1); a.alu(4, 8, 8, 1)
    a.label("key_done"); a.emit(0xE1A06005)
    a.alu(4, 11, 11, 1); a.alu(10, 0, 11, 30, flags=True); a.branch("not_beat", 1)
    a.alu(13, 11, 0, 0); a.alu(4, 10, 10, 1)
    a.literal(0, 0x04000062); a.literal(1, 0xF080); a.halfword(1, 0)
    a.literal(1, 0x8000 | 1984); a.halfword(1, 0, 2)
    a.label("not_beat"); a.alu(10, 0, 11, 2, flags=True)
    a.literal(0, 0x04000062, condition=0); a.alu(13, 1, 0, 0, condition=0); a.halfword(1, 0, condition=0)
    # Six live 16-bit fields and a fixed signature. Four scanlines per barcode;
    # 3584 small VRAM stores per VBlank, not a full-screen repaint/poll loop.
    for row, reg in enumerate((4, 7, 8, 5, 9, 10, None)):
        if reg is None: a.literal(1, SIGNATURE)
        else: a.emit(0xE1A01000 | reg)
        a.literal(0, 0x06000000 + ((16 + row * 16) * 240 + 16) * 2)
        a.alu(13, 2, 0, 16); a.label("bits_" + str(row))
        a.alu(8, 0, 1, 1, flags=True); a.literal(3, 0x7FFF, condition=1); a.alu(13, 3, 0, 0, condition=0)
        a.alu(13, 12, 0, 4); a.label("rows_" + str(row))
        for x in range(8): a.halfword(3, 0, x * 2)
        a.alu(4, 0, 0, 480); a.alu(2, 12, 12, 1, flags=True); a.branch("rows_" + str(row), 1)
        a.alu(2, 0, 0, 1904); a.emit(0xE1A010A1)  # LSR r1,#1.
        a.alu(2, 2, 2, 1, flags=True); a.branch("bits_" + str(row), 1)
    a.branch("visible")
    code = a.finish()
    if len(code) >= 0x1F00: raise ValueError("Keep program and original header separate")
    rom = bytearray(65536); struct.pack_into("<I", rom, 0, 0xEA00003E)
    rom[0xA0:0xAC] = b"MX REALTIME "; rom[0xAC:0xB0] = b"MXRT"; rom[0xB0:0xB2] = b"00"; rom[0xB2] = 0x96
    rom[0xBD] = (-sum(rom[0xA0:0xBD]) - 0x19) & 255
    rom[0x100:0x100 + len(code)] = code
    return bytes(rom)


def contract() -> dict:
    return {"width": 240, "height": 160, "fields": list(FIELDS), "bits": 16,
            "bitOrder": "little-endian-left-to-right", "sampleX": [19 + 8 * b for b in range(16)],
            "sampleY": [17 + 16 * r for r in range(7)], "signature": SIGNATURE,
            "beatPeriodGuestFrames": 30, "pilotHz": 512, "tickHz": 2048,
            "tickLengthGuestFrames": 2, "left": "continuous-pilot", "right": "metronome",
            "inputCounted": "A rising and falling edges sampled by the real guest at VBlank",
            "manufacturerLogoIncluded": False, "biosIncluded": False}


def self_test() -> dict:
    raw = diagnostic_rom()
    assert len(raw) == 65536 and raw == diagnostic_rom()
    assert raw[0x100:] != bytes(len(raw) - 0x100) and not any(raw[4:0xA0])
    assert (sum(raw[0xA0:0xBE]) + 0x19) & 255 == 0
    assert len(contract()["sampleX"]) == 16 and len(contract()["sampleY"]) == 7
    assert max(contract()["sampleX"]) < 240 and max(contract()["sampleY"]) < 160
    return {"pureChecks": 6, "romSha256": hashlib.sha256(raw).hexdigest(), "nativeRun": False}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", required=True)
    parser.add_argument("--product", type=Path)
    parser.add_argument("--expected-product-sha256", default=PUBLISHED_SHA256)
    parser.add_argument("--seconds", type=int, default=20)
    parser.add_argument("--generate-only", action="store_true")
    args = parser.parse_args()
    if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", args.name) or not 3 <= args.seconds <= 120:
        raise ValueError("Use a fresh simple name and a bounded 3..120 second measurement")
    if any(os.environ.get(k) for k in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")):
        raise ValueError("Inherited Java options would change this baseline")
    out = ROOT / "build" / args.name; out.mkdir(exist_ok=False)
    raw = diagnostic_rom(); rom = out / "original-realtime.gba"; rom.write_bytes(raw)
    report = {"schema": 1, **self_test(), "romBytes": len(raw), "contract": contract(), "minecraftStarted": False,
              "audioDeviceRequested": False, "scope": "Real paced ordinary mGBA worker, generated PCM and actual decoded native video; not OS input or physical display/speaker latency.",
              "sourceSHA256": {p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in (Path(__file__), ROOT / "tools/gba_diagnostic.py", ROOT / "tests/runtime/GbaRealtimeProbe.java")}}
    if args.generate_only:
        (out / "inputs.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8"); print(json.dumps(report)); return
    product = args.product.resolve(strict=True) if args.product else None
    if product is None or not product.is_relative_to(ROOT / "build"):
        raise ValueError("An exact existing repository candidate is required")
    h = hashlib.sha256()
    with product.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""): h.update(block)
    if h.hexdigest() != args.expected_product_sha256: raise ValueError("Candidate differs from the explicit baseline pin")
    report["product"] = {"path": str(product), "sha256": h.hexdigest()}
    extracted = {}
    with zipfile.ZipFile(product) as jar:
        hashes = json.loads(jar.read("META-INF/muxi/extract-hashes.json")); sizes = json.loads(jar.read("META-INF/muxi/extract-sizes.json"))
        for name in ("worker/runtime.jar", "worker/jna-5.14.0.jar", "cores/windows-x86_64/mgba.dll"):
            data = jar.read(name)
            if len(data) != sizes[name] or hashlib.sha256(data).hexdigest() != hashes[name]: raise ValueError("Actual candidate extraction differs")
            target = out / Path(name).name; target.write_bytes(data); extracted[name] = target
    report["extracted"] = {name: {"sha256": hashlib.sha256(p.read_bytes()).hexdigest(), "bytes": p.stat().st_size} for name, p in extracted.items()}
    classes = out / "classes"; classes.mkdir(); jdk = ROOT.parent / "test-environment/toolchains/java-21/bin"
    cp = os.pathsep.join(map(str, (classes, extracted["worker/runtime.jar"], extracted["worker/jna-5.14.0.jar"])))
    compile_cmd = [str(jdk / "javac.exe"), "-J-Xmx256m", "-J-XX:ActiveProcessorCount=1", "--release", "21", "-encoding", "UTF-8", "-cp", cp, "-d", str(classes), str(ROOT / "tests/runtime/GbaRealtimeProbe.java")]
    compiled = subprocess.run(compile_cmd, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=60)
    (out / "compile.log").write_text(compiled.stdout + compiled.stderr, encoding="utf-8")
    if compiled.returncode: raise RuntimeError("Independent probe compilation failed; log retained")
    command = [str(jdk / "java.exe"), "-Xmx256m", "-XX:ActiveProcessorCount=1", "-cp", cp,
               "net.muxigame.console.tests.runtime.GbaRealtimeProbe", str(jdk / "java.exe"), cp,
               str(extracted["cores/windows-x86_64/mgba.dll"]), str(rom), str(out), str(args.seconds)]
    (out / "inputs.json").write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=args.seconds + 45)
    (out / "native.log").write_text(result.stdout + result.stderr, encoding="utf-8")
    print(result.stdout, result.stderr)
    if result.returncode: raise RuntimeError("Actual paced baseline failed; original result/log retained")


if __name__ == "__main__":
    main()
