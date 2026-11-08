"""Prepare exact upstream source snapshots/notices. No binary pin changes or release-ready override."""
from __future__ import annotations
import argparse
import concurrent.futures
import configparser
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import tarfile
from urllib.parse import urljoin
from urllib.request import Request, urlopen
import zipfile

ROOT = Path(__file__).resolve().parents[1]
CACHE = ROOT.parent / "test-environment/cache/muxi-console/third-party-source"
RESOLVED = {"mupen64plus_next": "15bada85906593376e8fd697f1b0b3af3a20f512", "mame": "5f5a07748ce5c239c4118463ce8e6473743d4859"}
LICENSE_NAME = re.compile(r"^(?:(?:LICEN[CS]E|COPYING|COPYRIGHT|NOTICE)(?:[._-].*)?|.*[_-]licen[cs]e(?:[._-].*)?)$", re.I)
MANIFEST = ROOT / "third-party/source_manifest.json"
DELIVERY = CACHE / "source-delivery.json"
REBUILT = {"blastem": "rebuild-blastem", "mednafen_pce_fast": "rebuild-pce"}
JNA = {"file": "jna-5.14.0.jar", "artifact": "net.java.dev.jna:jna:5.14.0", "url": "https://repo.maven.apache.org/maven2/net/java/dev/jna/jna/5.14.0/jna-5.14.0.jar", "bytes": 1878533, "sha256": "34ed1e1f27fa896bca50dbc4e99cf3732967cec387a7a0d5e3486c09673fe8c6"}


def digest(path):
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""): h.update(block)
    return h.hexdigest()


def download(url, target, maximum=512 * 1024 * 1024):
    target.parent.mkdir(parents=True, exist_ok=True)
    provenance = target.with_name(target.name + ".origin.json")
    if target.is_file() and provenance.is_file():
        old = json.loads(provenance.read_text(encoding="utf-8"))
        if old["url"] == url and digest(target) == old["sha256"]: return old
        raise ValueError("Existing source cache identity differs: " + str(target))
    temporary = target.with_name(target.name + ".part")
    if temporary.exists(): raise ValueError("Preserve an unfinished source download: " + str(temporary))
    try:
        request = Request(url, headers={"User-Agent": "muxi-console-corresponding-source/1", "Accept": "*/*"})
        with urlopen(request, timeout=60) as response, temporary.open("xb") as f:
            size = 0
            while block := response.read(1024 * 1024):
                size += len(block)
                if size > maximum: raise ValueError("Source response exceeds its bounded size")
                f.write(block)
        temporary.replace(target)
        record = {"url": url, "sha256": digest(target), "bytes": target.stat().st_size}
        provenance.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
        return record
    finally:
        if temporary.exists(): temporary.unlink()


def api(url):
    name = hashlib.sha256(url.encode()).hexdigest() + ".json"
    target = CACHE / "api" / name
    download(url, target, 16 * 1024 * 1024)
    return json.loads(target.read_text(encoding="utf-8"))


def github(repo):
    match = re.fullmatch(r"https://github.com/([^/]+/[^/]+?)(?:\.git)?", repo)
    if not match: raise ValueError("Expected an explicit official GitHub source repository: " + repo)
    return match.group(1)


def safe_relative(value):
    path = PurePosixPath(value)
    if path.is_absolute() or ".." in path.parts or not path.parts: raise ValueError("Unsafe upstream source member")
    return path


def gitlink(slug, revision, path):
    """Only actual gitlinks need extra snapshots; vendored .gitmodules may be stale."""
    current = revision
    parts = safe_relative(path).parts
    for index, part in enumerate(parts):
        tree = api("https://api.github.com/repos/" + slug + "/git/trees/" + current)
        if tree.get("truncated"): raise ValueError("Truncated nonrecursive upstream tree")
        node = next((entry for entry in tree["tree"] if entry["path"] == part), None)
        if node is None: return None
        if index == len(parts) - 1: return node["sha"] if node["mode"] == "160000" else None
        if node["type"] != "tree": return None
        current = node["sha"]


