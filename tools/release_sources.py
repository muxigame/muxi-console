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
LICENSE_NAME = re.compile(r"^(?:(?:LICEN[CS]ES?|COPYING|COPYRIGHTS?|NOTICES?|OFL)(?:[._-].*)?|.*[_-](?:licen[cs]es?|copyrights?|notices?)(?:[._-].*)?)$", re.I)
MANIFEST = ROOT / "third-party/source_manifest.json"
DELIVERY = CACHE / "source-delivery.json"
REBUILT = {"blastem": "rebuild-blastem", "mednafen_pce_fast": "rebuild-pce"}
JNA = {"file": "jna-5.14.0.jar", "artifact": "net.java.dev.jna:jna:5.14.0", "url": "https://repo.maven.apache.org/maven2/net/java/dev/jna/jna/5.14.0/jna-5.14.0.jar", "bytes": 1878533, "sha256": "34ed1e1f27fa896bca50dbc4e99cf3732967cec387a7a0d5e3486c09673fe8c6"}
AUXILIARY = {"sameboy-link":"sameboy","mgba-link":"mgba","melondsds-link":"melondsds","azahar-link":"azahar"}
REVIEWED_RUNTIME = {
    "AvFrame.java","ConsoleRuntime.java","CoreInfo.java","DiskState.java","HardwareVideo.java",
    "InputCodec.java","InputFrame.java","LaunchConfig.java","LibretroCore.java","LinkBackend.java",
    "Link3dsIdentity.java","Link3dsNative.java","Link3dsRuntime.java","Link3dsWorker.java",
    "LinkDsCheckpoint.java","LinkDsIdentity.java","LinkDsNativeState.java","LinkDsNetpacket.java",
    "LinkDsNetwork.java","LinkDsRuntime.java","LinkDsWorker.java","LinkGbaNativeCore.java",
    "LinkNativeCore.java","LinkNetpacket.java","LinkRuntime.java","LinkWire.java","LinkWorker.java",
    "MgbaStateNormalizer.java","NativeProfilePath.java","NativeStateBinding.java","OwnedProcess.java",
    "PortInput.java","ProcessConsoleRuntime.java","WindowsOwnedProcess.java","Wire.java","WorkerMain.java",
}
REVIEWED_NATIVE = {"native/link/sameboy_link_adapter.c","native/link/mgba_link_adapter.c","native/link/melondsds_link_adapter.cpp","native/link/azahar_link_adapter.cpp","native/link/diagnostics/azahar_uds.c"}
REVIEWED_PATCHES={"native/patches/dolphin-sd-checkpoint-sharing.patch":"GPL-2.0-or-later","native/patches/dolphin-mingw-constexpr.patch":"GPL-2.0-or-later"}
REVIEWED_GPL_NATIVE={"native/link/azahar_nwm_state.inc":"GPL-2.0-or-later"}
MINGW_TOOLCHAIN={"url":"https://github.com/mstorsjo/llvm-mingw/releases/download/20250613/llvm-mingw-20250613-ucrt-x86_64.zip","bytes":152446205,"sha256":"45145c035d9246e1de16f1873aa9afa863d93909f4a8f363e2eb38a04031d3c3"}
MINGW_NOTICES={
    "COPYING":(2326,"99a69660981156c21336fdb5661f89341b013c94e4bf9e1c7467b4745718397f"),
    "COPYING.MinGW-w64.txt":(5905,"f38e6194bd3bfa1b654f118e5acefe0aead437bbe669eee43957ccc65a7127f1"),
    "COPYING.MinGW-w64-runtime.txt":(10936,"e9b2dc02451ea29092a1f25fa0f3c07207ed421f1807dffb0c4e6dce69dee7bd"),
    "COPYING.winpthreads.txt":(2883,"63263614cdd29f2f93cba85e992f041b31f9fc7b4033692f31269489a8a1b177"),
    "COPYING.winstorecompat.txt":(1099,"fa7368d1f93d890c173177475d8ec7aff265a7d08f1f364d66f74d138978ce36"),
}
REVIEWED_BUILD = ("LICENSE","tools/build_worker.py","docs/BUILD.md","tools/rebuild_cores.py","tools/release_sources.py","third-party/worker-dependencies.json","tools/build_link_adapter.py","tools/build_gba_link_adapter.py","tools/build_melondsds.py","tools/export_melondsds_sources.py","tools/build_ds_link_adapter.py","tools/export_ds_link_sources.py","tools/expansion_sources.py","tools/fetch_expansion_cores.py","tools/prepare_dolphin_system.py","tools/build_3ds_link_adapter.py","tools/export_3ds_link_sources.py","tools/build_dolphin.py","tools/export_dolphin_sources.py")

