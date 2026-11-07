"""Fetch explicitly pinned official Windows software cores into the shared cache.

--pin is an explicit development-time refresh; ordinary runs verify existing pins.
No core source implementation or other product's cache is read.
"""
from __future__ import annotations

import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import hashlib
import io
import json
from pathlib import Path
import re
import struct
import urllib.request
import zipfile

BASE = "https://buildbot.libretro.com/nightly/windows/x86_64/latest/"
INFO = "https://raw.githubusercontent.com/libretro/libretro-core-info/master/"
# Public project identities and platform support; no implementation code is imported.
SPECS = {
    "fceumm": ("libretro/libretro-fceumm", ["nes", "fds"], "fceumm"),
    "mupen64plus_next": ("libretro/mupen64plus-libretro-nx", ["n64"], "mupen64plus"),
    "mame": ("libretro/mame", ["arcade"], "mame"),
    "sameboy": ("libretro/SameBoy", ["gb", "gbc"], "sameboy"),
    "mgba": ("libretro/mgba", ["gba"], "mgba"),
    "bsnes2014_performance": ("libretro/bsnes2014", ["snes"], "bsnes2014_performance"),
    "blastem": ("libretro/blastem", ["md"], "blastem"),
    "gearsystem": ("drhelius/Gearsystem", ["sms", "gg", "sg1000"], "gearsystem"),
    "mednafen_pce_fast": ("libretro/beetle-pce-fast-libretro", ["pce"], "beetle_pce_fast"),
    "stella2014": ("libretro/stella2014-libretro", ["a2600"], "stella2014"),
    "prosystem": ("libretro/prosystem-libretro", ["a7800"], "prosystem"),
    "mednafen_lynx": ("libretro/beetle-lynx-libretro", ["lynx"], "beetle_lynx"),
    "mednafen_ngp": ("libretro/beetle-ngp-libretro", ["ngp", "ngpc"], "beetle_ngp"),
    "mednafen_wswan": ("libretro/beetle-wswan-libretro", ["ws", "wsc"], "beetle_wswan"),
    "mednafen_vb": ("libretro/beetle-vb-libretro", ["vb"], "beetle_vb"),
    "2048": ("libretro/libretro-2048", [], "2048"),
}


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def download(url):
    request = urllib.request.Request(url, headers={"User-Agent": "MuxiConsole-CorePin/1", "Accept-Encoding": "identity"})
    with urllib.request.urlopen(request, timeout=60) as response:
        if not response.url.startswith((BASE, INFO)):
            raise ValueError("Unexpected official-core download redirect")
        raw = response.read(256 * 1024 * 1024 + 1)
        if len(raw) > 256 * 1024 * 1024:
            raise ValueError("Core archive exceeds 256 MiB")
        return raw, response.headers.get("Last-Modified")


