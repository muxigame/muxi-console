"""Independent platform worker diagnostics. Never starts Minecraft or fetches games/BIOS."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import struct
import subprocess
from platform_smoke_software import GENERATORS


def n64_diagnostic() -> bytes:
    """Original MIPS boot and VI pattern; no Nintendo IPL3, logo, SDK or BIOS bytes."""
    def lui(reg, value): return 0x3c000000 | reg << 16 | value
    def ori(dst, src, value): return 0x34000000 | src << 21 | dst << 16 | value
    def addiu(dst, src, value): return 0x24000000 | src << 21 | dst << 16 | (value & 65535)
    def sw(src, base, offset=0): return 0xac000000 | base << 21 | src << 16 | (offset & 65535)
    def sh(src, base, offset=0): return 0xa4000000 | base << 21 | src << 16 | (offset & 65535)
    def lw(dst, base, offset=0): return 0x8c000000 | base << 21 | dst << 16 | offset
    def bne(left, right, offset): return 0x14000000 | left << 21 | right << 16 | (offset & 65535)
    def words(values): return b"".join(struct.pack(">I", v) for v in values)
    # Copy our own executable out of cartridge memory into emulated RDRAM.
    boot = [lui(8, 0xb000), ori(8, 8, 0x1000), lui(9, 0x8000), ori(9, 9, 0x0400),
            lui(10, 0x8000), ori(10, 10, 0x1400), lw(11, 8), sw(11, 9),
            addiu(8, 8, 4), addiu(9, 9, 4), bne(9, 10, -5), 0,
            lui(29, 0x8040), lui(8, 0x8000), ori(8, 8, 0x0400), 8 << 21 | 8, 0]
    code = [lui(8, 0x3400), 0x40886000, 0, lui(8, 0xa440)]
    # Public VI register programming, NTSC 320x240 RGBA5551 framebuffer.
    for offset, value in enumerate((0x3202, 0x100000, 320, 2, 0, 0x3e52239, 525,
                                    0xc15, 0xc150c15, 0x6c02ec, 0x2501ff,
                                    0xe0204, 0x200, 0x400)):
        code.extend((lui(9, value >> 16), ori(9, 9, value & 65535), sw(9, 8, offset * 4)))
    code.extend((lui(8, 0xa010), lui(9, 0xa011), ori(9, 9, 0x2c00), ori(10, 0, 0xf801)))
    code.extend((sh(10, 8), addiu(8, 8, 2), bne(8, 9, -3), 0))
    code.extend((lui(9, 0xa012), ori(9, 9, 0x5800), ori(10, 0, 0x003f)))
    code.extend((sh(10, 8), addiu(8, 8, 2), bne(8, 9, -3), 0))
    # Poll four controllers through the public SI/PIF hardware registers. The
    # command stream and MIPS instructions below are independently authored.
    controller_loop = len(code)
    pif = bytearray(64)
    for port in range(4): pif[port * 7:port * 7 + 3] = b"\x01\x04\x01"
    pif[28] = 0xfe; pif[63] = 1
    code.extend((lui(8, 0xa008), lui(11, 0xa480)))
    for offset in range(0, 64, 4):
        value = int.from_bytes(pif[offset:offset + 4], "big")
        code.extend((lui(9, value >> 16), ori(9, 9, value & 65535), sw(9, 8, offset)))
    for dma_register in (0x10, 0x04):
        code.extend((lui(9, 8), sw(9, 11), lui(9, 0x1fc0), ori(9, 9, 0x07c0), sw(9, 11, dma_register)))
        # Wait until both the SI DMA and IO read flags have cleared.
        code.extend((lw(9, 11, 0x18), 0x31290003, bne(9, 0, -3), 0, sw(0, 11, 0x18)))
    for port, start, end, normal, pressed in ((0, 0xa0100000, 0xa0112c00, 0xf801, 0x07c1),
                                              (1, 0xa0112c00, 0xa0125800, 0x003f, 0xffc1)):
        code.extend((0x91090000 | (3 + port * 7), 0x31290080, ori(10, 0, normal),
                     0x11200002, 0, ori(10, 0, pressed)))
        code.extend((lui(8, start >> 16), ori(8, 8, start & 65535),
                     lui(9, end >> 16), ori(9, 9, end & 65535)))
        code.extend((sh(10, 8), addiu(8, 8, 2), bne(8, 9, -3), 0, lui(8, 0xa008)))
    code.extend((0x08000000 | ((0x80000400 + controller_loop * 4) >> 2 & 0x3ffffff), 0))
    rom = bytearray(0x101000)
    rom[:16] = struct.pack(">4I", 0x80371240, 0xf, 0x80000400, 0x1444)
    rom[0x20:0x34] = b"MUXI ORIGINAL VI TEST"[:20].ljust(20, b" ")
    rom[0x3e] = 0x45
    rom[0x40:0x40 + len(boot) * 4] = words(boot)
    rom[0x1000:0x1000 + len(code) * 4] = words(code)
    assert len(rom) == 0x101000 and len(rom) % 4 == 0
    return bytes(rom)


def main():
    repo = Path(__file__).resolve().parents[1]; workspace = repo.parent
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--platform", choices=("n64", "arcade", "fds", "nes", "gba", "probe", *GENERATORS), required=True)
    parser.add_argument("--core", type=Path)
    parser.add_argument("--content", type=Path)
    parser.add_argument("--system", type=Path)
    parser.add_argument("--frames", type=int, help="Native frames before assertions; default 360 for GB/GBC boot animation, 120 otherwise")
    parser.add_argument("--close-sequence", choices=("open-only", "no-save", "save-only", "save-continue", "save-load", "fresh-load"), help="Run a focused N64 native shutdown path")
    parser.add_argument("--close-repeats", type=int, default=1)
    args = parser.parse_args()
    if args.frames is None: args.frames = 360 if args.platform in ("gb", "gbc") else 120
    output = repo / "build/platform-smoke"; output.mkdir(parents=True, exist_ok=True)
    libraries = workspace / "test-environment/resources/client/game/libraries"
    jna = libraries / "net/java/dev/jna/jna/5.14.0/jna-5.14.0.jar"
    jars = [jna]
    for module in ("lwjgl", "lwjgl-glfw", "lwjgl-opengl"):
        jars.extend(libraries / f"org/lwjgl/{module}/3.3.3/{module}-3.3.3{suffix}.jar" for suffix in ("", "-natives-windows"))
    java = workspace / "test-environment/toolchains/java-21/bin/java.exe"
    classes = output / "classes"; classes.mkdir(exist_ok=True)
    sources = sorted((repo / "common/src/main/java/net/muxigame/console/runtime").glob("*.java")) + sorted(
        path for path in (repo / "tests/runtime").glob("*.java") if path.name not in {"MediaLaunchSmoke.java", "MediaAdmissionChecks.java"})
    if args.platform == "gba": sources += sorted((repo / "domain/src/main/java").rglob("*.java"))
    subprocess.run([str(java.with_name("javac.exe")), "-J-Xmx512m", "-J-XX:ActiveProcessorCount=1", "--release", "21", "-cp", os.pathsep.join(map(str, jars)), "-d", str(classes), *map(str, sources)], check=True)
    classpath = os.pathsep.join(map(str, [classes, *jars]))
    cores = {"n64": "mupen64plus_next", "arcade": "mame", "fds": "fceumm", "nes": "fceumm", "gba": "mgba",
             "snes": "bsnes2014_performance", "md": "blastem", "sms": "gearsystem", "gg": "gearsystem", "sg1000": "gearsystem",
             "gb": "sameboy", "gbc": "sameboy", "a2600": "stella2014", "ws": "mednafen_wswan", "wsc": "mednafen_wswan", "pce": "mednafen_pce_fast", "a7800":"prosystem",
             "ngp":"mednafen_ngp", "ngpc":"mednafen_ngp", "vb":"mednafen_vb", "lynx":"mednafen_lynx"}
    core = args.core or workspace / f"test-environment/cache/muxi-console/libretro/{cores.get(args.platform, '2048')}_libretro.dll"
    content = args.content
    if not content and args.platform in GENERATORS:
        extension = {"snes":"sfc", "sg1000":"sg", "a2600":"a26", "a7800":"a78", "lynx":"o"}.get(args.platform,args.platform)
        content = output / ("original." + extension); content.write_bytes(GENERATORS[args.platform]())
        if args.platform == "lynx" and not args.system:
            from platform_smoke_software import lynx_diagnostic_firmware
            args.system = output / "original-lynx-firmware"; args.system.mkdir(exist_ok=True)
            (args.system / "lynxboot.img").write_bytes(lynx_diagnostic_firmware())
    if not content and args.platform == "n64":
        content = output / "original-vi.z64"; content.write_bytes(n64_diagnostic())
    if not content and args.platform == "arcade":
        # MAME's own Pong discrete-circuit driver contains no game ROM set.
        content = output / "pong.cmd"; content.write_text("pong -skip_gameinfo -nothrottle -sound none", encoding="ascii")
    if not content and args.platform == "fds":
        from platform_smoke_fds import diagnostic_disk, diagnostic_firmware
        content = output / "original-transfer.fds"; content.write_bytes(diagnostic_disk())
        if not args.system:
            args.system = output / "original-fds-firmware"; args.system.mkdir(exist_ok=True)
            (args.system / "disksys.rom").write_bytes(diagnostic_firmware())
    if not content and args.platform in ("nes", "gba"):
        if args.platform == "nes":
            from runtime_smoke import diagnostic_rom
        else:
            from gba_diagnostic import diagnostic_rom
        content = output / ("regression." + args.platform); content.write_bytes(diagnostic_rom())
    command = [str(java), "-Xmx256m", "-XX:ActiveProcessorCount=1", "-Dmuxi.console.workerDiagnostics=true", "-cp", classpath,
               "net.muxigame.console.runtime.PlatformSmoke", str(java), classpath, str(core.resolve()),
               str(content.resolve()) if content else "", str(output), args.platform,
               str(args.system.resolve() if args.system else output / "system"), str(args.frames)]
    if args.platform in ("nes", "gba"):
        command = command[:command.index("net.muxigame.console.runtime.PlatformSmoke")] + [
            "net.muxigame.console.runtime." + ("RuntimeSmoke" if args.platform == "nes" else "GbaSmoke"),
            str(java), classpath, str(core.resolve()),
            *([str(output), str(content)] if args.platform == "nes" else [str(content), str(output)])]
    if args.close_sequence:
        if args.platform != "n64" or not 1 <= args.close_repeats <= 20: raise ValueError("Close-sequence testing requires N64 and 1..20 repeats")
        command = command[:command.index("net.muxigame.console.runtime.PlatformSmoke")] + [
            "net.muxigame.console.runtime.N64CloseSmoke", str(java), classpath, str(core.resolve()), str(content.resolve()), str(output), args.close_sequence, str(args.close_repeats)]
    result = subprocess.run(command, capture_output=True, text=True, timeout=300)
    record = {"platform": args.platform, "coreSha256": hashlib.sha256(core.read_bytes()).hexdigest(),
              "contentSha256": hashlib.sha256(content.read_bytes()).hexdigest() if content else None,
              "exitCode": result.returncode, "stdout": result.stdout, "stderr": result.stderr}
    result_name = f"{args.platform}-close-{args.close_sequence}-result.json" if args.close_sequence else f"{args.platform}-result.json"
    (output / result_name).write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding="utf-8")
    print(result.stdout, end=""); print(result.stderr, end="")
    raise SystemExit(result.returncode)


if __name__ == "__main__": main()