def reviewed_first_party():
    runtime=sorted((ROOT/"common/src/main/java/net/muxigame/console/runtime").glob("*.java"))
    if {p.name for p in runtime} != REVIEWED_RUNTIME: raise ValueError("Review the exact new worker/runtime source scope before release")
    native={p.relative_to(ROOT).as_posix() for p in (ROOT/"native").rglob("*") if p.is_file() and p.suffix.lower() in {".c",".cc",".cpp",".h",".hpp",".s",".inc"}}
    if native != REVIEWED_NATIVE|set(REVIEWED_GPL_NATIVE): raise ValueError("Review the exact native bridge and derivative-source scope before release")
    patches={p.relative_to(ROOT).as_posix() for p in (ROOT/"native").rglob("*.patch") if p.is_file()}
    if patches!=set(REVIEWED_PATCHES):raise ValueError("Review the exact third-party patch/license scope before release")
    return runtime+[ROOT/p for p in (*REVIEWED_BUILD,*sorted(REVIEWED_NATIVE))]

def packaged_auxiliary():
    names={p.stem for p in (ROOT/"third-party").glob("*-link.json")}
    if names-set(AUXILIARY):raise ValueError("Review the exact new native link/source scope")
    return names


def private_metadata(relative, data):
    """Upstream examples remain preferred source; generated host records never do."""
    normalized=data.replace(b"\\\\",b"\\").replace(b"\\",b"/").lower()
    workspace=ROOT.parent.as_posix().lower().encode()
    if workspace in normalized or b".bmc-private" in normalized or b".private/muxi-console" in normalized:return True
    preferred=any(part in {"source","upstream"} for part in relative.parts[:-1])
    return not preferred and re.search(rb"c:/users/",normalized) is not None


