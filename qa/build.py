"""Build separate, disposable server/client QA mods from this repository's own sources."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import zipfile

REPO = Path(__file__).resolve().parents[1]
WORKSPACE = REPO.parent
sys.path.insert(0, str(WORKSPACE / "better-mc-remake/scripts"))
import local_mc_runtime as sdk


def sha(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(32768), b""):
            digest.update(block)
    return digest.hexdigest()


def build(name: str, product: Path, rom: Path) -> Path:
    if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", name):
        raise ValueError("Use a new lowercase QA build name")
    product, rom = product.resolve(strict=True), rom.resolve(strict=True)
    if REPO / "build" not in product.parents or REPO / "build/runtime-smoke" not in rom.parents:
        raise ValueError("QA inputs must be the new repository's product and original runtime diagnostic")
    if any(os.environ.get(key) for key in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")):
        raise ValueError("Inherited Java options are not accepted")
    with zipfile.ZipFile(product) as jar:
        if "net/muxigame/console/MuxiConsole.class" not in jar.namelist():
            raise ValueError("Missing independent product entrypoint")
    if not rom.read_bytes().startswith(b"NES\x1a"):
        raise ValueError("Expected our newly authored iNES diagnostic")
    out = REPO / "build/qa" / name
    out.mkdir(parents=True, exist_ok=False)
    game = WORKSPACE / "test-environment/resources/client/game"
    server = WORKSPACE / "minecraft/release/server"
    jdk = WORKSPACE / "test-environment/toolchains/java-21/bin"
    metadata = sdk.client_metadata(game, "BatterMC5Remake")
    classpath = str(product) + os.pathsep + sdk.javac_classpath(server, game, "21.1.250", sdk.client_libraries(game, "BatterMC5Remake", metadata))
    sources = REPO / "qa/java/net/muxigame/consoleqa"
    pins = {"product.sha256": sha(product), "rom.sha256": sha(rom)}
    manifest = {"schema": 1, "product": {"path": str(product), "sha256": pins["product.sha256"]},
                "diagnosticRom": {"path": str(rom), "sha256": pins["rom.sha256"]}, "artifacts": {},
                "minecraftTested": False, "physicalOSInput": False, "productionPublished": False}
    input_hashes = {str(path.relative_to(REPO)): sha(path) for path in sources.glob("*.java")}
    for side, entry in (("server", "ServerQaMod.java"), ("client", "ClientQaMod.java")):
        classes = out / (side + "-classes")
        classes.mkdir()
        args = ["--release", "21", "-encoding", "UTF-8", "-proc:none", "-implicit:none", "-cp", classpath, "-d", str(classes),
                str(sources / "QaOwner.java"), str(sources / entry)]
        argument_file = out / (side + ".args")
        sdk.argfile(argument_file, args)
        command = [str(jdk / "javac.exe"), "-J-Xmx512m", "-J-XX:ActiveProcessorCount=1", "-J-Dfile.encoding=UTF-8", "-J-Duser.language=en", "@" + str(argument_file)]
        compiled = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=120, cwd=REPO)
        (out / (side + ".log")).write_text(compiled.stdout + compiled.stderr, encoding="utf-8")
        if compiled.returncode:
            raise RuntimeError(side + " QA compilation failed: " + compiled.stderr[-5000:])
        mod_id = "muxi_console_qa_" + side
        resources = {path.relative_to(classes).as_posix(): path.read_bytes() for path in classes.rglob("*.class")}
        if any(not path.startswith("net/muxigame/consoleqa/") for path in resources):
            raise RuntimeError("QA must never contain product classes")
        resources["console-qa-pins.properties"] = "".join(key + "=" + value + "\n" for key, value in pins.items()).encode("ascii")
        resources["diagnostics/two-port-battery.nes"] = rom.read_bytes()
        resources["META-INF/neoforge.mods.toml"] = (f'modLoader="javafml"\nloaderVersion="[4,)"\nlicense="Private test fixture; not a product"\n'
                f'[[mods]]\nmodId="{mod_id}"\nversion="0.1.0"\ndisplayName="Muxi Console {side} QA"\n'
                f'[[dependencies.{mod_id}]]\nmodId="muxi_console"\ntype="required"\nversionRange="[0.1.0,)"\nordering="AFTER"\nside="BOTH"\n').encode("utf-8")
        artifact = out / ("muxi-console-qa-" + side + ".jar")
        with zipfile.ZipFile(artifact, "w", zipfile.ZIP_DEFLATED) as jar:
            for path, body in sorted(resources.items()):
                info = zipfile.ZipInfo(path, (2026, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                jar.writestr(info, body)
        manifest["artifacts"][side] = {"path": str(artifact), "sha256": sha(artifact), "modId": mod_id}
    if sha(product) != pins["product.sha256"] or sha(rom) != pins["rom.sha256"] or any(sha(REPO / path) != digest for path, digest in input_hashes.items()):
        raise RuntimeError("QA inputs changed during compilation")
    manifest["sources"] = input_hashes
    report = out / "qa-build.json"
    report.write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps({"qaBuild": str(report), "artifacts": manifest["artifacts"], "minecraftTested": False}))
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", required=True)
    parser.add_argument("--product", type=Path, required=True)
    parser.add_argument("--rom", type=Path, default=REPO / "build/runtime-smoke/two-port-battery.nes")
    options = parser.parse_args()
    build(options.name, options.product, options.rom)
