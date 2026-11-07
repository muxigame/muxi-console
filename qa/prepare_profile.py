"""Freeze only this independent QA and the fixed public browser dependencies; never launches Minecraft."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import re
import shutil
import stat
import sys
import time
import tomllib
import zipfile

REPO = Path(__file__).resolve().parents[1]
WORKSPACE = REPO.parent
sys.path.insert(0, str(WORKSPACE / "scripts/ops"))
import release_environment as release
import test_environment as environment

BROWSERS = {
    "[内置网页浏览器]WebDisplays-REMASTER-2.1.0.jar": ("webdisplays", "2c61926eb8049a098679c5d3710714d49e831152d8b017b822e99eb00849c85e"),
    "[嵌入式框架]mcef-neoforge-2.1.6-1.21.1.jar": ("mcef", "c8d7c1edd4f3b1085d65240d50b03a768f9efb763b44caa358a17b29979dbe02"),
}


def ordinary(path: Path, parent: Path):
    release.checked_child(path, parent)
    info = path.lstat()
    if not stat.S_ISREG(info.st_mode) or path.is_symlink() or getattr(info, "st_file_attributes", 0) & 0x400:
        raise ValueError("Nonordinary source: " + str(path))
    return info


def metadata(path: Path):
    with zipfile.ZipFile(path) as jar:
        value = tomllib.loads(jar.read("META-INF/neoforge.mods.toml").decode("utf-8-sig"))
    return value


def retire_profile(path: Path, digest: str):
    path=release.checked_child(path.absolute(),release.RELEASES)
    if path.name!="validation-profile.json" or not re.fullmatch(r"[a-f0-9]{64}",digest) or release.hashes(path)["sha256"]!=digest:
        raise ValueError("Retirement requires the exact canonical prior profile and SHA256")
    profile=release.load_profile(path)
    root=profile["_root"]
    owner=json.loads((root/"preparation-owner.json").read_text(encoding="utf-8"))
    if owner.get("workspace")!=str(WORKSPACE) or owner.get("name")!=root.name or owner.get("productionWrites") is not False:
        raise ValueError("Foreign preparation owner")
    closed=[]
    for marker_path in sorted(release.ENVIRONMENT.glob("client-*/runs/*/local-mc-owner.json")):
        marker=json.loads(marker_path.read_text(encoding="utf-8"))
        selected=marker.get("releaseProfile",{})
        if selected.get("path")!=str(path):
            continue
        if selected.get("sha256")!=digest:
            raise ValueError("Run references a different generation of the prior profile")
        run=marker_path.parent
        result=json.loads((run/"run-result.json").read_text(encoding="utf-8"))
        native=result.get("nativeChildAudit",{})
        if result.get("runId")!=marker.get("runId") or result.get("normalExit") is not True or result.get("normalStopOnly") is not True or set(result.get("exitCodes",{}))!=set(marker["roles"]) or any(code!=0 for code in result["exitCodes"].values()) or native.get("complete") is not True or any(native.get(key) for key in ("remaining","unknown","actions","errors")):
            raise ValueError("Prior preparation still has an unclosed or failed-resource run: "+str(run))
        for process in marker["processes"].values():
            created=environment.process_created(process["pid"])
            if created is not None and abs(created-process["processCreateTime"])<=.1:
                raise ValueError("Prior run still has its original live process")
        lease=json.loads((run.parent.parent/"lease.json").read_text(encoding="utf-8"))
        if lease.get("instanceRoot")==str(run) and (lease.get("pid") is not None or lease.get("expires",0)>time.time() or not lease.get("releasedAt")):
            raise ValueError("Prior preparation run lease is not released")
        closed.append({"runId":marker["runId"],"run":str(run)})
    expected={"preparation-owner.json","preparation-result.json","validation-profile.json"}
    expected.update(side+"/"+row["path"] for side in ("client","server") for row in profile[side+"Files"])
    actual=set()
    for entry in root.rglob("*"):
        release.checked_child(entry,root)
        info=entry.lstat()
        if entry.is_symlink() or getattr(info,"st_file_attributes",0)&0x400 or not (stat.S_ISDIR(info.st_mode) or stat.S_ISREG(info.st_mode)):
            raise ValueError("Retirement refuses redirected or unknown entry types")
        if entry.is_file():
            actual.add(entry.relative_to(root).as_posix())
    if actual!=expected:
        raise ValueError("Retirement refuses files beyond the exact prior preparation")
    # checked_child above resolves the absolute recursive target below RELEASES;
    # all entries have been checked and every referencing run is normally closed.
    release.checked_child(root,release.RELEASES)
    shutil.rmtree(root)
    return {"profile":str(path),"sha256":digest,"normallyClosedRuns":closed}


def prepare(name: str, build_path: Path, retire: Path | None = None, retire_sha256: str | None = None):
    if not re.fullmatch(r"[a-z0-9]+(?:-[a-z0-9]+)*", name):
        raise ValueError("Use a new lowercase preparation name")
    build_path = release.checked_child(build_path.absolute(), REPO / "build/qa")
    build = json.loads(build_path.read_text(encoding="utf-8"))
    if (retire is None)!=(retire_sha256 is None):
        raise ValueError("Retirement path and exact SHA256 must be supplied together")
    retired=retire_profile(retire,retire_sha256) if retire is not None else None
    release_root = release.RELEASES
    if release_root.resolve() != release_root or not release_root.is_dir() or any(release_root.iterdir()):
        raise ValueError("The single release-test preparation area must be empty; never overwrite another preparation")
    root = release.checked_child(release_root / name, release_root)
    if root.exists():
        raise ValueError("Fresh preparation required")
    clean = WORKSPACE / "minecraft/release/client"
    mods = []
    for filename, (mod_id, digest) in BROWSERS.items():
        source = clean / "mods" / filename
        ordinary(source, clean)
        if release.hashes(source)["sha256"] != digest or release.mod_ids(source) != {mod_id}:
            raise ValueError("Fixed browser JAR changed: " + filename)
        mods.append(source)
    products = {"product": Path(build["product"]["path"]), **{side: Path(row["path"]) for side, row in build["artifacts"].items()}}
    for key, source in products.items():
        ordinary(source, REPO / "build")
        expected = build["product"] if key == "product" else build["artifacts"][key]
        if release.hashes(source)["sha256"] != expected["sha256"]:
            raise ValueError("QA/product build bytes changed")
    native_rows = []
    for relative, expected in release.MCEF_NATIVE_PUBLIC_INPUTS.items():
        source = clean / relative
        ordinary(source, clean)
        if release.hashes(source) != expected:
            raise ValueError("Public native differs from the runner's exact 72-file lock: " + relative)
        native_rows.append((relative, source, expected))
    if len(native_rows) != 72:
        raise ValueError("Unexpected public native lock size")
    config_relative = "config/mcef/mcef.properties"
    config = clean / config_relative
    ordinary(config, clean)
    config_pin = release.hashes(config)
    if config_pin["size"] != 139:
        raise ValueError("Expected the reviewed public MCEF configuration")
    required = {"minecraft", "neoforge", "muxi_console", "mcef", "webdisplays", "muxi_console_qa_client", "muxi_console_qa_server"}
    dependencies = {}
    for source in [products["product"], products["server"], products["client"], *mods]:
        meta = metadata(source)
        dependencies[source.name] = meta.get("dependencies", {})
        for declarations in dependencies[source.name].values():
            for row in declarations:
                if (row.get("type") == "required" or row.get("mandatory") is True) and row["modId"] not in required:
                    raise ValueError("Unexpected required dependency: " + row["modId"])
    copy_bytes = sum(row[2]["size"] for row in native_rows) + config_pin["size"] + sum(release.hashes(p)["size"] * (2 if key == "product" else 1) for key, p in products.items()) + sum(release.hashes(p)["size"] * 2 for p in mods)
    if shutil.disk_usage(release_root).free < copy_bytes + 10 * 1024**3:
        raise ValueError("Preparation exceeds the normal disk reserve")
    root.mkdir(exist_ok=False)
    release.write_json(root / "preparation-owner.json", {"schema": 1, "workspace": str(WORKSPACE), "name": name,
                       "createdUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "productionWrites": False,
                       "purpose": "muxi-console minimal native model/browser QA"})
    profile = {"schema": "muxigame-release-profile/v1", "preparation": name,
               "baselineVersion": "local-public-browser-pins", "candidateVersion": name,
               "clientFiles": [], "serverFiles": [], "releaseApproved": False,
               "blockers": ["Private QA only; never publish this payload", "Native scenarios and original screenshot review are pending"]}

    def copy(side, relative, source, expected=None):
        before = ordinary(source, WORKSPACE)
        identity = release.hashes(source)
        if expected is not None and identity != expected:
            raise ValueError("Source drift before copy: " + relative)
        target = release.checked_child(root / side / relative, root)
        target.parent.mkdir(parents=True, exist_ok=True)
        with source.open("rb") as incoming, target.open("xb") as outgoing:
            shutil.copyfileobj(incoming, outgoing, 1024 * 1024)
        after = ordinary(source, WORKSPACE)
        info = ordinary(target, root)
        if (before.st_dev, before.st_ino, before.st_size, before.st_mtime_ns) != (after.st_dev, after.st_ino, after.st_size, after.st_mtime_ns) or info.st_nlink != 1 or (info.st_dev, info.st_ino) == (before.st_dev, before.st_ino) or release.hashes(source) != identity or release.hashes(target) != identity:
            raise ValueError("Independent copy or source identity changed: " + relative)
        profile[side + "Files"].append({"path": relative, "policy": "Managed" if relative.endswith(".jar") else "Seed", **identity, "origin": source.relative_to(WORKSPACE).as_posix()})

    for side in ("client", "server"):
        for source in [products["product"], products[side], *mods]:
            copy(side, "mods/" + source.name, source)
    for relative, source, expected in native_rows:
        copy("client", relative, source, expected)
    copy("client", config_relative, config, config_pin)
    # Browser file upload service is unnecessary for a pixel-only QA fixture. This
    # private seed uses its documented 0 setting and never changes clean sources.
    common = root / "client/config/webdisplays_common.toml"
    common.write_text("[mini_server]\nminiserv_port = 0\n", encoding="utf-8")
    common_identity = release.hashes(common)
    profile["clientFiles"].append({"path": "config/webdisplays_common.toml", "policy": "Seed", **common_identity, "origin": "muxi-console/qa/prepare_profile.py:private-no-file-service-fixture"})
    server_common = root / "server/config/webdisplays_common.toml"
    server_common.parent.mkdir(parents=True, exist_ok=True)
    with server_common.open("xb") as out:
        out.write(common.read_bytes())
    profile["serverFiles"].append({"path": "config/webdisplays_common.toml", "policy": "Seed", **common_identity, "origin": "muxi-console/qa/prepare_profile.py:private-no-file-service-fixture"})
    for side in ("client", "server"):
        profile[side + "Files"].sort(key=lambda row: row["path"])
    path = root / "validation-profile.json"
    release.write_json(path, profile)
    verified = release.load_profile(path)
    writable = release.runtime_preflight(verified, 2)
    result = {"profile": str(path), "sha256": release.hashes(path)["sha256"], "clientFiles": len(profile["clientFiles"]),
              "serverFiles": len(profile["serverFiles"]), "publicMcefNativeFiles": len(native_rows), "runtimeWritableBytes": writable,
              "qaBuild": str(build_path), "qaBuildSha256": release.hashes(build_path)["sha256"], "dependencies": dependencies,
              "minecraftStarted": False, "currentPointerChanged": False, "productionWrites": False, "releaseApproved": False}
    if retired is not None:
        result["retiredPreparation"]=retired
    release.write_json(root / "preparation-result.json", result)
    return result


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--name", required=True)
    parser.add_argument("--build", required=True, type=Path)
    parser.add_argument("--retire-profile", type=Path)
    parser.add_argument("--retire-profile-sha256")
    options = parser.parse_args()
    print(json.dumps(prepare(options.name, options.build, options.retire_profile, options.retire_profile_sha256), ensure_ascii=False, indent=2))