def auxiliary_sources(entries):
    result={}
    for name in sorted(packaged_auxiliary()):
        path=ROOT/"third-party"/(name+".json")
        pin=json.loads(path.read_text(encoding="utf-8"));delivery=pin["sourceDelivery"];archive=ROOT.parent/safe_relative(delivery["archive"])
        if not archive.resolve().is_relative_to(CACHE.parent.resolve()) or archive.stat().st_size!=delivery["archiveBytes"] or digest(archive)!=delivery["archiveSha256"]:raise ValueError("Auxiliary corresponding source differs: "+name)
        binary=pin["platforms"]["windows-x86_64"];binary_archive=ROOT.parent/safe_relative(binary["archive"])
        if binary_archive.stat().st_size!=binary["archiveBytes"] or digest(binary_archive)!=binary["archiveSha256"]:raise ValueError("Auxiliary binary archive differs: "+name)
        with zipfile.ZipFile(binary_archive) as z:
            files=[f for f in z.infolist() if f.filename==binary["libraryName"] and not f.is_dir()]
            if len(files)!=1 or files[0].file_size!=binary["libraryBytes"] or hashlib.sha256(z.read(files[0])).hexdigest()!=binary["librarySha256"]:raise ValueError("Auxiliary binary library differs: "+name)
        report_path=ROOT.parent/safe_relative(delivery["buildReport"])
        if not report_path.resolve().is_relative_to(CACHE.parent.resolve()) or digest(report_path)!=delivery["buildReportSha256"]:raise ValueError("Auxiliary rebuild record differs: "+name)
        report=json.loads(report_path.read_text(encoding="utf-8"))
        if report["sourceCommit"]!=pin["sourceRevision"] or report["library"]["sha256"]!=binary["librarySha256"] or report["library"]["bytes"]!=binary["libraryBytes"] or report["archive"]["sha256"]!=binary["archiveSha256"] or report["sourceArchive"]["sha256"]!=delivery["archiveSha256"] or report["sourceArchive"]["bytes"]!=delivery["archiveBytes"]:raise ValueError("Auxiliary source/build/binary correspondence differs: "+name)
        if re.search(r"(?i)c:[/\\]users[/\\]",json.dumps(report).replace("\\\\","\\")):raise ValueError("Auxiliary rebuild record needs a public portable export: "+name)
        base=entries[AUXILIARY[name]]
        if base["sourceCommit"]!=pin["sourceRevision"] or base["repository"]!=pin["source"]:raise ValueError("Auxiliary upstream source identity differs: "+name)
        if name in ("sameboy-link","mgba-link") and report["upstreamArchive"]["sha256"]!=base["source"]["sha256"]:raise ValueError("Auxiliary exact upstream archive differs: "+name)
        source={"repository":pin["source"],"revision":pin["sourceRevision"],"archive":"auxiliary/"+name+"-corresponding-source.tar.gz","cachePath":"../"+archive.relative_to(CACHE.parent).as_posix(),"bytes":archive.stat().st_size,"sha256":digest(archive),"licenseFiles":[],"submodules":[]}
        source["rebuildReport"]=report;source["rebuildReportSha256"]=delivery["buildReportSha256"]
        source["upstreamCore"]=AUXILIARY[name]
        source["licenseFiles"].extend(license for item in flatten(base["source"]) for license in item["licenseFiles"])
        # The outer corresponding-source tar's LICENSE is our adapter/package
        # MIT license. Never overwrite the upstream core's canonical LICENSE.
        if name in ("sameboy-link","mgba-link"):
            original=ROOT/"third-party/licenses"/AUXILIARY[name]/"LICENSE";canonical=ROOT/"third-party/licenses"/name/"LICENSE"
            canonical.parent.mkdir(parents=True,exist_ok=True);canonical.write_bytes(original.read_bytes());source["licenseFiles"].append(canonical.relative_to(ROOT).as_posix())
        with tarfile.open(archive,"r:*") as t:
            for m in t.getmembers():
                relative=safe_relative(m.name)
                if m.isfile() and (LICENSE_NAME.fullmatch(relative.name) or any(p.upper()=="LICENSES" for p in relative.parts[:-1])):
                    if m.size>1024*1024:raise ValueError("Oversized auxiliary notice")
                    dest=ROOT/"third-party/licenses"/name/"source-package"/relative;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(t.extractfile(m).read());source["licenseFiles"].append(dest.relative_to(ROOT).as_posix())
                if m.isfile() and m.name.endswith((".json",".md")) and m.size<5*1024*1024:
                    text=t.extractfile(m).read()
                    if private_metadata(relative,text):raise ValueError("Auxiliary public source contains private host paths: "+name)
        result[name]={"manifest":file_record(path),"licenseMetadata":pin["license"],"binarySha256":binary["librarySha256"],"sourceCommit":pin["sourceRevision"],"delivery":"complete-snapshot","source":source}
    return result

def packaged_resources():
    path=ROOT/"third-party/system-resources.json"
    if not path.is_file():return {}
    resources={}
    for entry in json.loads(path.read_text(encoding="utf-8"))["resources"]:
        name=entry["id"]
        if name in resources or name!="dolphin-sys":raise ValueError("Review the exact immutable system resource scope")
        if entry["entry"]!="system/dolphin-sys.zip":raise ValueError("Dolphin system resource JAR entry differs")
        archive=ROOT.parent/safe_relative(entry["archive"])
        if not archive.resolve().is_relative_to(CACHE.parent.resolve()) or archive.stat().st_size!=entry["archiveBytes"] or digest(archive)!=entry["archiveSha256"]:raise ValueError("Packaged system resource archive differs: "+name)
        resources[name]={"jarEntry":entry["entry"],"cachePath":"../"+archive.relative_to(CACHE.parent).as_posix(),"bytes":entry["archiveBytes"],"sha256":entry["archiveSha256"],"sourceCommit":entry["sourceRevision"]}
    return resources