def snapshot(component, repo, revision, active=()):
    slug = github(repo)
    if (slug, revision) in active: raise ValueError("Recursive upstream submodule cycle")
    if not re.fullmatch(r"[0-9a-f]{40}", revision): raise ValueError("Full immutable source commit required")
    name = component + "-" + revision + ".tar.gz"
    file = CACHE / "archives" / name
    source = download("https://codeload.github.com/" + slug + "/tar.gz/" + revision, file)
    result = {"repository": repo, "revision": revision, "archive": "archives/" + name, **source, "licenseFiles": [], "submodules": [], "nonGitlinkDeclarations": []}
    with tarfile.open(file, "r:gz") as tar:
        members = tar.getmembers(); roots = {PurePosixPath(m.name).parts[0] for m in members}
        if len(roots) != 1: raise ValueError("Expected a single source archive prefix")
        prefix = next(iter(roots)); modules = []
        for member in members:
            path = safe_relative(member.name); relative = PurePosixPath(*path.parts[1:])
            legal_directory = any(p.upper() == "LICENSES" for p in relative.parts[:-1]) or str(relative).startswith("docs/legal/")
            if member.isfile() and (LICENSE_NAME.fullmatch(path.name) or legal_directory):
                if member.size > 1024 * 1024: raise ValueError("Upstream license exceeds bounds")
                body = tar.extractfile(member).read()
                dest = ROOT / "third-party/licenses" / component / str(relative)
                dest.parent.mkdir(parents=True, exist_ok=True); dest.write_bytes(body)
                result["licenseFiles"].append(str(dest.relative_to(ROOT)).replace("\\", "/"))
            if member.isfile() and path.name == ".gitmodules":
                if member.size > 65536: raise ValueError("Oversized upstream submodule manifest")
                cfg = configparser.ConfigParser(); cfg.read_string(tar.extractfile(member).read().decode("utf-8"))
                for section in cfg.sections():
                    child_path = safe_relative(str(relative.parent / cfg[section]["path"]))
                    modules.append((str(child_path), cfg[section]["url"]))
        # Git archives omit gitlinks, including nested manifests inside a vendored source tree.
        for path, declared in modules:
            child_revision = gitlink(slug, revision, path)
            if child_revision is None:
                result["nonGitlinkDeclarations"].append({"path": path, "declaredRepository": declared, "reason": "not a gitlink in this exact parent tree; no omitted submodule source"})
                continue
            child_repo = urljoin(repo.rstrip("/") + "/", declared) if declared.startswith(".") else declared
            if child_repo in ("git://git.sv.gnu.org/gnulib.git", "git://git.savannah.gnu.org/gnulib.git"):
                # GNU's official coreutils mirror retains the exact git commit identity.
                child_repo = "https://github.com/coreutils/gnulib"
            child_component = component + "--" + re.sub(r"[^a-zA-Z0-9_.-]", "-", path)
            child = snapshot(child_component, child_repo.removesuffix(".git"), child_revision, (*active, (slug, revision)))
            child.update({"installPath": path, "declaredRepository": declared}); result["submodules"].append(child)
    return result


def flatten(source):
    yield source
    for child in source.get("submodules", []): yield from flatten(child)


def file_record(path, relative=None):
    return {"path": relative or path.relative_to(ROOT).as_posix(), "bytes": path.stat().st_size, "sha256": digest(path)}


def public_rebuild_report(report):
    """Keep original cache evidence private; publish only the build-relevant view."""
    workspace = ROOT.parent.as_posix()
    python = report.get("build", {}).get("variables", {}).get("PYTHON")
    def clean(value, key=""):
        if key == "PATH":
            return "${LLVM_MINGW_ROOT}/bin;${GNU_MAKE_ROOT}/bin;${GIT_ROOT}/usr/bin;${GIT_ROOT}/mingw64/bin;${PYTHON_DIRECTORY};${SYSTEM_PATH}"
        if isinstance(value, dict): return {k: clean(v, k) for k, v in value.items()}
        if isinstance(value, list): return [clean(v) for v in value]
        if isinstance(value, str):
            value = value.replace("\\", "/").replace(workspace, "${WORKSPACE}")
            if python: value = value.replace(python.replace("\\", "/"), "${PYTHON}")
        return value
    result = clean(report)
    result["publicView"] = "Host workspace paths use placeholders; inherited PATH entries unrelated to building are omitted. Original private cache record is retained separately."
    return result


