"""Deterministic companion-resource packaging; no SDK, native execution or downloads."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import shutil
import zipfile

RUNTIME_VERSION = "1.0.2"
SOURCE_ATTACHMENT = f"muxi-console-runtime-{RUNTIME_VERSION}-sources.zip"
SOURCE_RELEASE_URL = "https://github.com/muxigame/muxi-console/releases/tag/v0.2.4"
SOURCE_DOWNLOAD_URL = SOURCE_RELEASE_URL.replace("/tag/", "/download/") + "/" + SOURCE_ATTACHMENT
SOURCE_METHOD = "complete source attachment alongside runtime binary"
PAYLOAD_PREFIXES = ("worker/", "cores/", "system/")
EXTRACT_LISTS = {"META-INF/muxi/extract-hashes.json", "META-INF/muxi/extract-sizes.json"}
RUNTIME_METADATA = EXTRACT_LISTS | {
    "META-INF/muxi/worker-dependencies.json", "META-INF/muxi/system-resources.json",
    "META-INF/muxi/cores.json", "META-INF/muxi/sameboy-link.json",
    "META-INF/muxi/mgba-link.json", "META-INF/muxi/melondsds-link.json",
    "META-INF/muxi/azahar-link.json", "META-INF/muxi/OWNERSHIP.md", "META-INF/LICENSE",
}
SERVER_IDENTITIES = {
    "META-INF/muxi/worker-dependencies.json", "META-INF/muxi/sameboy-link.json",
    "META-INF/muxi/mgba-link.json", "META-INF/muxi/melondsds-link.json", "META-INF/muxi/azahar-link.json",
}


def sha(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def record(path: Path) -> dict:
    return {"path": str(path), "bytes": path.stat().st_size, "sha256": sha(path)}


def runtime_toml() -> bytes:
    # No main-mod version, build timestamp or machine path may enter this archive.
    return f'''modLoader="lowcodefml"
loaderVersion="[4,)"
license="MIT; third-party components retain their own licenses"
[[mods]]
modId="muxi_console_runtime"
version="{RUNTIME_VERSION}"
displayName="PlayStation Runtime"
authors="Muxigame"
description="Pinned emulator cores, isolated worker dependencies and system resources."
[[dependencies.muxi_console_runtime]]
modId="neoforge"
type="required"
versionRange="[21.1.250,)"
ordering="NONE"
side="CLIENT"
[[dependencies.muxi_console_runtime]]
modId="minecraft"
type="required"
versionRange="[1.21.1]"
ordering="NONE"
side="CLIENT"
'''.encode("utf-8")


def write_jar(path: Path, resources: dict[str, bytes]) -> None:
    with zipfile.ZipFile(path, "x", zipfile.ZIP_DEFLATED) as jar:
        for name, body in sorted(resources.items()):
            if name.startswith("/") or "\\" in name or any(p in {"", ".", ".."} for p in name.split("/")):
                raise ValueError("Unsafe packaged resource path")
            info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_DEFLATED
            jar.writestr(info, body)


def validate_payload(resources: dict[str, bytes]) -> None:
    hashes = json.loads(resources["META-INF/muxi/extract-hashes.json"])
    sizes = json.loads(resources["META-INF/muxi/extract-sizes.json"])
    actual = {k for k in resources if k.startswith(PAYLOAD_PREFIXES)}
    if set(hashes) != actual or set(sizes) != actual:
        raise ValueError("Runtime extraction lists do not cover the exact payload")
    for name in actual:
        body = resources[name]
        if hashlib.sha256(body).hexdigest() != hashes[name] or len(body) != sizes[name]:
            raise ValueError("Runtime payload differs from its extraction identity: " + name)


def package_artifacts(artifact: Path, resources: dict[str, bytes], source_delivery: dict | None) -> dict:
    """Write the small main mod, class-free runtime and optional complete source attachment."""
    validate_payload(resources)
    if not RUNTIME_METADATA.issubset(resources):
        raise ValueError("Runtime identity/license metadata is incomplete")
    if "META-INF/muxi/source-bundle.zip" in resources:
        raise ValueError("Corresponding source must be a release attachment, not a runtime payload")
    main = dict(resources)
    runtime = {}
    for name, body in resources.items():
        if name.startswith(PAYLOAD_PREFIXES) or name in RUNTIME_METADATA or name.startswith("META-INF/muxi/licenses/"):
            runtime[name] = body
        # LinkCoreIdentity on the dedicated server reads these five small identity records.
        if (name.startswith(PAYLOAD_PREFIXES) or name in RUNTIME_METADATA - SERVER_IDENTITIES - {
                "META-INF/LICENSE", "META-INF/muxi/OWNERSHIP.md"
            } or name.startswith("META-INF/muxi/licenses/")):
            del main[name]
    runtime["META-INF/neoforge.mods.toml"] = runtime_toml()
    runtime["META-INF/MANIFEST.MF"] = b"Manifest-Version: 1.0\r\nAutomatic-Module-Name: muxi.console.runtime.resources\r\n\r\n"
    source_attachment = None
    if source_delivery is not None:
        manifest = source_delivery["manifest"]
        delivery = manifest.get("delivery", {})
        if (delivery.get("method") != SOURCE_METHOD or delivery.get("attachment") != SOURCE_ATTACHMENT
                or delivery.get("releaseUrl") != SOURCE_RELEASE_URL or not delivery.get("notAnOnRequestOffer")):
            raise ValueError("Source delivery does not match this independently versioned runtime")
        # Preserve the exact manifest bytes whose hash was verified by source_delivery().
        manifest_path = Path(__file__).resolve().parents[1] / "third-party/source_manifest.json"
        body = manifest_path.read_bytes()
        if hashlib.sha256(body).hexdigest() != source_delivery["manifestSha256"]:
            raise ValueError("Verified source manifest changed during packaging")
        source = Path(source_delivery["path"])
        if source.stat().st_size != source_delivery["bytes"] or sha(source) != source_delivery["sha256"]:
            raise ValueError("Verified source bundle changed during packaging")
        destination = artifact.parent / SOURCE_ATTACHMENT
        with source.open("rb") as incoming, destination.open("xb") as outgoing:
            shutil.copyfileobj(incoming, outgoing, 1024 * 1024)
        source_attachment = record(destination)
        if source_attachment["bytes"] != source_delivery["bytes"] or source_attachment["sha256"] != source_delivery["sha256"]:
            raise ValueError("Copied source attachment differs from its verified source")
        runtime["META-INF/muxi/source_manifest.json"] = body
        runtime["META-INF/muxi/SOURCE-DELIVERY.txt"] = (
            f"PlayStation Runtime {RUNTIME_VERSION}\n\n"
            "Original project: https://github.com/muxigame/muxi-console (MIT).\n"
            "Third-party components retain their own licenses; see licenses/ and source_manifest.json.\n"
            "Complete corresponding core and worker sources accompany this runtime as a separate attachment\n"
            "in the same public release. They are not downloaded or loaded by Minecraft.\n"
            f"Release: {SOURCE_RELEASE_URL}\nDownload: {SOURCE_DOWNLOAD_URL}\n"
            f"Attachment: {SOURCE_ATTACHMENT}\nBytes: {source_attachment['bytes']}\n"
            f"SHA256: {source_attachment['sha256']}\n"
            "Extract the complete ZIP and follow README-SOURCES.txt and the included build instructions.\n"
        ).encode("utf-8")
    else:
        runtime["META-INF/muxi/SOURCE-DELIVERY.txt"] = (
            "Development runtime only. Complete source delivery has not been verified for this package.\n"
            "Do not publish this development artifact; use tools/build.py --release.\n"
        ).encode("utf-8")
    if any(name.endswith(".class") for name in runtime):
        raise ValueError("The lowcodefml runtime resource mod must contain no entrypoint classes")
    permitted = RUNTIME_METADATA | {
        "META-INF/neoforge.mods.toml", "META-INF/MANIFEST.MF",
        "META-INF/muxi/source_manifest.json", "META-INF/muxi/SOURCE-DELIVERY.txt"
    }
    if any(name not in permitted and not name.startswith(PAYLOAD_PREFIXES + ("META-INF/muxi/licenses/",)) for name in runtime):
        raise ValueError("Product resources leaked into the runtime companion")
    if any(name.startswith(PAYLOAD_PREFIXES) for name in main):
        raise ValueError("Native/worker/system payload leaked into the small main mod")
    validate_payload(runtime)
    runtime_artifact = artifact.parent / f"muxi-console-runtime-{RUNTIME_VERSION}+mc1.21.1.jar"
    write_jar(runtime_artifact, runtime)
    write_jar(artifact, main)
    return {"runtimeArtifact": {**record(runtime_artifact), "version": RUNTIME_VERSION},
            "sourceAttachment": source_attachment}