def resource_sources(entries):
    report=CACHE.parent/"expansion/dolphin/system-resources.json"
    packaged=packaged_resources()
    if "dolphin" not in entries:
        if packaged:raise ValueError("System resources have no packaged source core")
        return {}
    if set(packaged)!={"dolphin-sys"}:raise ValueError("Packaged Dolphin system resource is missing")
    if not report.is_file():raise ValueError("Dolphin system resource source record is missing")
    value=json.loads(report.read_text());archive=Path(value["path"])
    if not archive.resolve().is_relative_to(CACHE.parent.resolve()) or archive.stat().st_size!=value["bytes"] or digest(archive)!=value["sha256"]:raise ValueError("Dolphin system resources differ")
    dolphin_source=entries["dolphin"]["source"];upstream_sha=dolphin_source.get("rebuildReport",{}).get("upstreamArchive",{}).get("sha256",dolphin_source["sha256"])
    if value["sourceCommit"]!=entries["dolphin"]["sourceCommit"] or value["sourceArchiveSha256"]!=upstream_sha:raise ValueError("Dolphin Sys source mapping differs")
    resource={"jarEntry":"system/dolphin-sys.zip","cachePath":"../"+archive.relative_to(CACHE.parent).as_posix(),"bytes":value["bytes"],"sha256":value["sha256"],"sourceCommit":value["sourceCommit"],"sourceArchiveSha256":value["sourceArchiveSha256"],"sourceScope":"Exact tracked Data/Sys subtree; full source and per-file font/resource notices are included with the Dolphin source records","files":value["files"],"expandedBytes":value["expandedBytes"]}
    if any(resource[k]!=v for k,v in packaged["dolphin-sys"].items()):raise ValueError("Packaged Dolphin system resource/source mapping differs")
    return {"dolphin-sys":resource}


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


def source_pin(pin):
    """Source-relevant pins exclude later release approval and runtime-test flags."""
    platform=pin["platforms"]["windows-x86_64"];delivery=pin.get("sourceDelivery",{})
    return {"repository":pin["source"],"revision":pin.get("sourceRevision"),"license":pin["license"],
        "binaryVersion":pin["binaryVersion"],"binaryOrigin":pin.get("binaryOrigin"),
        "platform":{key:platform.get(key) for key in ("archive","archiveBytes","archiveSha256","libraryName","libraryBytes","librarySha256")},
        "sourceDelivery":{key:delivery[key] for key in ("archive","format","archiveBytes","archiveSha256","buildReport","buildReportSha256","sharedAuxiliary") if key in delivery}}


def mingw_runtime_notices():
    result=[]
    for name,(size,sha) in MINGW_NOTICES.items():
        path=ROOT/"third-party/licenses/mingw-runtime"/name
        if not path.is_file() or path.stat().st_size!=size or digest(path)!=sha:raise ValueError("Original pinned MinGW runtime notice differs: "+name)
        result.append({"licenseFile":path.relative_to(ROOT).as_posix(),"upstream":{"toolchainArchive":MINGW_TOOLCHAIN,"member":"llvm-mingw-20250613-ucrt-x86_64/x86_64-w64-mingw32/share/mingw32/"+name,"bytes":size,"sha256":sha}})
    return result


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
                if any(marker in body for marker in (".codex", "muxi-zombie-challenge")) or private_metadata(path, body.encode("utf-8")):
                    raise ValueError("Private host environment in a public source record")
            if member.isfile() and LICENSE_NAME.fullmatch(path.name):
                if member.size > 1024 * 1024: raise ValueError("Oversized rebuilt license")
                dest = ROOT / "third-party/licenses" / component / path
                dest.parent.mkdir(parents=True, exist_ok=True); dest.write_bytes(tar.extractfile(member).read())
                result["licenseFiles"].append(dest.relative_to(ROOT).as_posix())
    return result, report


def source_path(source):
    return CACHE / source.get("cachePath", source["archive"])