def rebuilt_source(component, report_path):
    """Read a completed local rebuild, never infer correspondence for an old DLL."""
    private = json.loads(report_path.read_text(encoding="utf-8"))
    public_path = report_path.with_name("rebuild-public-report.json")
    report = json.loads(public_path.read_text(encoding="utf-8"))
    if report["sourceCommit"] != private["sourceCommit"] or report["library"]["sha256"] != private["library"]["sha256"]:
        raise ValueError("Public rebuild record differs from the original: " + component)
    archive = report["sourceArchive"]
    source_path = public_path.parent / safe_relative(archive["path"])
    if digest(source_path) != archive["sha256"] or source_path.stat().st_size != archive["size"]:
        raise ValueError("Rebuilt source archive differs: " + component)
    if archive.get("format") != "tar.gz" or "patchesApplied" not in archive or archive.get("completePreferredSource") is not True:
        raise ValueError("Rebuild source report is not final: " + component)
    if digest(Path(private["library"]["path"])) != report["library"]["sha256"]:
        raise ValueError("Rebuilt library differs: " + component)
    relative = source_path.relative_to(CACHE.parent).as_posix()
    public = public_rebuild_report(report)
    public_bytes = (json.dumps(public, indent=2) + "\n").encode("utf-8")
    result = {"repository": report["repository"], "revision": report["sourceCommit"], "archive": "rebuilt/" + relative,
              "cachePath": "../" + relative, "bytes": archive["size"], "sha256": archive["sha256"],
              "archiveLayout": "source/ contains the exact tracked upstream tree; BUILDING.md and build driver describe reconstruction",
              "patchesApplied": archive["patchesApplied"], "licenseFiles": [], "submodules": [], "rebuildReport": public,
              "rebuildReportSha256": hashlib.sha256(public_bytes).hexdigest(), "originalPublicRecordSha256": digest(public_path), "originalPrivateRecordSha256": digest(report_path)}
    with tarfile.open(source_path, "r:gz") as tar:
        names = tar.getnames()
        if not any(PurePosixPath(p).name == "BUILDING.md" for p in names): raise ValueError("Missing independent rebuild instructions")
        driver_hashes = {hashlib.sha256(tar.extractfile(p).read()).hexdigest() for p in names if PurePosixPath(p).name == "rebuild_cores.py"}
        if report["buildScript"]["sha256"] not in driver_hashes: raise ValueError("Source package rebuild driver differs")
        for patch in report["patches"]:
            if not any(hashlib.sha256(tar.extractfile(p).read()).hexdigest() == patch["sha256"] for p in names if PurePosixPath(p).name == PurePosixPath(patch["path"]).name):
                raise ValueError("Source package rebuild patch differs")
        for member in tar:
            path = safe_relative(member.name)
            if member.isfile() and path.suffix == ".json" and path.parts[0] != "source":
                body = tar.extractfile(member).read().decode("utf-8")
                if any(marker in body for marker in (".codex", "muxi-zombie-challenge", "C:\\\\Users\\\\ranzh", "C:/Users/ranzh")):
                    raise ValueError("Private host environment in a public source record")
            if member.isfile() and LICENSE_NAME.fullmatch(path.name):
                if member.size > 1024 * 1024: raise ValueError("Oversized rebuilt license")
                dest = ROOT / "third-party/licenses" / component / path
                dest.parent.mkdir(parents=True, exist_ok=True); dest.write_bytes(tar.extractfile(member).read())
                result["licenseFiles"].append(dest.relative_to(ROOT).as_posix())
    return result, report


def source_path(source):
    return CACHE / source.get("cachePath", source["archive"])


