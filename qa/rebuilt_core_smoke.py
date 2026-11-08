"""Exact MD/PCE rebuild smoke using authored ROMs and existing warm/cold worker tests; no Minecraft."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
from platform_smoke_software import GENERATORS

PINS = {
    "md": {"core": "blastem", "dll": "f527a808ece895df2c6088b6978a4d7957aaaa2cd35490b6d67062802b1580f2", "bytes": 10126336,
           "commit": "ab743653ed110eeb7b1fe64d00cc5f9279c2bfec", "source": "52e4794b68818dc96798209d22f80d8075653c4e225207867a563c378bc26315",
           "rom": "1ebefd7dd962c75838162002d26bd5a56da77278b17f88f0d266a75358f622dc"},
    "pce": {"core": "pce", "dll": "20c919696cefac5b6ed6a452791e7154f010e272aaeeb0236252f0d5eda9dbcb", "bytes": 4010496,
            "commit": "2de1ce1c3f237b161c126c54ba407990bb86d1e9", "source": "fcabbd62b4a54caae01e7cc46aa0ffac6468337a5ed90b29673f7bc30affb7b3",
            "rom": "523d25514828f71eae422d5af9a3b866d6e9a8cde6a28b6cd17bddf4aeaac5f5"}}
WORKER_SHA = "99012c1f6043fa9520cda82f14f6d297405cfd1c293446956f7c0eb6fb17ae3a"


def sha(path):
    value = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024**2), b""): value.update(block)
    return value.hexdigest()


def read(path):
    if not path.is_file() or path.stat().st_size > 256 * 1024: raise ValueError("Missing/oversize rebuild metadata: " + str(path))
    return json.loads(path.read_text(encoding="utf-8"))


def describe(path): return {"path": str(path), "bytes": path.stat().st_size, "sha256": sha(path)}


def run(name, reports, worker_report, execute):
    if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", name): raise ValueError("New simple output name required")
    output = ROOT / "build" / name
    if output.exists(): raise ValueError("Preserve the old native output; choose a new name")
    worker_report = worker_report.resolve(strict=True); worker = read(worker_report)
    runtime = Path(worker["artifact"]).resolve(strict=True)
    if sha(runtime) != worker["sha256"] or worker["sha256"] != WORKER_SHA: raise ValueError("Expected exact independently rebuilt current worker")
    dependencies = []
    for row in worker["dependencies"]:
        jar = Path(row["cachePath"]).resolve(strict=True)
        if jar.stat().st_size != row["bytes"] or sha(jar) != row["sha256"]: raise ValueError("Worker dependency input drift")
        dependencies.append(jar)
    if len(dependencies) != 7: raise ValueError("Expected seven pinned official worker dependencies")
    sources = [ROOT / "tests/runtime/PlatformSmoke.java", ROOT / "tests/runtime/CoreColdLoadSmoke.java"]
    source_pins = {str(p): sha(p) for p in sources}
    generator = ROOT / "tools/platform_smoke_software.py"
    inputs = {}
    for platform, supplied in reports.items():
        path = supplied.resolve(strict=True); record = read(path); pin = PINS[platform]
        dll = Path(record["library"]["path"]).resolve(strict=True); source = Path(record["sourceArchive"]["path"]).resolve(strict=True)
        if record["core"] != pin["core"] or record["sourceCommit"] != pin["commit"] or record["library"]["sha256"] != pin["dll"] or sha(dll) != pin["dll"] or dll.stat().st_size != pin["bytes"] or record["sourceArchive"]["sha256"] != pin["source"] or sha(source) != pin["source"]:
            raise ValueError("Frozen new DLL/corresponding-source identity differs: " + platform)
        raw = GENERATORS[platform]()
        if hashlib.sha256(raw).hexdigest() != pin["rom"] or (ROOT / "build/platform-smoke" / ("original." + platform)).read_bytes() != raw:
            raise ValueError("Authored diagnostic differs from the validated original")
        inputs[platform] = {"report": describe(path), "library": describe(dll), "sourceArchive": describe(source), "sourceCommit": record["sourceCommit"], "repository": record["repository"], "romSha256": pin["rom"], "romBytes": len(raw)}
    plan = {"name": name, "execute": execute, "minecraftStarted": False, "driver": describe(Path(__file__).resolve()), "generator": describe(generator),
            "workerReport": describe(worker_report), "runtime": describe(runtime), "testSources": source_pins, "cores": inputs,
            "scope": "Each DLL: PlatformSmoke real video/input/serialization/warm cross-process recovery plus CoreColdLoadSmoke zero-public-step fresh restore and all normal worker/native cleanup"}
    if not execute: return plan
    if any(os.environ.get(v) for v in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")): raise ValueError("Unexpected Java options")
    java = Path(worker["javaHome"]) / "bin/java.exe"; compiler = java.with_name("javac.exe")
    output.mkdir(); classes = output / "classes"; classes.mkdir()
    cp = os.pathsep.join(map(str, [classes, runtime, *dependencies]))
    args = ["--release", "21", "-encoding", "UTF-8", "-proc:none", "-implicit:none", "-cp", cp, "-d", str(classes), *map(str, sources)]
    argfile = output / "compile.args"; argfile.write_text("\n".join('"' + str(v).replace("\\", "/").replace('"', '\\"') + '"' for v in args), encoding="utf-8")
    command = [str(compiler), "-J-Xmx512m", "-J-XX:ActiveProcessorCount=1", "@" + str(argfile)]
    result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=180, cwd=ROOT)
    (output / "compile.log").write_text(result.stdout + result.stderr, encoding="utf-8")
    if result.returncode: raise RuntimeError("Narrow Java driver compilation failed")
    plan["results"] = []; plan["allPassed"] = False
    try:
        for platform, core in inputs.items():
            folder = output / platform; folder.mkdir(); content = folder / ("original." + platform); content.write_bytes(GENERATORS[platform]())
            for label, entry in (("platform", "PlatformSmoke"), ("cold", "CoreColdLoadSmoke")):
                phase = folder / label; phase.mkdir(); system = phase / "system"; system.mkdir()
                command = [str(java), "-Xmx256m", "-XX:ActiveProcessorCount=1", "-cp", cp, "net.muxigame.console.runtime." + entry,
                           str(java), cp, core["library"]["path"], str(content), str(phase), platform, str(system), "120"]
                result = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=180, cwd=ROOT)
                (phase / "stdout.log").write_text(result.stdout, encoding="utf-8"); (phase / "stderr.log").write_text(result.stderr, encoding="utf-8")
                pattern = r"platform checks passed: (\d+)" if label == "platform" else r"COLD_LOAD_CHECKS (\d+)"
                match = re.search(pattern, result.stdout); checks = int(match.group(1)) if match else 0
                row = {"platform": platform, "phase": label, "command": command, "exitCode": result.returncode, "checks": checks,
                       "coreSha256": sha(Path(core["library"]["path"])), "sourceArchiveSha256": sha(Path(core["sourceArchive"]["path"])), "stdoutSha256": sha(phase / "stdout.log"), "stderrSha256": sha(phase / "stderr.log")}
                plan["results"].append(row)
                print(platform, label, "exit", result.returncode, "checks", checks, flush=True)
                if result.returncode or not checks or row["coreSha256"] != PINS[platform]["dll"] or row["sourceArchiveSha256"] != PINS[platform]["source"]: raise RuntimeError("Rebuilt core native phase failed; original logs retained")
        if any(sha(Path(p)) != h for p, h in source_pins.items()) or sha(generator) != plan["generator"]["sha256"] or sha(Path(__file__)) != plan["driver"]["sha256"]: raise RuntimeError("Native validation source drift")
        plan["allPassed"] = True
    finally:
        (output / "result.json").write_text(json.dumps(plan, indent=2), encoding="utf-8")
    return {"allPassed": plan["allPassed"], "checks": sum(row["checks"] for row in plan["results"]), "result": str(output / "result.json")}


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--name", required=True); p.add_argument("--md-report", type=Path, required=True); p.add_argument("--pce-report", type=Path, required=True)
    p.add_argument("--worker-report", type=Path, default=ROOT / "build/public-worker-01/build.json"); p.add_argument("--execute", action="store_true")
    a = p.parse_args(); print(json.dumps(run(a.name, {"md": a.md_report, "pce": a.pce_report}, a.worker_report, a.execute), indent=2))