def ordinary_source_aliases(entries, auxiliary):
    """A renamed ordinary DLL must retain the exact patched bridge source mapping."""
    for name,alias in (("azahar","azahar-link"),("melondsds","melondsds-link")):
        if name not in entries:continue
        ordinary=entries[name]
        if alias not in auxiliary:
            if ordinary.get("sourceArtifactAlias")==alias:raise ValueError("Declared ordinary native source alias is missing: "+name)
            continue
        patched=auxiliary[alias]
        if ordinary["binarySha256"]!=patched["binarySha256"]:
            if ordinary.get("sourceArtifactAlias")==alias or name=="azahar" and ordinary.get("binaryOrigin")=="source-rebuild":raise ValueError("Rebuilt ordinary core lacks its exact patched native source: "+name)
            continue
        if ordinary["sourceCommit"]!=patched["sourceCommit"]:raise ValueError("Ordinary and auxiliary source revisions differ: "+name)
        ordinary.update({"source":dict(patched["source"]),"delivery":"complete-snapshot","binaryOrigin":"source-rebuild",
            "sourceArtifactAlias":alias,"mappingEvidence":"auxiliary."+alias+".source.rebuildReport; ordinary and auxiliary DLLs have the same actual SHA256",
            "correspondenceBasis":"Exact locally rebuilt patched native DLL and complete corresponding source; old snapshot compatibility is a separate runtime claim"})


