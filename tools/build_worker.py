"""Build only our Java21 worker from public dependencies, without a Minecraft/workspace SDK."""
from __future__ import annotations

import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import urllib.request
import zipfile
import zlib

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "common/src/main/java/net/muxigame/console/runtime"
MANIFEST = ROOT / "third-party/worker-dependencies.json"
JNA = {"file": "jna-5.14.0.jar", "artifact": "net.java.dev.jna:jna:5.14.0",
       "url": "https://repo.maven.apache.org/maven2/net/java/dev/jna/jna/5.14.0/jna-5.14.0.jar",
       "bytes": 1878533, "sha256": "34ed1e1f27fa896bca50dbc4e99cf3732967cec387a7a0d5e3486c09673fe8c6"}
FLAGS = ["--release", "21", "-encoding", "UTF-8", "-proc:none", "-implicit:none"]
JVM_FLAGS = ["-J-Xmx512m", "-J-XX:ActiveProcessorCount=1", "-J-Duser.language=en", "-J-Duser.country=US",
             "-J-Dfile.encoding=UTF-8", "-J-Dsun.stdout.encoding=UTF-8", "-J-Dsun.stderr.encoding=UTF-8"]


def sha(path):
    result = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""): result.update(block)
    return result.hexdigest()


def regular(path):
    if not path.is_file() or path.is_symlink() or getattr(path.lstat(), "st_file_attributes", 0) & 0x400:
        raise ValueError("Expected an ordinary file: " + str(path))


def dependency_cache(supplied):
    workspace = ROOT.parent
    canonical = workspace / "test-environment/cache/muxi-console/dependencies"
    if (workspace / "projects.json").is_file() and (workspace / "test-environment").is_dir():
        if supplied is not None and supplied.absolute().resolve() != canonical.resolve():
            raise ValueError("This workspace uses only test-environment/cache/muxi-console/dependencies")
        cache = canonical
    else:
        if supplied is None: raise ValueError("For an independent clone, provide --cache <dependency-cache-directory>")
        cache = supplied.absolute()
    cache.mkdir(parents=True, exist_ok=True)
    if cache.absolute() != cache.resolve() or cache.is_symlink() or getattr(cache.lstat(), "st_file_attributes", 0) & 0x400:
        raise ValueError("Dependency cache must not be redirected")
    return cache


def dependencies():
    value = json.loads(MANIFEST.read_text(encoding="utf-8"))
    rows = [JNA, *value["dependencies"]]
    names = {"jna-5.14.0.jar", *{f"{module}-3.3.3{suffix}.jar" for module in ("lwjgl", "lwjgl-glfw", "lwjgl-opengl") for suffix in ("", "-natives-windows")}}
    if len(rows) != 7 or {row["file"] for row in rows} != names: raise ValueError("Expected pinned JNA5.14.0 and six LWJGL3.3.3 dependencies")
    for row in rows:
        if Path(row["file"]).name != row["file"] or not row["url"].startswith("https://repo.maven.apache.org/maven2/") or not re.fullmatch("[a-f0-9]{64}", row["sha256"]) or type(row["bytes"]) is not int or not 0 < row["bytes"] < 10 * 1024**2:
            raise ValueError("Invalid public dependency pin")
    return rows


def obtain(cache, row, offline):
    destination = cache / row["file"]
    def matches(path):
        regular(path)
        return path.stat().st_size == row["bytes"] and sha(path) == row["sha256"]
    if destination.exists():
        if not matches(destination): raise ValueError("Cached bytes differ; retained without overwrite: " + str(destination))
        return destination
    if offline: raise FileNotFoundError("Pinned dependency is not cached: " + str(destination))
    temporary = None
    try:
        request = urllib.request.Request(row["url"], headers={"User-Agent": "MuxiConsolePublicWorkerBuild/1", "Accept-Encoding": "identity"})
        with urllib.request.urlopen(request, timeout=60) as response:
            if response.geturl() != row["url"] or response.status != 200: raise ValueError("Unexpected Maven response/redirect")
            if response.headers.get("Content-Length") and int(response.headers["Content-Length"]) != row["bytes"]: raise ValueError("Unexpected Maven artifact size")
            with tempfile.NamedTemporaryFile(mode="wb", dir=cache, prefix=row["file"] + ".", suffix=".part", delete=False) as output:
                temporary = Path(output.name); total = 0
                while block := response.read(65536):
                    total += len(block)
                    if total > row["bytes"]: raise ValueError("Maven artifact exceeds its exact size")
                    output.write(block)
        if not matches(temporary): raise ValueError("Maven artifact failed size/SHA256 validation")
        if destination.exists():
            if not matches(destination): raise ValueError("Concurrent dependency cache input differs")
        else: os.replace(temporary, destination)
        if not matches(destination): raise ValueError("Dependency changed after cache placement")
        return destination
    finally:
        if temporary is not None and temporary.exists(): temporary.unlink()