def fetch_one(name, workspace, pin, prior, offline):
    repository, games, documentation = SPECS[name]
    filename = name + "_libretro.dll"
    cache = workspace / "test-environment/cache/muxi-console/libretro"
    cache.mkdir(parents=True, exist_ok=True)
    archive_path = cache / (filename + ".zip")
    dll_path = cache / filename
    existing = prior.get("platforms", {}).get("windows-x86_64")
    if not pin and not existing:
        raise ValueError(f"No existing pin for {name}; use --pin for explicit initial registration")
    if archive_path.exists():
        archive = archive_path.read_bytes(); modified = None
    elif offline:
        raise ValueError(f"Missing cached archive: {archive_path}")
    else:
        archive, modified = download(BASE + filename + ".zip")
    if not pin and sha(archive) != existing["archiveSha256"]:
        raise ValueError(f"Official archive differs from its pin: {name}")
    with zipfile.ZipFile(io.BytesIO(archive)) as container:
        members = [entry for entry in container.infolist() if Path(entry.filename).name == filename and not entry.is_dir()]
        limit = 512 * 1024 * 1024 if name == "mame" else 64 * 1024 * 1024
        if len(members) != 1 or members[0].file_size > limit:
            raise ValueError(f"Archive has no unique bounded expected library: {name}")
        library = container.read(members[0])
    pe = struct.unpack_from("<I", library, 0x3C)[0] if len(library) >= 64 else 0
    if not library.startswith(b"MZ") or pe + 6 > len(library) or library[pe:pe + 4] != b"PE\x00\x00" or struct.unpack_from("<H", library, pe + 4)[0] != 0x8664:
        raise ValueError(f"Official library is not a Windows x86_64 PE image: {name}")
    if not pin and sha(library) != existing["librarySha256"]:
        raise ValueError(f"Official library differs from its pin: {name}")
    if not archive_path.exists():
        archive_path.write_bytes(archive)
    if not dll_path.exists() or sha(dll_path.read_bytes()) != sha(library):
        dll_path.write_bytes(library)
    if not pin:
        return name, prior
    info_path = cache / (name + "_libretro.info")
    if info_path.exists():
        info = info_path.read_bytes()
    elif offline:
        raise ValueError(f"Missing official info metadata: {name}")
    else:
        info, _ = download(INFO + name + "_libretro.info"); info_path.write_bytes(info)
    match = re.search(r'^license\s*=\s*"([^"]+)"', info.decode("utf-8"), re.MULTILINE)
    if not match:
        raise ValueError(f"Official info has no license field: {name}")
    entry = {
        "systems": games,
        "purpose": "diagnostic-only" if name == "2048" else "emulation",
        "renderer": "opengl" if name == "mupen64plus_next" else "software",
        "source": "https://github.com/" + repository,
        "sourceRepositoryEvidence": "https://raw.githubusercontent.com/libretro/libretro-super/master/rules.d/core-rules.sh",
        "sourceRevision": None,
        "sourceRevisionVerified": False,
        "releaseReady": False,
        "sourceRevisionNote": "Official rolling binary has no verified corresponding source revision; current source HEAD is not substituted. Requires exact corresponding source and license delivery before release.",
        "license": match.group(1),
        "licenseUrl": INFO + name + "_libretro.info",
        "documentation": "https://docs.libretro.com/library/" + documentation + "/",
        "officialMetadataUrl": INFO + name + "_libretro.info",
        "officialMetadataSha256": sha(info),
        "platforms": {"windows-x86_64": {
            "archive": archive_path.relative_to(workspace).as_posix(),
            "libraryName": filename,
            "url": BASE + filename + ".zip",
            "archiveBytes": len(archive),
            "archiveSha256": sha(archive),
            "libraryBytes": len(library),
            "librarySha256": sha(library),
            "upstreamLastModified": modified,
        }},
    }
    # A platform-label update does not invalidate evidence for unchanged bytes.
    if existing and existing.get("librarySha256") == sha(library):
        for key in ("binaryVersion", "exportProbe", "binaryReportedRevision", "binaryReportedRevisionResolved", "revisionResolutionEvidence"):
            if key in prior: entry[key] = prior[key]
    return name, entry


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--only", help="Comma-separated core ids; default all software cores and diagnostic 2048")
    parser.add_argument("--pin", action="store_true", help="Explicitly create/refresh development pins from cached/new official downloads")
    parser.add_argument("--offline", action="store_true", help="Verify existing cache without network")
    parser.add_argument("--record-probes", action="store_true", help="Bind our completed export probes and resolve self-reported git prefixes through GitHub's SHA-only API")
    args = parser.parse_args()
    repo = Path(__file__).resolve().parents[1]; workspace = repo.parent
    manifest_path = repo / "third-party/cores.json"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8")) if manifest_path.exists() else {"schema": 1, "cores": {}}
    names = args.only.split(",") if args.only else list(SPECS)
    if any(name not in SPECS for name in names):
        raise ValueError("Unknown core id")
    with ThreadPoolExecutor(max_workers=4) as executor:
        futures = [executor.submit(fetch_one, name, workspace, args.pin, manifest["cores"].get(name, {}), args.offline) for name in names]
        entries = [future.result() for future in futures]
    if args.pin:
        for name, entry in entries:
            manifest["cores"][name] = entry
        manifest["pinnedAtUtc"] = datetime.now(timezone.utc).isoformat()
        manifest["scope"] = "Windows x86_64 development candidate; exact source correspondence not yet verified; no proprietary BIOS or game ROMs"
        manifest["unsupportedSystems"] = {} if "mupen64plus_next" in manifest["cores"] else {"n64": "No N64 core has been pinned."}
        manifest_path.parent.mkdir(exist_ok=True)
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    if args.record_probes:
        report = json.loads((repo / "build/runtime-smoke/core-export-probes.json").read_text(encoding="utf-8"))
        for row in report:
            entry = manifest["cores"][row["core"]]
            if row["exitCode"] != 0 or row["librarySha256"] != entry["platforms"]["windows-x86_64"]["librarySha256"]:
                raise ValueError("Probe is not successful/bound to current library: " + row["core"])
            version = row["stdout"].split("\t")[1]
            entry["binaryVersion"] = version
            entry["exportProbe"] = {"apiVersion": 1, "normalExit": True, "gameCompatibilityTested": row["core"] in ("fceumm", "2048")}
            match = re.search(r"(?:^|[ -])g?([0-9a-f]{7,40})$", version)
            if not match:
                entry["binaryReportedRevision"] = None
                continue
            prefix = match.group(1)
            entry["binaryReportedRevision"] = prefix
            if args.offline:
                continue
            # GitHub's SHA-only representation returns no source/patch content.
            url = entry["source"].replace("https://github.com/", "https://api.github.com/repos/") + "/commits/" + prefix
            request = urllib.request.Request(url, headers={"User-Agent": "MuxiConsole-CorePin/1", "Accept": "application/vnd.github.sha"})
            with urllib.request.urlopen(request, timeout=30) as response:
                full = response.read(128).decode("ascii").strip()
            if not re.fullmatch(r"[0-9a-f]{40}", full) or not full.startswith(prefix):
                raise ValueError("Official revision prefix did not resolve uniquely: " + row["core"])
            entry["binaryReportedRevisionResolved"] = full
            entry["revisionResolutionEvidence"] = url
            # A binary's own reported hash does not establish corresponding source,
            # submodule versions, local modifications or reproducible build inputs.
        manifest_path.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for name, _ in entries:
        print(name + ": pinned" if args.pin else name + ": verified")


if __name__ == "__main__":
    main()