def prepare():
    cores = json.loads((ROOT / "third-party/cores.json").read_text(encoding="utf-8"))["cores"]
    entries = {}; jobs = []
    for name, core in cores.items():
        if core.get("purpose") == "diagnostic-only": continue
        revision = RESOLVED.get(name, core.get("binaryReportedRevisionResolved"))
        entries[name] = {"licenseMetadata": core["license"], "repository": core["source"], "binaryVersion": core["binaryVersion"], "binarySha256": core["platforms"]["windows-x86_64"]["librarySha256"], "sourceCommit": revision, "mappingEvidence": core.get("revisionResolutionEvidence", "https://api.github.com/repos/" + github(core["source"]) + "/commits/" + core["binaryVersion"].split()[-1].strip("()")), "delivery": "pending-unresolved"}
        entries[name]["sourcePin"]=source_pin(core)
        if core.get("binaryOrigin"):entries[name]["binaryOrigin"]=core["binaryOrigin"]
        alias=core.get("sourceDelivery",{}).get("sharedAuxiliary")
        if alias:
            if AUXILIARY.get(alias)!=name:raise ValueError("Ordinary source alias names another core")
            entries[name]["sourceArtifactAlias"]=alias
        expansion=CACHE/("expansion-"+name+"-source.json")
        if name=="dolphin" and core.get("binaryOrigin")=="source-rebuild":
            expansion=CACHE/"expansion-dolphin-rebuilt-source.json"
            if not expansion.is_file():raise ValueError("Rebuilt Dolphin requires its exact complete patched source record")
        if name in ("melondsds","azahar","dolphin") and expansion.is_file():
            source=json.loads(expansion.read_text(encoding="utf-8"))
            if source["revision"]!=core["sourceRevision"] or source_path(source).stat().st_size!=source["bytes"] or digest(source_path(source))!=source["sha256"]:raise ValueError("Expansion source differs: "+name)
            if name=="melondsds" and not alias and source["rebuildReport"]["library"]["sha256"]!=core["platforms"]["windows-x86_64"]["librarySha256"]:raise ValueError("Rebuilt DS source maps to another binary")
            if name=="dolphin" and core.get("binaryOrigin")=="source-rebuild" and source["rebuildReport"]["library"]["sha256"]!=core["platforms"]["windows-x86_64"]["librarySha256"]:raise ValueError("Rebuilt Dolphin source maps to another binary")
            if name=="azahar" and source.get("missingDeclaredModuleDirectories"):raise ValueError("Unified Azahar source has missing module directories")
            entries[name].update({"source":source,"sourceCommit":source["revision"],"delivery":"complete-snapshot","mappingEvidence":"fixed official unified source / binary-reported revision / exact local build record as detailed in source","correspondenceBasis":"exact source mapping; independent gameplay and reproducible-build claims remain separate"})
            continue
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
    toolchain_notices=[]
    for relative in ("LICENSE.TXT","libcxx/LICENSE.TXT","libcxxabi/LICENSE.TXT","compiler-rt/LICENSE.TXT","libunwind/LICENSE.TXT"):
        url="https://raw.githubusercontent.com/llvm/llvm-project/llvmorg-20.1.7/"+relative
        cached=CACHE/"notices"/("llvm20-"+relative.replace("/","_"));provenance=download(url,cached,1024*1024)
        destination=ROOT/"third-party/licenses/llvm-runtime"/relative;destination.parent.mkdir(parents=True,exist_ok=True);destination.write_bytes(cached.read_bytes())
        toolchain_notices.append({"licenseFile":destination.relative_to(ROOT).as_posix(),"upstream":provenance})
    llvm_notices=list(toolchain_notices);mingw_notices=mingw_runtime_notices();toolchain_notices.extend(mingw_notices)
    auxiliary=auxiliary_sources(entries);ordinary_source_aliases(entries,auxiliary);resources=resource_sources(entries)
    first_party = reviewed_first_party()+[ROOT/entry["manifest"]["path"] for entry in auxiliary.values()]
    first_party_records = [file_record(p) for p in first_party]
    patch_records=[{**file_record(ROOT/path),"license":license} for path,license in sorted(REVIEWED_PATCHES.items())]
    gpl_native_records=[{**file_record(ROOT/path),"license":license} for path,license in sorted(REVIEWED_GPL_NATIVE.items())]
    pending = [name for name, entry in entries.items() if entry["delivery"] != "complete-snapshot"]
    all_sources=[*(e["source"] for e in entries.values() if "source" in e),*dependencies.values(),*(e["source"] for e in auxiliary.values())]
    licenses = sorted({"third-party/licenses/README.md",*(row["licenseFile"] for row in toolchain_notices), *(license for entry in all_sources for source in flatten(entry) for license in source["licenseFiles"])})
    manifest = {"schema": 3, "scope": "Exact upstream snapshots, recursive fixed gitlinks, unmodified notices, all reviewed Java/native workers, independent link adapters and immutable system resources; release-ready flags remain independently reviewed", "delivery": {"method": "complete source embedded in the same binary JAR", "jarEntry": "META-INF/muxi/source-bundle.zip", "notAnExternalSourceOffer": True}, "firstParty": {"license": "MIT", "workerJavaFiles": len(REVIEWED_RUNTIME), "reviewedRuntimeFiles":sorted(REVIEWED_RUNTIME),"protocolSource": "common/src/main/java/net/muxigame/console/runtime/Wire.java", "files": first_party_records}, "cores": entries, "dependencies": dependencies,"auxiliary":auxiliary,"resources":resources, "licenseFiles": [file_record(ROOT / p) for p in licenses], "pendingCores": pending,"pendingArtifacts":pending, "completeForAllPackagedCores": not pending,"completeForAllPackagedArtifacts":not pending}
    manifest["compilerRuntimeNotices"]={"llvm20":{"source":"https://github.com/llvm/llvm-project/tree/llvmorg-20.1.7","notices":llvm_notices,"scope":"LLVM-MinGW statically linked runtime library notices retained in addition to each core's own full source and notices; compiler toolchain archives are not shipped as binaries"},"mingwW64":{"toolchain":MINGW_TOOLCHAIN,"notices":mingw_notices,"scope":"Exact original notices supplied with the fixed compiler's x86-64 MinGW runtime, winpthreads and compatibility libraries; retaining all notices does not assert every optional runtime component is linked"}}
    manifest["thirdPartyPatches"]=patch_records
    manifest["thirdPartyNativeSources"]=gpl_native_records
    MANIFEST.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    bundle = CACHE / "muxi-console-third-party-sources.zip"
    readme = """Complete source delivery for muxi-console

This ZIP is embedded at META-INF/muxi/source-bundle.zip in the same mod JAR as the corresponding DLLs and Java worker. It is complete source delivery, not a promise to provide sources on request. Read source_manifest.json for exact binary/source hashes, upstream revisions and license scope. pendingArtifacts must be empty for release. Source completeness is separate from runtime compatibility and release approval.

archives/: immutable upstream source snapshots. Strip each tar archive's single directory prefix. Then install its recursively listed submodule archives at installPath relative to its parent source root. Revisions are the exact parent gitlinks, never current HEAD. nonGitlinkDeclarations are stale vendored manifests, not missing source. All source headers, original copyrights, LICENSE/COPYING and vendor files are retained in these complete trees.

rebuilt/: the BlastEm/PCE and melonDS DS corresponding-source packages contain complete preferred source trees, BUILDING.md, MIT build drivers, sanitized build records, exact dependency/toolchain locks and any applied patches. Follow each package's BUILDING.md and archiveLayout. Reports apply only to the recorded DLL hash, not a replaced rolling binary. Successful independent rebuilding does not assert bit-identical output unless its record expressly proves that.

auxiliary/: independent emulation-link bridge packages include exact upstream source plus original MIT adapters/build drivers. Keep their upstream notices and build instructions. The resources section binds the packaged Dolphin Data/Sys ZIP to its exact complete source tree, including font/resource notices; it does not relicense those resources as MIT.

first-party/: MIT Java worker sources including its complete subprocess protocol, tools/build_worker.py, docs/BUILD.md, dependency pins, and build/source delivery scripts. Extract this directory as a normal repository tree and follow docs/BUILD.md. Java21/Python3.11 and public fixed Maven dependencies suffice to rebuild the worker without Minecraft or private SDKs. Mod source is also publicly available at https://github.com/muxigame/muxi-console .

JNA is distributed under the LGPL-2.1-or-later alternative; its original dual-license notice and both full license texts are preserved. LWJGL/GLFW/libffi/Khronos notices are retained. Each core retains its upstream license; this package does not relicense third-party code as MIT. Source archives include upstream build scripts and instructions. Older official rolling cores are mapped by their binary-reported git revision resolved in the declared official repository; fixed official releases, unified sources and local rebuilds have their distinct correspondence evidence recorded in the manifest. These mappings are not a reproducible-build claim.
"""
    temporary = bundle.with_suffix(".zip.part")
    with zipfile.ZipFile(temporary, "w", zipfile.ZIP_STORED) as zip:
        zip.writestr("README-SOURCES.txt", readme); zip.write(MANIFEST, "source_manifest.json")
        for record in first_party_records: zip.write(ROOT / record["path"], "first-party/" + record["path"])
        for record in patch_records:zip.write(ROOT/record["path"],"third-party-patches/"+record["path"])
        for record in gpl_native_records:zip.write(ROOT/record["path"],"third-party-native-sources/"+record["path"])
        added = set();added_archives={}
        for root in all_sources:
            for source in flatten(root):
                archive = source_path(source)
                if digest(archive) != source["sha256"]: raise ValueError("Source changed before bundling")
                name=source["archive"];identity=(source["bytes"],source["sha256"])
                if name in added_archives:
                    if added_archives[name]!=identity:raise ValueError("Conflicting shared source archive identity")
                else:
                    zip.write(archive,name);added_archives[name]=identity
                    if "rebuildReport" in source: zip.writestr("build-records/" + PurePosixPath(name).name + ".json", json.dumps(source["rebuildReport"], indent=2) + "\n")
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
    if require_complete and (manifest["pendingCores"] or not manifest["completeForAllPackagedCores"] or manifest.get("pendingArtifacts") or manifest.get("completeForAllPackagedArtifacts") is not True): raise ValueError("Incomplete corresponding-source artifacts")
    cores = json.loads((ROOT / "third-party/cores.json").read_text(encoding="utf-8"))["cores"]
    packaged = {name: c for name, c in cores.items() if c.get("purpose") != "diagnostic-only"}
    if packaged.keys() != manifest["cores"].keys(): raise ValueError("Packaged core/source set differs")
    for name, core in packaged.items():
        if manifest["cores"][name].get("sourcePin")!=source_pin(core):raise ValueError("Packaged core source/license/binary pins changed: "+name)
        if manifest["cores"][name]["binarySha256"] != core["platforms"]["windows-x86_64"]["librarySha256"]:
            raise ValueError("Packaged DLL/source mapping differs: " + name)
        if core.get("sourceRevisionVerified") and core.get("sourceRevision") != manifest["cores"][name]["sourceCommit"]:
            raise ValueError("Verified source revision differs: " + name)
    if manifest["dependencies"]["jna"]["binaries"] != [JNA]: raise ValueError("JNA source/binary mapping differs")
    worker = json.loads((ROOT / "third-party/worker-dependencies.json").read_text(encoding="utf-8"))["dependencies"]
    if manifest["dependencies"]["lwjgl"]["binaries"] != worker: raise ValueError("LWJGL source/binary mapping differs")
    expected_patches=[{**file_record(ROOT/path),"license":license} for path,license in sorted(REVIEWED_PATCHES.items())]
    if manifest.get("thirdPartyPatches")!=expected_patches:raise ValueError("Reviewed third-party patch/source/license scope changed")
    expected_gpl_native=[{**file_record(ROOT/path),"license":license} for path,license in sorted(REVIEWED_GPL_NATIVE.items())]
    if manifest.get("thirdPartyNativeSources")!=expected_gpl_native:raise ValueError("Reviewed derivative native-source/license scope changed")
    for record in manifest["firstParty"]["files"] + manifest["licenseFiles"]+manifest.get("thirdPartyPatches",[])+manifest.get("thirdPartyNativeSources",[]):
        path = ROOT / safe_relative(record["path"])
        if path.stat().st_size != record["bytes"] or digest(path) != record["sha256"]:
            raise ValueError("Source/license changed; prepare the bundle again: " + record["path"])
    current_aux=packaged_auxiliary()
    if current_aux!=set(manifest.get("auxiliary",{})):raise ValueError("Packaged native link/source set differs")
    for name,alias in (("azahar","azahar-link"),("melondsds","melondsds-link")):
        if alias not in manifest.get("auxiliary",{}):continue
        ordinary=manifest["cores"][name];patched=manifest["auxiliary"][alias]
        if ordinary.get("sourceArtifactAlias")==alias or ordinary["binarySha256"]==patched["binarySha256"]:
            if ordinary["binarySha256"]!=patched["binarySha256"] or ordinary.get("sourceArtifactAlias")!=alias or any(ordinary["source"].get(k)!=patched["source"].get(k) for k in ("sha256","bytes","archive","rebuildReportSha256")):raise ValueError("Ordinary patched core must reference its actual shared corresponding-source artifact: "+name)
    expected_files={p.relative_to(ROOT).as_posix() for p in reviewed_first_party()}|{"third-party/"+name+".json" for name in current_aux}
    if expected_files!={r["path"] for r in manifest["firstParty"]["files"]}:raise ValueError("Reviewed first-party source scope changed")
    for name,entry in manifest.get("auxiliary",{}).items():
        pin=json.loads((ROOT/entry["manifest"]["path"]).read_text(encoding="utf-8"));binary=pin["platforms"]["windows-x86_64"]
        if pin["sourceRevision"]!=entry["sourceCommit"] or binary["librarySha256"]!=entry["binarySha256"]:raise ValueError("Native link source/binary mapping differs: "+name)
        archive=ROOT.parent/safe_relative(binary["archive"])
        if archive.stat().st_size!=binary["archiveBytes"] or digest(archive)!=binary["archiveSha256"]:raise ValueError("Native link archive changed: "+name)
        report=ROOT.parent/safe_relative(pin["sourceDelivery"]["buildReport"])
        if not report.resolve().is_relative_to(CACHE.parent.resolve()) or digest(report)!=entry["source"]["rebuildReportSha256"]:raise ValueError("Native link source/build evidence changed: "+name)
    resources=packaged_resources()
    if set(resources)!=set(manifest.get("resources",{})):raise ValueError("Packaged system resource/source set differs")
    for name,resource in manifest.get("resources",{}).items():
        if any(resource.get(k)!=v for k,v in resources[name].items()):raise ValueError("Packaged system resource/source mapping differs: "+name)
        file=CACHE/safe_relative(resource["cachePath"]) if not resource["cachePath"].startswith("../") else CACHE.parent/safe_relative(resource["cachePath"][3:])
        if not file.resolve().is_relative_to(CACHE.parent.resolve()) or file.stat().st_size!=resource["bytes"] or digest(file)!=resource["sha256"]:raise ValueError("Immutable resource/source mapping changed")
    for entry in [*(e["source"] for e in manifest["cores"].values() if "source" in e),*manifest["dependencies"].values(),*(e["source"] for e in manifest.get("auxiliary",{}).values())]:
        for source in flatten(entry):
            archive=source_path(source)
            if archive.stat().st_size!=source["bytes"] or digest(archive)!=source["sha256"]:raise ValueError("Exact corresponding source archive changed")
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
