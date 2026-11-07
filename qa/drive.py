"""Drive only an already running, hash-bound unified test run. Never launches or kills Minecraft."""
from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import time

REPO = Path(__file__).resolve().parents[1]
WORKSPACE = REPO.parent
sys.path.insert(0, str(WORKSPACE / "scripts/ops"))
import test_environment as environment


def sha(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for block in iter(lambda: source.read(32768), b""):
            digest.update(block)
    return digest.hexdigest()


def read_json(path):
    path = Path(path)
    if path.stat().st_size > 1024 * 1024:
        raise ValueError("Oversize QA metadata")
    return json.loads(path.read_text(encoding="utf-8-sig"))


class Driver:
    def __init__(self, run: Path, build: Path):
        supplied = run.absolute()
        self.root = supplied.resolve(strict=True)
        if supplied != self.root:
            raise ValueError("Linked run paths are forbidden")
        relative = self.root.relative_to(WORKSPACE / "test-environment").parts
        if len(relative) != 3 or not re.fullmatch(r"client-[1-9][0-9]*", relative[0]) or relative[1] != "runs":
            raise ValueError("QA only operates in test-environment/client-N/runs/name")
        self.build_path = build.resolve(strict=True)
        if REPO / "build/qa" not in self.build_path.parents:
            raise ValueError("QA build report must come from this independent repository")
        self.build = read_json(self.build_path)
        self.counter = time.time_ns() // 1000
        self.marker = self.verify()
        self.channel = self.root / "console-qa"

    def verify(self):
        environment.verify_slot_lease(self.root)
        marker = read_json(self.root / "local-mc-owner.json")
        if marker.get("projectRoot") not in (str(REPO), str(WORKSPACE / "better-mc-remake")) or marker.get("instanceRoot") != str(self.root) or not marker.get("offlineLoopback") or marker.get("productionMutation"):
            raise ValueError("Independent loopback runner ownership mismatch")
        if not re.fullmatch(r"[a-f0-9]{32}", marker.get("runId", "")) or set(marker.get("roles", [])) != {"server", "host", "guest"}:
            raise ValueError("Expected a two-real-client run")
        product = self.build["product"]
        for role in ("server", "host", "guest"):
            process = marker["processes"][role]
            actual_created = environment.process_created(process["pid"])
            if actual_created is None or abs(actual_created - process["processCreateTime"]) > 0.1 or Path(process["root"]).resolve() != self.root / role:
                raise ValueError("Actual role PID generation/root differs: " + role)
            expected_qa = self.build["artifacts"]["server" if role == "server" else "client"]
            for artifact in (product, expected_qa):
                path = self.root / role / "mods" / Path(artifact["path"]).name
                if not path.is_file() or sha(path) != artifact["sha256"]:
                    raise ValueError("Actual role product/QA bytes differ: " + str(path))
                rows = [row for row in marker["inputArtifacts"] if row.get("file") == path.name and row.get("sha256") == artifact["sha256"]]
                scope = "both" if artifact is product else "server" if role == "server" else "client"
                if not any(row.get("scope") == scope for row in rows):
                    raise ValueError("Runner did not pin this exact artifact/scope")
            opposite = self.build["artifacts"]["client" if role == "server" else "server"]
            if (self.root / role / "mods" / Path(opposite["path"]).name).exists():
                raise ValueError("Opposite-side QA mod must not be installed")
        if hasattr(self, "marker") and marker["runId"] != self.marker["runId"]:
            raise ValueError("Run identity changed")
        return marker

    def command(self, role, action, body=None, timeout=30):
        if role not in ("server", "host", "guest") or not 1 <= timeout <= 60:
            raise ValueError("Invalid QA command role/timeout")
        marker = self.verify()
        self.counter += 1
        qa = self.build["artifacts"]["server" if role == "server" else "client"]
        payload = dict(body or {})
        payload.update(id=self.counter, runId=marker["runId"], pid=marker["processes"][role]["pid"], action=action,
                       productSha256=self.build["product"]["sha256"], qaSha256=qa["sha256"])
        self.channel.mkdir(exist_ok=True)
        temporary = self.channel / ("command-" + role + ".tmp")
        temporary.write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
        os.replace(temporary, self.channel / ("command-" + role + ".json"))
        deadline = time.monotonic() + timeout
        result_path = self.channel / ("reply-" + role + ".json")
        while time.monotonic() < deadline:
            if (self.root / "stop-request.json").exists():
                raise RuntimeError("Unified runner requested normal stop")
            if result_path.exists():
                result = read_json(result_path)
                if result.get("id") == self.counter:
                    if result.get("runId") != marker["runId"] or result.get("pid") != payload["pid"] or result.get("role") != role or result.get("productSha256") != payload["productSha256"] or result.get("qaSha256") != payload["qaSha256"]:
                        raise ValueError("QA response identity/hash differs")
                    if not result.get("ok"):
                        raise RuntimeError(result.get("error", "QA command failed"))
                    return result["result"]
            time.sleep(0.1)
        raise TimeoutError("QA command timed out: " + role + "/" + action)

    def prepare(self):
        result = self.command("server", "prepare")
        if len(result["players"]) != 2 or result.get("cartridgesMintedByQa") != 0 or not result.get("scenePrepared"):
            raise ValueError("Server fixture did not prepare exactly two real players without minting")
        clients = {role: self.command(role, "observe") for role in ("host", "guest")}
        if not all(row.get("connected") for row in clients.values()):
            raise ValueError("Both actual clients must remain connected")
        return {"fixture": result, "clients": clients, "scenarioComplete": False, "physicalOSInput": False, "visualAcceptance": False}

    def wait_for(self, role, predicate, label, timeout=60):
        deadline = time.monotonic() + timeout
        last = None
        while time.monotonic() < deadline:
            last = self.command(role, "observe")
            if predicate(last):
                return last
            time.sleep(0.2)
        raise TimeoutError(label + ": " + json.dumps(last, ensure_ascii=False))

    def capture(self, role, name):
        self.command(role, "screenshot", {"name": name})
        self.wait_for(role, lambda value: value.get("screenshot") == name + ".png", "native screenshot completion")
        image = self.root / role / "screenshots" / (name + ".png")
        if not image.is_file() or image.stat().st_size < 1000:
            raise ValueError("Native screenshot missing/empty")
        return {"path": str(image), "sha256": sha(image), "visuallyReviewed": False}

    def close_sessions(self):
        closures, errors = {}, []
        resume_host_after_epoch = None
        for role in ("guest", "host"):
            try:
                before = self.command(role, "observe")
                if role == "guest" and before["room"]:
                    prior_host = self.command("host", "observe")
                    if prior_host["room"] == before["room"]:
                        resume_host_after_epoch = prior_host["epoch"]
                if role == "host" and resume_host_after_epoch is not None:
                    before = self.wait_for(role, lambda value: value["epoch"] > resume_host_after_epoch and value["ready"] and value["workerRunning"], "host receives the new epoch after guest saved leave")
                elif before["room"] and role == "host":
                    before = self.wait_for(role, lambda value: value["ready"] and value["workerRunning"], "host resumes after guest saved leave")
                self.command(role, "leave")
                closed = self.wait_for(role, lambda value: not value["workerRunning"] and not value["workers"], "normal product worker exit")
                closures[role] = {key: closed[key] for key in ("normalWorkerExits", "forcedWorkerExits", "failedWorkerExits", "savedStates", "savedStateHash")}
                if closed["normalWorkerExits"] < 1 or closed["forcedWorkerExits"] != 0 or closed["failedWorkerExits"] != 0:
                    raise ValueError("Product observed a missing normal exit or a forced/failed worker exit")
                if before["room"] and closed["normalWorkerExits"] <= before["normalWorkerExits"]:
                    raise ValueError("The active native worker did not record its own normal exit")
                if before["room"] and (role == "host" or before["room"].startswith("handheld/")):
                    if closed["savedStates"] <= before["savedStates"] or not re.fullmatch(r"[a-f0-9]{64}", closed["savedStateHash"]):
                        raise ValueError("Normal leader leave did not commit its final native checkpoint")
            except Exception as error:
                errors.append(str(error))
        return {"workerClosures": closures, "cleanupErrors": errors, "workersGone": not errors,
                "normalWorkerExitVerified": not errors and len(closures) == 2, "normalExitRequiresRootAudit": bool(errors)}

    def scenario(self, continue_after_drop=False, drop_x=None):
        evidence = {"runId": self.marker["runId"], "physicalOSInput": False, "visualAcceptance": False,
                    "syntheticServerPlayers": False, "cartridgesMintedByQa": 0, "screenshots": [], "scenarioComplete": False}
        if continue_after_drop:
            evidence = read_json(self.channel / "scenario-result.json")
            if evidence.get("runId") != self.marker["runId"] or not evidence.get("twoIndependentInstancesOneContentObject") or drop_x is None:
                raise ValueError("Continuation requires this same run's completed burn evidence and observed drop position")
            preserved = self.channel / "scenario-result-initial.json"
            if not preserved.exists():
                preserved.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
            evidence["continuedAfterPickupPositionFailure"] = True
            prepared = {"clients": {role: self.command(role, "observe") for role in ("host", "guest")}}
        else:
            prepared = self.prepare()
        host_name = prepared["clients"]["host"]["playerName"]
        guest_name = prepared["clients"]["guest"]["playerName"]
        host = lambda action, body=None: self.command("host", action, body)
        guest = lambda action, body=None: self.command("guest", action, body)
        move = lambda name, x, z: self.command("server", "position", {"player": name, "x": x, "z": z})
        try:
            if not continue_after_drop:
                move(host_name, 10.5, 13); move(guest_name, 16, 14)
                evidence["screenshots"].append(self.capture("host", "console-desktop"))
                move(host_name, 8.5, 11.5); move(guest_name, 10.5, 11.5)
                host("slot", {"slot": 8}); host("use_block", {"x": 8})
                self.wait_for("host", lambda value: value["screen"].endswith("WriterScreen"), "real writer screen")
                host("rights_negative")
                self.wait_for("host", lambda value: value.get("lastCode") == "rights_required", "server rejects omitted rights declaration")
                if self.command("server", "observe")["pendingUploads"] != 0:
                    raise ValueError("Unconfirmed upload allocated server state")
                evidence["rightsDefaultRejected"] = True
                host("writer_drop")
                if host("writer_confirm").get("confirmed") is not True:
                    raise ValueError("Actual rights checkbox did not confirm")
                if host("writer_drop").get("confirmed") is not False:
                    raise ValueError("Selecting a file must reset the actual rights checkbox")
                evidence["fileChangeResetsCheckbox"] = True
                host("writer_confirm")
                evidence["screenshots"].append(self.capture("host", "console-writer"))
                host("button", {"text": "上传并刻录"})
                self.wait_for("host", lambda value: len(value.get("cards", [])) == 1, "first UI/network cartridge burn")
                host("writer_drop"); host("writer_confirm"); host("button", {"text": "上传并刻录"})
                burned = self.wait_for("host", lambda value: len(value.get("cards", [])) == 2, "second independent cartridge burn")
                cards = burned["cards"]
                if len({card["instance"] for card in cards}) != 2 or {card["hash"] for card in cards} != {self.build["diagnosticRom"]["sha256"]}:
                    raise ValueError("Cartridge instance identity/content dedup mismatch")
                server = self.command("server", "observe")
                if server["contentBytes"] != Path(self.build["diagnosticRom"]["path"]).stat().st_size:
                    raise ValueError("Server stored duplicate ROM payloads")
                evidence["twoIndependentInstancesOneContentObject"] = cards
                host("close_screen")
                for card in cards:
                    host("slot", {"slot": card["slot"]}); host("drop")
            move(host_name, 16, 14); move(guest_name, drop_x if continue_after_drop else 8.5, 10)
            self.wait_for("guest", lambda value: len(value.get("cards", [])) == 2, "normal dropped-item pickup by recipient")
            self.wait_for("host", lambda value: len(value.get("cards", [])) == 0, "old holder no longer possesses any copy")
            host("probe"); guest("probe")
            self.wait_for("host", lambda value: value.get("probe") == "denied", "actual server denies old holder download")
            self.wait_for("guest", lambda value: value.get("probe") == "granted", "actual server grants new holder download")
            evidence["transferDownloadAuthorization"] = {"oldHolder": "denied", "newHolder": "granted", "cachedDataBypassed": True}
            move(host_name, 10.5, 11.5); move(guest_name, 11.5, 11.5)
            recipient = guest("observe")
            guest("slot", {"slot": recipient["cards"][0]["slot"]}); guest("use_block", {"x": 10})
            host("slot", {"slot": 1}); host("use_block", {"x": 10}); host("use_block", {"x": 10})
            self.wait_for("server", lambda value: any(device.get("ports") == 2 and "card" in device for device in value["devices"]), "physical cartridge/controller installation")
            host("slot", {"slot": 8}); host("use_block", {"x": 10})
            self.wait_for("host", lambda value: value["screen"].endswith("DeviceScreen"), "real device screen")
            host("button", {"text": "启动 / 停止"}); host("button", {"text": "连接附近屏幕"})
            host("button", {"text": "按键与手柄"})
            self.wait_for("host", lambda value: value["screen"].endswith("ControlsScreen"), "real controls screen")
            evidence["screenshots"].append(self.capture("host", "console-controls"))
            host("button", {"text": "返回"}); host("button", {"text": "P1 加入"})
            self.wait_for("host", lambda value: value["ready"] and value["workerRunning"], "P1 native worker ready")
            guest("slot", {"slot": 8}); guest("use_block", {"x": 10})
            self.wait_for("guest", lambda value: value["screen"].endswith("DeviceScreen"), "P2 real device screen")
            guest("button", {"text": "P2 加入"})
            self.wait_for("host", lambda value: value["ready"] and value["workerRunning"] and value["frames"] >= 30 and len(value["workers"]) == 1, "P1 restarted for two-player epoch")
            self.wait_for("guest", lambda value: value["ready"] and value["workerRunning"] and value["frames"] >= 30 and len(value["workers"]) == 1, "P2 native worker ready")
            initial_host, initial_guest = host("observe"), guest("observe")
            if initial_host["epoch"] != initial_guest["epoch"] or initial_host["crc"] != initial_guest["crc"] or (initial_host["port"], initial_guest["port"]) != (0, 1):
                raise ValueError("Initial two-port native picture differs")
            restored_hash = initial_host["restoredStateHash"]
            if not re.fullmatch(r"[a-f0-9]{64}", restored_hash) or initial_host["restoredStates"] < 1 or initial_guest["restoredStates"] < 1:
                raise ValueError("Both actual native runtimes must restore the handover checkpoint")
            if initial_guest["restoredStateHash"] != restored_hash or initial_host["sessionStateHash"] != restored_hash or initial_guest["sessionStateHash"] != restored_hash:
                raise ValueError("The two native restores differ from the actual server start metadata")
            server_saved = self.command("server", "observe")["states"]
            if not server_saved:
                raise ValueError("The authoritative server vault has no checkpoint for the active cartridge")
            if initial_host["savedStates"] < 1:
                raise ValueError("Host handover must first commit a real emulator checkpoint")
            evidence["serverCheckpointRestored"] = {"hash": restored_hash, "hostRestored": initial_host["restoredStates"],
                                                     "guestRestored": initial_guest["restoredStates"], "hostSaved": initial_host["savedStates"], "vault": server_saved,
                                                     "serverStartHash": initial_host["sessionStateHash"],
                                                     "vaultHeadAdvancedAfterRestore": not any(row["hash"] == restored_hash for row in server_saved)}
            joins = {}
            for role in ("host", "guest"):
                before = self.command(role, "observe")
                self.command(role, "repeat_join")
                after = self.wait_for(role, lambda value: value["ready"] and value["serverTick"] >= before["serverTick"] + 4, "same-epoch repeated join keeps running")
                if after["epoch"] != before["epoch"] or after["room"] != before["room"] or after["workers"] != before["workers"] or after["restoredStates"] != before["restoredStates"] or after["normalWorkerExits"] != before["normalWorkerExits"]:
                    raise ValueError("Repeated join restarted or reloaded a native worker")
                delta = after["frames"] - before["frames"]
                if delta < 1 or delta > (after["serverTick"] - before["serverTick"]) * 6 + 12:
                    raise ValueError("Repeated join reset or jumped the authoritative frame sequence")
                joins[role] = {"epoch": after["epoch"], "workers": after["workers"], "beforeFrame": before["frames"], "afterFrame": after["frames"]}
            evidence["sameEpochJoinIdempotent"] = joins
            baseline = initial_host["crc"]
            host("input_source", {"buttons": 256})
            p1 = self.wait_for("host", lambda value: value["crc"] and value["crc"] != baseline, "P1 A changes native diagnostic picture")
            host("input_source", {"buttons": 0})
            self.wait_for("guest", lambda value: value["crc"] == p1["crc"], "P1 input rendered identically by P2")
            guest("input_source", {"buttons": 256})
            p2 = self.wait_for("guest", lambda value: value["crc"] and value["crc"] != p1["crc"], "P2 A changes its independent half of native picture")
            guest("input_source", {"buttons": 0})
            self.wait_for("host", lambda value: value["crc"] == p2["crc"], "P2 input rendered identically by P1")
            evidence["nativeTwoPortCrc"] = {"baseline": baseline, "p1": p1["crc"], "p2": p2["crc"], "inputFrontendCallback": True}
            evidence["screenshots"].append(self.capture("host", "console-fullscreen"))
            host("toggle_tv")
            evidence["screenshots"].append(self.capture("host", "console-crt"))
            desktop_closure = self.close_sessions()
            evidence["desktopWorkerClosures"] = desktop_closure
            if not desktop_closure["normalWorkerExitVerified"]:
                raise ValueError("Desktop sessions must finish normally before the real handheld flow")
            spare = guest("observe")["cards"]
            if len(spare) != 1 or spare[0]["slot"] == 2:
                raise ValueError("The guest must still hold the second genuine spare cartridge before loading")
            spare_id = spare[0]["instance"]
            guest("handheld_load")
            loaded = self.wait_for("guest", lambda value: any(card["slot"] == 2 and card["instance"] == spare_id for card in value.get("cards", [])), "vanilla sneak/use loads the real spare cartridge into slot-2 handheld")
            if len(loaded["cards"]) != 1:
                raise ValueError("Loading a handheld must consume the loose cartridge rather than duplicate it")
            evidence["screenshots"].append(self.capture("guest", "console-handheld-model"))
            guest("handheld_use")
            portable = self.wait_for("guest", lambda value: value["room"] == "handheld/" + spare_id and value["port"] == 0 and value["ready"] and value["workerRunning"] and value["frames"] >= 30 and len(value["workers"]) == 1, "ordinary vanilla handheld use starts its real P1 worker")
            portable_crc = portable["crc"]
            guest("input_source", {"buttons": 256})
            portable_input = self.wait_for("guest", lambda value: value["room"] == portable["room"] and value["crc"] and value["crc"] != portable_crc, "handheld P1 frontend input changes the native picture")
            guest("input_source", {"buttons": 0})
            evidence["screenshots"].append(self.capture("guest", "console-handheld-game"))
            evidence["handheld"] = {"instance": spare_id, "room": portable["room"], "baselineCrc": portable_crc,
                                    "p1Crc": portable_input["crc"], "workers": portable["workers"],
                                    "vanillaSneakUse": True, "physicalOSInput": False, "looseCartridgeConsumed": True}
            evidence["scenarioComplete"] = True
        finally:
            # Product leave performs normal worker close; unified runner alone owns Java/server shutdown.
            closure = self.close_sessions()
            evidence.update(closure)
            if evidence.get("handheld") and closure["normalWorkerExitVerified"]:
                current = self.command("server", "observe")["states"]
                saved_hash = closure["workerClosures"]["guest"]["savedStateHash"]
                matches = [row for row in current if row["card"] == evidence["handheld"]["instance"] and row["hash"] == saved_hash]
                if not matches:
                    evidence["cleanupErrors"].append("The genuine handheld instance has no matching committed server checkpoint")
                    evidence["normalWorkerExitVerified"] = False
                else:
                    evidence["handheld"]["serverCheckpoint"] = matches[0]
            if not evidence["workersGone"]:
                evidence["scenarioComplete"] = False
            if not evidence["normalWorkerExitVerified"]:
                evidence["scenarioComplete"] = False
            (self.channel / "scenario-result.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
        return evidence


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--build", type=Path, required=True)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--prepare", action="store_true")
    mode.add_argument("--scenario", action="store_true")
    mode.add_argument("--continue-after-drop", action="store_true")
    parser.add_argument("--drop-x", type=float)
    mode.add_argument("--action")
    parser.add_argument("--role", choices=("server", "host", "guest"), default="server")
    parser.add_argument("--body", default="{}")
    options = parser.parse_args()
    driver = Driver(options.run, options.build)
    if options.check:
        result = {"runId": driver.marker["runId"], "verifiedActualProductAndQaHashes": True, "roles": driver.marker["roles"], "minecraftScenarioPassed": False}
    elif options.prepare:
        result = driver.prepare()
    elif options.scenario:
        result = driver.scenario()
    elif options.continue_after_drop:
        result = driver.scenario(True, options.drop_x)
    else:
        result = driver.command(options.role, options.action, json.loads(options.body))
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