def prepare():
    cores = json.loads((ROOT / "third-party/cores.json").read_text(encoding="utf-8"))["cores"]
    entries = {}; jobs = []
    for name, core in cores.items():
        if core.get("purpose") == "diagnostic-only": continue
        revision = RESOLVED.get(name, core.get("binaryReportedRevisionResolved"))
        entries[name] = {"licenseMetadata": core["license"], "repository": core["source"], "binaryVersion": core["binaryVersion"], "binarySha256": core["platforms"]["windows-x86_64"]["librarySha256"], "sourceCommit": revision, "mappingEvidence": core.get("revisionResolutionEvidence", "https://api.github.com/repos/" + github(core["source"]) + "/commits/" + core["binaryVersion"].split()[-1].strip("()")), "delivery": "pending-unresolved"}
        report = CACHE.parent / REBUILT.get(name, "absent") / "rebuild-report.json"
        if name in REBUILT and report.is_file():
            source, record = rebuilt_source(name, report)
            entries[name].update({"source": source, "sourceCommit": record["sourceCommit"], "binarySha256": record["library"]["sha256"], "delivery": "complete-snapshot", "mappingEvidence": "source.rebuildReport", "binaryOrigin": "source-rebuild", "correspondenceBasis": "local DLL built from this source archive and recorded toolchain; no claim for replaced rolling DLL"})
        elif revision: jobs.append((name, core["source"], revision))
    def obtain(job):
        name, repo, revision = job
        # API commit confirmation is kept independently of the binary's self-reported short hash.
        confirmed = api("https://api.github.com/repos/" + github(repo) + "/commits/" + revision)
        if confirmed["sha"] != revision: raise ValueError("Upstream revision resolution differs")
        source = snapshot(name, repo, revision); print("prepared " + name, flush=True); return name, source
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        for name, source in pool.map(obtain, jobs): entries[name].update({"source": source, "delivery": "complete-snapshot", "correspondenceBasis": "binary-reported git revision resolved in the declared upstream repository; not a reproducible-build claim"})
    entries["mame"]["upstreamLicenseDeclaration"] = {"spdx": "GPL-2.0-or-later", "scope": "project as a whole; individual headers and COPYING retain component-specific terms", "evidence": "https://github.com/libretro/mame/blob/" + entries["mame"]["sourceCommit"] + "/README.md#license"}
    dependencies = {}
    for name, repo, tag in (("jna", "https://github.com/java-native-access/jna", "5.14.0"), ("lwjgl", "https://github.com/LWJGL/lwjgl3", "3.3.3")):
        revision = api("https://api.github.com/repos/" + github(repo) + "/commits/" + tag)["sha"]
        dependencies[name] = snapshot(name, repo, revision); dependencies[name]["version"] = tag; print("prepared " + name, flush=True)
    # LWJGL stores some third-party notices as <module>_license.txt instead of LICENSE.
    for relative in ("LICENSE.md", "modules/lwjgl/core/libffi_license.txt", "modules/lwjgl/glfw/glfw_license.txt", "modules/lwjgl/opengl/khronos_license.txt"):
        dest = ROOT / "third-party/licenses/lwjgl" / relative; dest.parent.mkdir(parents=True, exist_ok=True)
        data = CACHE / "notices" / ("lwjgl-" + relative.replace("/", "_"))
        download("https://raw.githubusercontent.com/LWJGL/lwjgl3/" + dependencies["lwjgl"]["revision"] + "/" + relative, data, 1024 * 1024); dest.write_bytes(data.read_bytes())
        key = str(dest.relative_to(ROOT)).replace("\\", "/")
        if key not in dependencies["lwjgl"]["licenseFiles"]: dependencies["lwjgl"]["licenseFiles"].append(key)
    for relative in ("LICENSE", "LGPL2.1", "AL2.0"):
        dest = ROOT / "third-party/licenses/jna" / relative; dest.parent.mkdir(parents=True, exist_ok=True)
        data = CACHE / "notices" / ("jna-" + relative)
        download("https://raw.githubusercontent.com/java-native-access/jna/" + dependencies["jna"]["revision"] + "/" + relative, data, 1024 * 1024); dest.write_bytes(data.read_bytes())
        key = str(dest.relative_to(ROOT)).replace("\\", "/")
        if key not in dependencies["jna"]["licenseFiles"]: dependencies["jna"]["licenseFiles"].append(key)
    dependencies["jna"].update({"licenseChoice": "LGPL-2.1-or-later (the original Apache-2.0 OR LGPL dual-license notice is also preserved)", "binaries": [JNA]})
    dependencies["lwjgl"]["binaries"] = json.loads((ROOT / "third-party/worker-dependencies.json").read_text(encoding="utf-8"))["dependencies"]
    first_party = sorted((ROOT / "common/src/main/java/net/muxigame/console/runtime").glob("*.java"))
    if len(first_party) != 12: raise ValueError("Review the worker source scope before changing its file count")
    first_party += [ROOT / p for p in ("LICENSE", "tools/build_worker.py", "docs/BUILD.md", "tools/rebuild_cores.py", "tools/release_sources.py", "third-party/worker-dependencies.json")]
    first_party_records = [file_record(p) for p in first_party]
    pending = [name for name, entry in entries.items() if entry["delivery"] != "complete-snapshot"]
    licenses = sorted({"third-party/licenses/README.md", *(license for entry in [*(e["source"] for e in entries.values() if "source" in e), *dependencies.values()] for source in flatten(entry) for license in source["licenseFiles"])})
    manifest = {"schema": 2, "scope": "Exact upstream snapshots, recursive fixed gitlinks, unmodified notices and the MIT worker/build source; cores.json releaseReady flags are independently reviewed", "delivery": {"method": "complete source embedded in the same binary JAR", "jarEntry": "META-INF/muxi/source-bundle.zip", "notAnExternalSourceOffer": True}, "firstParty": {"license": "MIT", "workerJavaFiles": 12, "protocolSource": "common/src/main/java/net/muxigame/console/runtime/Wire.java", "files": first_party_records}, "cores": entries, "dependencies": dependencies, "licenseFiles": [file_record(ROOT / p) for p in licenses], "pendingCores": pending, "completeForAllPackagedCores": not pending}
    MANIFEST.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    bundle = CACHE / "muxi-console-third-party-sources.zip"
    readme = """Complete source delivery for muxi-console

This ZIP is embedded at META-INF/muxi/source-bundle.zip in the same mod JAR as the corresponding DLLs and Java worker. It is complete source delivery, not a promise to provide sources on request. Read source_manifest.json for exact binary/source hashes, upstream revisions and license scope. pendingCores must be empty for release.

archives/: immutable upstream source snapshots. Strip each tar archive's single directory prefix. Then install its recursively listed submodule archives at installPath relative to its parent source root. Revisions are the exact parent gitlinks, never current HEAD. nonGitlinkDeclarations are stale vendored manifests, not missing source. All source headers, original copyrights, LICENSE/COPYING and vendor files are retained in these complete trees.

rebuilt/: the BlastEm/PCE corresponding-source packages contain source/, BUILDING.md, the MIT build driver, full build record, toolchain download locks and any applied patches. Follow BUILDING.md. The report applies only to the new recorded DLL hash, not the replaced rolling binary.

first-party/: MIT Java worker sources including its complete subprocess protocol, tools/build_worker.py, docs/BUILD.md, dependency pins, and build/source delivery scripts. Extract this directory as a normal repository tree and follow docs/BUILD.md. Java21/Python3.11 and public fixed Maven dependencies suffice to rebuild the worker without Minecraft or private SDKs. Mod source is also publicly available at https://github.com/muxigame/muxi-console .

JNA is distributed under the LGPL-2.1-or-later alternative; its original dual-license notice and both full license texts are preserved. LWJGL/GLFW/libffi/Khronos notices are retained. Each core retains its upstream license; this package does not relicense third-party code as MIT. Source archives include upstream build scripts and instructions. The 13 rolling cores are mapped by their binary-reported git revision resolved in the declared official repository; this is not a reproducible-build claim.
"""
    temporary = bundle.with_suffix(".zip.part")
    with zipfile.ZipFile(temporary, "w", zipfile.ZIP_STORED) as zip:
        zip.writestr("README-SOURCES.txt", readme); zip.write(MANIFEST, "source_manifest.json")
        for record in first_party_records: zip.write(ROOT / record["path"], "first-party/" + record["path"])
        added = set()
        for root in [*(e["source"] for e in entries.values() if "source" in e), *dependencies.values()]:
            for source in flatten(root):
                archive = source_path(source)
                if digest(archive) != source["sha256"]: raise ValueError("Source changed before bundling")
                zip.write(archive, source["archive"])
                if "rebuildReport" in source: zip.writestr("rebuilt/" + root["revision"] + "-build-report.json", json.dumps(source["rebuildReport"], indent=2) + "\n")
                for license in source["licenseFiles"]:
                    if license not in added: zip.write(ROOT / license, license); added.add(license)
        for license in licenses:
            if license not in added: zip.write(ROOT / license, license); added.add(license)
    temporary.replace(bundle)
    delivery = {"schema": 1, "manifestSha256": digest(MANIFEST), "bundle": file_record(bundle, bundle.name), "completeForAllPackagedCores": not pending}
    DELIVERY.write_text(json.dumps(delivery, indent=2) + "\n", encoding="utf-8")
    return {"manifest": str(MANIFEST), "completeCoreSnapshots": len(entries)-len(pending), "pendingCores": pending, "bundle": str(bundle), "bundleBytes": bundle.stat().st_size, "bundleSha256": delivery["bundle"]["sha256"]}