def compare(reference, artifact, rows):
    with zipfile.ZipFile(reference) as package:
        previous = package.read("worker/runtime.jar")
        hashes = json.loads(package.read("META-INF/muxi/extract-hashes.json"))
        sizes = json.loads(package.read("META-INF/muxi/extract-sizes.json"))
        for row in rows:
            name = "worker/" + row["file"]
            if hashes[name] != row["sha256"] or sizes[name] != row["bytes"]: raise ValueError("Dependency pin differs from the candidate: " + name)
            body = package.read(name)
            if len(body) != row["bytes"] or hashlib.sha256(body).hexdigest() != row["sha256"]: raise ValueError("Candidate embedded dependency differs from its extraction manifest")
        if len(previous) != sizes["worker/runtime.jar"] or hashlib.sha256(previous).hexdigest() != hashes["worker/runtime.jar"]: raise ValueError("Reference worker differs from its extraction manifest")
    with zipfile.ZipFile(io.BytesIO(previous)) as old, zipfile.ZipFile(artifact) as new:
        a, b = set(old.namelist()), set(new.namelist())
        changed = sorted(n for n in a & b if old.read(n) != new.read(n))
    return {"reference": str(reference), "referenceSha256": sha(reference), "workerSha256": hashes["worker/runtime.jar"],
            "sameClasses": not changed and a == b, "classes": len(b), "changed": changed, "missing": sorted(a-b), "extra": sorted(b-a),
            "byteIdentical": artifact.read_bytes() == previous}


def build(name, java_home, cache, offline=False, reference=None):
    if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", name): raise ValueError("Choose a simple new lowercase build name")
    out = ROOT / "build" / name
    if out.exists(): raise ValueError("Build already exists; preserve it and use a new name")
    if any(os.environ.get(v) for v in ("JAVA_TOOL_OPTIONS", "_JAVA_OPTIONS", "JDK_JAVA_OPTIONS")): raise ValueError("Unexpected inherited Java options")
    selected = java_home or os.environ.get("JAVA_HOME")
    if not selected: raise ValueError("Provide a Java21 JDK with --java-home or JAVA_HOME")
    jdk = Path(selected).resolve(strict=True)
    release_file = jdk / "release"; regular(release_file)
    release = release_file.read_text(encoding="utf-8")
    if not re.search(r'^JAVA_VERSION="21(?:[.\+"]|$)', release, re.M): raise ValueError("Java21 JDK required")
    compiler = jdk / "bin" / ("javac.exe" if os.name == "nt" else "javac"); regular(compiler)
    cache = dependency_cache(cache); rows = dependencies(); manifest_hash = sha(MANIFEST)
    jars = [obtain(cache, row, offline) for row in rows]
    files = sorted(SOURCE.glob("*.java"))
    if not files: raise ValueError("Runtime Java sources are missing")
    inputs = {p.relative_to(ROOT).as_posix(): sha(p) for p in files}
    out.mkdir(parents=True); classes = out / "classes"; classes.mkdir()
    args = [*FLAGS, "-cp", os.pathsep.join(map(str, jars)), "-d", str(classes), *map(str, files)]
    argfile = out / "compile.args"
    argfile.write_text("\n".join('"' + str(v).replace("\\", "/").replace('"', '\\"') + '"' for v in args), encoding="utf-8")
    command = [str(compiler), *JVM_FLAGS, "@" + str(argfile)]
    result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=180)
    (out / "compile.log").write_text(result.stdout + result.stderr, encoding="utf-8")
    if result.returncode: raise RuntimeError("Worker compilation failed; see " + str(out / "compile.log"))
    emitted = sorted(classes.rglob("*.class"))
    if any(not p.relative_to(classes).as_posix().startswith("net/muxigame/console/runtime/") for p in emitted): raise ValueError("Unexpected non-worker output class")
    artifact = out / "runtime.jar"
    with zipfile.ZipFile(artifact, "w", zipfile.ZIP_DEFLATED) as bundle:
        for path in emitted:
            info = zipfile.ZipInfo(path.relative_to(classes).as_posix(), (2026, 1, 1, 0, 0, 0))
            info.create_system = 0; info.compress_type = zipfile.ZIP_DEFLATED
            bundle.writestr(info, path.read_bytes())
    if sha(MANIFEST) != manifest_hash or any(sha(ROOT / p) != h for p, h in inputs.items()) or any(p.stat().st_size != row["bytes"] or sha(p) != row["sha256"] for p, row in zip(jars, rows)):
        raise RuntimeError("Worker build input drift")
    report = {"artifact": str(artifact), "sha256": sha(artifact), "bytes": artifact.stat().st_size, "sources": inputs, "classes": len(emitted),
              "javaHome": str(jdk), "jdkRelease": release, "python": sys.version, "zlib": zlib.ZLIB_RUNTIME_VERSION,
              "compilerFlags": FLAGS, "compilerJvmFlags": JVM_FLAGS, "dependencyManifestSha256": manifest_hash,
              "dependencies": [{**row, "cachePath": str(path)} for row, path in zip(rows, jars)], "minecraftStarted": False, "workerStarted": False}
    if reference: report["comparison"] = compare(reference.resolve(strict=True), artifact, rows)
    (out / "build.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    if reference and not report["comparison"]["byteIdentical"]: raise RuntimeError("Worker differs from the reference; per-class differences are in build.json")
    return {k: report[k] for k in ("artifact", "sha256", "bytes", "classes")} | {"comparison": report.get("comparison"), "report": str(out / "build.json")}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", required=True); parser.add_argument("--java-home", type=Path); parser.add_argument("--cache", type=Path)
    parser.add_argument("--offline", action="store_true"); parser.add_argument("--compare", type=Path, help="Optional existing product JAR; require exact embedded worker/dependency equality")
    args = parser.parse_args()
    print(json.dumps(build(args.name, args.java_home, args.cache, args.offline, args.compare), ensure_ascii=False))