def source_delivery(require_complete=True):
    """Offline build gate. It never downloads, changes pins, or grants release permission."""
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    delivery = json.loads(DELIVERY.read_text(encoding="utf-8"))
    if digest(MANIFEST) != delivery["manifestSha256"]: raise ValueError("Source manifest changed; prepare the bundle again")
    if require_complete and (manifest["pendingCores"] or not manifest["completeForAllPackagedCores"]): raise ValueError("Incomplete core source delivery")
    cores = json.loads((ROOT / "third-party/cores.json").read_text(encoding="utf-8"))["cores"]
    packaged = {name: c for name, c in cores.items() if c.get("purpose") != "diagnostic-only"}
    if packaged.keys() != manifest["cores"].keys(): raise ValueError("Packaged core/source set differs")
    for name, core in packaged.items():
        if manifest["cores"][name]["binarySha256"] != core["platforms"]["windows-x86_64"]["librarySha256"]:
            raise ValueError("Packaged DLL/source mapping differs: " + name)
        if core.get("sourceRevisionVerified") and core.get("sourceRevision") != manifest["cores"][name]["sourceCommit"]:
            raise ValueError("Verified source revision differs: " + name)
    if manifest["dependencies"]["jna"]["binaries"] != [JNA]: raise ValueError("JNA source/binary mapping differs")
    worker = json.loads((ROOT / "third-party/worker-dependencies.json").read_text(encoding="utf-8"))["dependencies"]
    if manifest["dependencies"]["lwjgl"]["binaries"] != worker: raise ValueError("LWJGL source/binary mapping differs")
    for record in manifest["firstParty"]["files"] + manifest["licenseFiles"]:
        path = ROOT / safe_relative(record["path"])
        if path.stat().st_size != record["bytes"] or digest(path) != record["sha256"]:
            raise ValueError("Source/license changed; prepare the bundle again: " + record["path"])
    bundle = CACHE / safe_relative(delivery["bundle"]["path"])
    if bundle.stat().st_size != delivery["bundle"]["bytes"] or digest(bundle) != delivery["bundle"]["sha256"]: raise ValueError("Source bundle differs")
    return {"path": bundle, "bytes": bundle.stat().st_size, "sha256": delivery["bundle"]["sha256"], "manifest": manifest, "manifestSha256": delivery["manifestSha256"]}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True); group.add_argument("--prepare", action="store_true"); group.add_argument("--verify", action="store_true")
    args = parser.parse_args()
    result = prepare() if args.prepare else source_delivery()
    if args.verify: result = {k: str(v) if isinstance(v, Path) else v for k, v in result.items() if k != "manifest"}
    print(json.dumps(result, indent=2))
