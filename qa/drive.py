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
    if os.name == "nt":
        # Atomic Java reply replacement must remain possible while this reader holds
        # the old file. The default CRT reader shares READ/WRITE but denies DELETE.
        # This is a read-only handle; it neither changes ACLs nor retries an action.
        import ctypes
        from ctypes import wintypes
        import msvcrt
        kernel=ctypes.WinDLL("kernel32",use_last_error=True)
        create=kernel.CreateFileW
        create.argtypes=[wintypes.LPCWSTR,wintypes.DWORD,wintypes.DWORD,ctypes.c_void_p,wintypes.DWORD,wintypes.DWORD,wintypes.HANDLE]
        create.restype=wintypes.HANDLE
        handle=create(str(path),0x80000000,1|2|4,None,3,0x80,None)
        if handle==ctypes.c_void_p(-1).value:raise ctypes.WinError(ctypes.get_last_error())
        try:descriptor=msvcrt.open_osfhandle(int(handle),os.O_RDONLY|os.O_BINARY)
        except BaseException:
            kernel.CloseHandle.argtypes=[wintypes.HANDLE];kernel.CloseHandle(handle);raise
        with os.fdopen(descriptor,"rb")as source:content=source.read(1024*1024+1)
    else:content=path.read_bytes()
    if len(content)>1024*1024:raise ValueError("Oversize QA metadata")
    return json.loads(content.decode("utf-8-sig"))


class Driver:
    def __init__(self, run: Path, build: Path, *, allow_single=False, expect_jei=None):
        self.allow_single = allow_single
        self.expect_jei = expect_jei
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
        self.observation_timeouts=[]
        self.marker = self.verify()
        self.channel = self.root / "console-qa"

    def verify(self):
        environment.verify_slot_lease(self.root)
        marker = read_json(self.root / "local-mc-owner.json")
        if marker.get("projectRoot") not in (str(REPO), str(WORKSPACE / "better-mc-remake")) or marker.get("instanceRoot") != str(self.root) or not marker.get("offlineLoopback") or marker.get("productionMutation"):
            raise ValueError("Independent loopback runner ownership mismatch")
        self.roles = tuple(role for role in ("server", "host", "guest") if role in marker.get("roles", []))
        expected_roles = {"server", "host"} if self.allow_single else {"server", "host", "guest"}
        if not re.fullmatch(r"[a-f0-9]{32}", marker.get("runId", "")) or set(marker.get("roles", [])) != expected_roles:
            raise ValueError("Expected a two-real-client run")
        product = self.build["product"]
        for role in self.roles:
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
                scope = "server" if role == "server" else "client"
                accepted_scopes = {"both", scope} if artifact is product else {scope}
                if not any(row.get("scope") in accepted_scopes for row in rows):
                    raise ValueError("Runner did not pin this exact artifact/scope")
            opposite = self.build["artifacts"]["client" if role == "server" else "server"]
            if (self.root / role / "mods" / Path(opposite["path"]).name).exists():
                raise ValueError("Opposite-side QA mod must not be installed")
            for key, installed, scope in (("runtimeArtifact", role != "server", "client"),
                                           ("jeiArtifact", self.expect_jei is not False, "both")):
                extra = self.build.get(key)
                if extra is None:
                    continue
                path = self.root / role / "mods" / Path(extra["path"]).name
                if installed:
                    if not path.is_file() or sha(path) != extra["sha256"] or path.stat().st_size != extra["bytes"]:
                        raise ValueError("Actual split runtime/JEI bytes differ: " + str(path))
                    accepted_extra_scopes = {"both", "server" if role == "server" else "client"} if key == "jeiArtifact" else {scope}
                    if not any(r.get("file") == path.name and r.get("sha256") == extra["sha256"] and r.get("scope") in accepted_extra_scopes for r in marker["inputArtifacts"]):
                        raise ValueError("Runner did not pin the exact split/JEI scope")
                elif path.exists():
                    raise ValueError("Unexpected runtime/JEI in explicitly excluded role: " + role)
        if hasattr(self, "marker") and marker["runId"] != self.marker["runId"]:
            raise ValueError("Run identity changed")
        return marker

    def command(self, role, action, body=None, timeout=60):
        if role not in self.roles or not 1 <= timeout <= 60:
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
        deadline = time.monotonic() + timeout
        while True:
            try:
                os.replace(temporary, self.channel / ("command-" + role + ".json"))
                break
            except PermissionError:
                # A Java read handle can briefly deny DELETE sharing on NTFS.
                # Retry the exact same CREATE_NEW payload/id, never permissions
                # or another channel, inside this command's original budget.
                if time.monotonic()>=deadline:
                    raise
                if (self.root/"stop-request.json").exists():
                    raise RuntimeError("Unified runner requested normal stop")
                time.sleep(.1)
        result_path = self.channel / ("reply-" + role + ".json")
        while time.monotonic() < deadline:
            if (self.root / "stop-request.json").exists():
                raise RuntimeError("Unified runner requested normal stop")
            if result_path.exists():
                try:
                    result = read_json(result_path)
                except (PermissionError, FileNotFoundError):
                    # Windows may deny a read while the same reply is atomically
                    # replaced. Only this already-owned reply is retried, within
                    # the original deadline; identity checks still run below.
                    time.sleep(0.1)
                    continue
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
            try:
                last = self.command(role, "observe")
            except TimeoutError as error:
                self.observation_timeouts.append({"role":role,"label":label,"error":str(error)})
                if time.monotonic()>=deadline:
                    raise
                continue
            if predicate(last):
                return last
            time.sleep(0.2)
        raise TimeoutError(label + ": " + json.dumps(last, ensure_ascii=False))

    def capture(self, role, name):
        image = self.root / role / "screenshots" / (name + ".png")
        if image.exists():
            raise ValueError("A native screenshot with this name already exists; preserve the original and use a fresh run")
        self.command(role, "screenshot", {"name": name})
        self.wait_for(role, lambda value: value.get("screenshot") == name + ".png", "native screenshot completion")
        if not image.is_file() or image.stat().st_size < 1000:
            raise ValueError("Native screenshot missing/empty")
        header = image.read_bytes()[:24]
        if header[:8] != b"\x89PNG\r\n\x1a\n":
            raise ValueError("Minecraft did not return an original PNG")
        width, height = int.from_bytes(header[16:20], "big"), int.from_bytes(header[20:24], "big")
        if width < 640 or height < 360:
            raise ValueError("Native screenshot is below the model review resolution")
        return {"path": str(image), "sha256": sha(image), "width": width, "height": height,
                "nativeMinecraftFramebuffer": True, "visuallyReviewed": False}

    def settle(self, role, ticks=12):
        before = self.command(role, "observe")["clientTicks"]
        return self.wait_for(role, lambda value: value["clientTicks"] >= before + ticks, "actual client ticks after camera/interaction")

    def model_review(self, held_only=False):
        output = self.channel / ("held-review-result.json" if held_only else "model-review-result.json")
        if output.exists():
            raise ValueError("Model review results already exist; preserve failures and use a fresh run")
        plan = self.build["modelReviewPlan"]
        if sha(plan["path"]) != plan["sha256"]:
            raise ValueError("Build-pinned model review plan changed")
        stations = read_json(plan["path"])["stations"]
        evidence = {"runId": self.marker["runId"], "productSha256": self.build["product"]["sha256"],
                    "planSha256": plan["sha256"], "screenshots": [], "physicalOSInput": False,
                    "visualAcceptance": False, "nativeCaptureComplete": False, "scenarioComplete": False}
        try:
            self.prepare()
            evidence["fixture"] = self.command("server", "model_prepare")
            for station in ([] if held_only else [{"cmd": 0, "native_screenshot": "00-gallery-overview.png"}, *stations]):
                camera = self.command("server", "model_camera", {"cmd": station["cmd"]})
                self.command("host", "slot", {"slot": 8})
                self.command("host", "review_view", {"held": False})
                self.settle("host", 16)
                row = self.capture("host", Path(station["native_screenshot"]).stem)
                row.update(cmd=station["cmd"], camera=camera)
                evidence["screenshots"].append(row)
            for station in stations:
                if station["form_factor"] not in ("handheld", "viewer"):
                    continue
                for hand in ("main", "off"):
                    camera = self.command("server", "model_camera", {"cmd": station["cmd"], "held": True, "hand": hand})
                    self.command("host", "slot", {"slot": 7 if hand == "main" else 8})
                    self.command("host", "review_view", {"held": True})
                    key = "heldModel" if hand == "main" else "offhandModel"
                    self.wait_for("host", lambda v: v[key] == station["cmd"], "actual handheld CMD synchronized")
                    self.settle("host", 16)
                    name = "23-handhelds-first-person" if station["cmd"] == 3 and hand == "main" else Path(station["native_screenshot"]).stem + ("-offhand-first-person" if hand == "off" else "-first-person")
                    row = self.capture("host", name)
                    row.update(cmd=station["cmd"], held=True, hand=hand, camera=camera)
                    evidence["screenshots"].append(row)
            evidence["nativeCaptureComplete"] = len(evidence["screenshots"]) == (20 if held_only else 43)
            evidence["scenarioComplete"] = evidence["nativeCaptureComplete"]
        except Exception as error:
            evidence["failure"] = str(error)
            raise
        finally:
            output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
        return evidence

    def browser_dependencies(self):
        expected = {
            "[内置网页浏览器]WebDisplays-REMASTER-2.1.0.jar": "2c61926eb8049a098679c5d3710714d49e831152d8b017b822e99eb00849c85e",
            "[嵌入式框架]mcef-neoforge-2.1.6-1.21.1.jar": "c8d7c1edd4f3b1085d65240d50b03a768f9efb763b44caa358a17b29979dbe02",
        }
        for role in ("server", "host", "guest"):
            for filename, digest in expected.items():
                path = self.root / role / "mods" / filename
                if not path.is_file() or sha(path) != digest:
                    raise ValueError("Actual browser dependency differs: " + str(path))
                if not any(row.get("file") == filename and row.get("sha256") == digest for row in self.marker["inputArtifacts"]):
                    raise ValueError("Unified runner did not pin the browser dependency")
        return expected

    def hardware_scenario(self):
        output = self.channel / "hardware-scenario-result.json"
        if output.exists():
            raise ValueError("Preserve existing hardware results and use a fresh run")
        evidence = {"runId": self.marker["runId"], "productSha256": self.build["product"]["sha256"],
                    "physicalOSInput": False, "visualAcceptance": False, "scenarioComplete": False, "screenshots": []}
        host = lambda action, body=None: self.command("host", action, body)
        try:
            prepared = self.prepare()
            name = prepared["clients"]["host"]["playerName"]
            move = lambda x, z: self.command("server", "position", {"player": name, "x": x, "z": z})
            self.command("server", "hardware_prepare")
            self.command("server", "hardware_mode", {"mode": "survival"})
            self.wait_for("host", lambda v: v["gameMode"] == "survival", "real survival placement/drop mode")
            move(5.5, 8.5);host("slot", {"slot": 4});host("place_model")
            placed = self.wait_for("server", lambda v: v["hardware"]["block"] == "muxi_console:desk_console" and v["hardware"]["model"] == 2, "real CMD item placement preserves SNES model")
            self.wait_for("host", lambda v: not any(i["slot"] == 4 for i in v["inventory"]), "survival placement consumes its item")
            evidence["placedModel"] = placed["hardware"]
            for platform in ("nes", "gb"):
                move(8.5, 11.5);host("slot", {"slot": 8});host("use_block", {"x": 8})
                self.wait_for("host", lambda v: v["screen"].endswith("WriterScreen"), "real writer for " + platform)
                host("writer_drop", {"rom": platform});host("writer_confirm");host("button", {"text": "上传并刻录"})
                self.wait_for("host", lambda v: any(c["platform"] == platform and c["slot"] != 2 for c in v.get("cards", [])), "real " + platform + " cartridge burn")
                host("close_screen")
            cards = host("observe")["cards"]
            nes = next(c for c in cards if c["platform"] == "nes")
            gb = next(c for c in cards if c["platform"] == "gb")
            if gb["hash"] != self.build["gbDiagnosticRom"]["sha256"]:
                raise ValueError("GB fixture differs from the build-pinned original")
            move(5.5, 8.5);host("slot", {"slot": nes["slot"]});host("use_block", {"x": 5, "z": 6})
            wrong = self.wait_for("host", lambda v: v["lastCode"] == "hardware", "wrong machine rejects genuine NES cartridge")
            if not any(c["instance"] == nes["instance"] for c in wrong["cards"]) or "card" in self.command("server", "observe")["hardware"]["device"]:
                raise ValueError("Wrong-machine rejection consumed or installed the cartridge")
            evidence["wrongMachineRejectedWithoutConsumption"] = True
            host("handheld_load")
            loaded = self.wait_for("host", lambda v: any(c["instance"] == gb["instance"] and c["slot"] == 2 for c in v["cards"]), "GBA shell accepts the genuine GB cartridge")
            shell = next(i for i in loaded["inventory"] if i["slot"] == 2)
            if shell["model"] != 5 or shell["qaPreserve"] != "original-gba-shell":
                raise ValueError("Loading overwrote the GBA shell or its other custom data")
            evidence["loadedGbaShell"] = shell
            host("handheld_use")
            self.wait_for("host", lambda v: v["ready"] and v["workerRunning"] and v["room"] == "handheld/" + gb["instance"] and v["frames"] >= 420, "actual GBA-shaped handheld runs its GB cartridge beyond its native boot animation")
            evidence["screenshots"].append(self.capture("host", "26-gba-shell-gb-game"))
            native_before = host("observe")
            host("input_source", {"buttons": 256})
            changed = self.wait_for("host", lambda v: v["room"] == "handheld/" + gb["instance"] and v["crc"] != native_before["crc"] and v["frames"] > native_before["frames"], "real GB input changes the handheld picture")
            evidence["liveHandheldInput"] = {"beforeCrc": native_before["crc"], "afterCrc": changed["crc"]}
            host("review_view", {"held": True});self.settle("host")
            evidence["screenshots"].append(self.capture("host", "27-gba-shell-gb-live-main"))
            host("swap_hands")
            self.wait_for("host", lambda v: any(c["slot"] == 40 and c["instance"] == gb["instance"] for c in v["cards"]) and v["offhandModel"] == 5 and v["workerRunning"], "vanilla hand swap preserves active GB cartridge and GBA shell")
            self.settle("host")
            evidence["screenshots"].append(self.capture("host", "28-gba-shell-gb-live-offhand"))
            host("swap_hands")
            self.wait_for("host", lambda v: any(c["slot"] == 2 and c["instance"] == gb["instance"] for c in v["cards"]) and v["heldModel"] == 5, "normal hand swap returns the same loaded handheld")
            host("input_source", {"buttons": 0})
            before = host("observe");host("leave")
            closed = self.wait_for("host", lambda v: not v["workers"] and not v["workerRunning"], "handheld native normal saved exit")
            if closed["normalWorkerExits"] <= before["normalWorkerExits"] or closed["forcedWorkerExits"] or closed["failedWorkerExits"]:
                raise ValueError("GBA shell / GB worker did not exit normally")
            host("handheld_load")
            ejected = self.wait_for("host", lambda v: any(c["instance"] == gb["instance"] and c["slot"] != 2 for c in v["cards"]) and all(c["slot"] != 2 for c in v["cards"]), "actual sneak-use ejects the same GB cartridge")
            shell = next(i for i in ejected["inventory"] if i["slot"] == 2)
            if shell["model"] != 5 or shell["qaPreserve"] != "original-gba-shell":
                raise ValueError("Ejecting overwrote the GBA shell or unrelated custom data")
            evidence["ejectedGbaShell"] = shell
            host("close_screen");move(5.5, 8.5);host("slot", {"slot": 6});host("destroy_model")
            self.wait_for("server", lambda v: v["hardware"]["block"] == "minecraft:air", "real survival break completes")
            move(5.5, 6.5)
            dropped = self.wait_for("host", lambda v: any(i["item"] == "muxi_console:desk_console" and i["model"] == 2 for i in v["inventory"]), "real world drop and vanilla pickup preserve placed CMD")
            evidence["droppedModel"] = next(i for i in dropped["inventory"] if i["item"] == "muxi_console:desk_console" and i["model"] == 2)
            evidence["scenarioComplete"] = True
        except Exception as error:
            evidence["failure"] = str(error)
            raise
        finally:
            try:
                if host("observe")["workerRunning"]:
                    host("leave");self.wait_for("host", lambda v: not v["workers"], "normal hardware failure cleanup")
                self.command("server", "hardware_mode", {"mode": "creative"})
            except Exception as error:
                evidence["cleanupFailure"] = str(error);evidence["scenarioComplete"] = False
            output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
        return evidence

    def browser_scenario(self, resume=False, source_resume=False):
        output = self.channel / ("browser-source-resumed-result.json" if source_resume else "browser-resumed-result.json" if resume else "browser-scenario-result.json")
        if output.exists():
            raise ValueError("Browser scenario results already exist; preserve failures and use a fresh run")
        evidence = {"runId": self.marker["runId"], "productSha256": self.build["product"]["sha256"],
                    "physicalOSInput": False, "visualAcceptance": False, "syntheticServerPlayers": False,
                    "screenshots": [], "gpuSamples": [], "scenarioComplete": False}
        host = lambda action, body=None: self.command("host", action, body)
        guest = lambda action, body=None: self.command("guest", action, body)
        try:
            evidence["dependencies"] = self.browser_dependencies()
            if source_resume:
                original_path = self.channel / "browser-resumed-result.json"
                original = read_json(original_path)
                prepared = {"clients": {role: self.command(role, "observe") for role in ("host", "guest")}}
                source = prepared["clients"]["host"]
                server = self.command("server", "observe")
                fixture = server["browser"]
                source_card = original.get("genuineCartridge", {})
                device = next((row for row in server["devices"] if row.get("position") == (10 << 38 | 8 << 12 | 90)), {})
                if original.get("runId") != self.marker["runId"] or original.get("scenarioComplete") or "Permission denied" not in original.get("failure", ""):
                    raise ValueError("Source resume only the recorded NTFS reply-read failure from this same run")
                if source["room"] or source["workers"] or server["rooms"] or server["pendingUploads"] or len(source.get("cards", [])) != 2 or source["forcedWorkerExits"] or source["failedWorkerExits"]:
                    raise ValueError("Source resume requires normal previous cleanup with no upload, extra cartridge or active session")
                if source_card.get("hash") != self.build["diagnosticRom"]["sha256"] or device.get("card", {}).get("instance") != source_card.get("instance") or device.get("card", {}).get("hash") != source_card.get("hash") or not device.get("powered") or device.get("ports") != 1:
                    raise ValueError("The physically installed powered source differs from the real cartridge minted before the failure")
                if not fixture.get("prepared") or fixture.get("bindings") or fixture.get("url") != original["fixture"]["url"]:
                    raise ValueError("Source resume browser fixture changed")
                self.assert_observer(prepared["clients"]["guest"])
                evidence.update(originalScenarioFailed=True, sourceResumeWithoutAnotherBurn=True, originalFailure={"path": str(original_path), "sha256": sha(original_path), "failure": original["failure"]})
                evidence["genuineCartridge"] = source_card
            elif resume:
                original_path = self.channel / "browser-scenario-result.json"
                original = read_json(original_path)
                prepared = {"clients": {role: self.command(role, "observe") for role in ("host", "guest")}}
                source = prepared["clients"]["host"]
                server = self.command("server", "observe")
                fixture = server["browser"]
                expected_hashes = {self.build["diagnosticRom"]["sha256"], self.build["gbDiagnosticRom"]["sha256"]}
                if original.get("runId") != self.marker["runId"] or original.get("scenarioComplete") or not original.get("failure", "").startswith("QA command timed out: host/"):
                    raise ValueError("Resume only the recorded initial browser command timeout in this same run")
                if not source["screen"].endswith("WriterScreen") or source.get("fileSelected") or len(source.get("cards", [])) != 2 or {card["hash"] for card in source["cards"]} != expected_hashes or source["room"] or source["workers"] or server["pendingUploads"] or server["rooms"]:
                    raise ValueError("Resume requires the original untouched WriterScreen, exactly the two earlier cartridges and no third burn/upload/session")
                expected_bytes = sum(Path(self.build[key]["path"]).stat().st_size for key in ("diagnosticRom", "gbDiagnosticRom"))
                if server["contentBytes"] != expected_bytes or not fixture.get("prepared") or fixture.get("bindings") != 0 or fixture.get("url") != original["fixture"]["url"]:
                    raise ValueError("Resume server fixture/content differs from the original pre-burn failure")
                for role in ("host", "guest"):
                    surface = self.command(role, "browser_native", {"position": fixture["position"], "side": fixture["side"]})["browserNative"]
                    if not surface.get("surfaceExists"):
                        raise ValueError("Real WebDisplays surface must first recover through its normal chunk lifecycle")
                self.assert_observer(prepared["clients"]["guest"])
                evidence.update(originalScenarioFailed=True, originalFailure={"path": str(original_path), "sha256": sha(original_path), "failure": original["failure"]}, normalChunkLifecycleRecovery=True)
            else:
                prepared = self.prepare()
                fixture = self.command("server", "browser_prepare_blocks")
                target = {"position": fixture["position"], "side": fixture["side"]}
                for role in ("host", "guest"):
                    self.command(role, "browser_native", target)
                    self.wait_for(role, lambda v: v["browser"].get("block") == "webdisplays:screen" and v["browser"].get("fixtureBlocks") == 6, "all six actual client display blocks arrive before WebDisplays addScreen")
                fixture = self.command("server", "browser_create_face")
                for role in ("host", "guest"):
                    self.wait_for(role, lambda v: v["browser"].get("surfaceExists") and v["browser"].get("browserPresent"), "normal WebDisplays face/browser creation")
            evidence["fixture"] = fixture
            target = {"position": fixture["position"], "side": fixture["side"]}
            host_name = prepared["clients"]["host"]["playerName"]
            guest_name = prepared["clients"]["guest"]["playerName"]
            if not source_resume:
                prior_cards = {card["instance"] for card in prepared["clients"]["host"].get("cards", [])}
                self.command("server", "position", {"player": host_name, "x": 8.5, "z": 11.5})
                host("slot", {"slot": 8}); host("use_block", {"x": 8})
                self.wait_for("host", lambda v: v["screen"].endswith("WriterScreen"), "real writer for browser diagnostic")
                host("writer_drop"); host("writer_confirm"); host("button", {"text": "上传并刻录"})
                burned = self.wait_for("host", lambda v: len(v.get("cards", [])) == len(prior_cards) + 1, "browser source genuine cartridge burn")
                new_cards = [card for card in burned["cards"] if card["instance"] not in prior_cards]
                if len(new_cards) != 1 or new_cards[0]["hash"] != self.build["diagnosticRom"]["sha256"]:
                    raise ValueError("Browser flow did not mint exactly one additional real diagnostic cartridge")
                source_card = new_cards[0]
                evidence["genuineCartridge"] = source_card
                host("close_screen")
            self.command("server", "position", {"player": host_name, "x": 10.5, "z": 11.5})
            if not source_resume:
                host("slot", {"slot": source_card["slot"]}); host("use_block", {"x": 10})
                host("slot", {"slot": 1}); host("use_block", {"x": 10})
            host("slot", {"slot": 8}); host("use_block", {"x": 10})
            self.wait_for("host", lambda v: v["screen"].endswith("DeviceScreen"), "real source device panel")
            if not source_resume:
                host("button", {"text": "开机"})
            host("button", {"text": "连接屏幕"}); host("button", {"text": "P1"})
            active = self.wait_for("host", lambda v: v["ready"] and v["workerRunning"] and v["frames"] >= 30, "real source libretro running")
            self.command("server", "position", {"player": host_name, "x": 13, "z": 11.5})
            self.command("server", "position", {"player": guest_name, "x": 13.5, "z": 13.5})
            guest("probe", {"room": active["room"], "epoch": active["epoch"]})
            denied = self.wait_for("guest", lambda v: v["probe"] == "denied", "pure observer ROM download rejected")
            self.assert_observer(denied)
            guest("browser_select", target); self.settle("guest")
            if self.command("server", "observe")["browser"]["bindings"] != 0:
                raise ValueError("A pure observer bound a display without a control seat")
            evidence["observerCannotBindOrDownload"] = True
            self.command("server", "browser_owner", {"owner": "guest"})
            host("browser_select", target); self.settle("host")
            if self.command("server", "observe")["browser"]["bindings"] != 0:
                raise ValueError("Controller without URL rights bound another player's display")
            evidence["controllerWithoutUrlRightsRejected"] = True
            self.command("server", "browser_owner", {"owner": "host"})
            host("browser_select", target)
            self.wait_for("server", lambda v: v["browser"]["bindings"] == 1, "real block-use binds display")
            for role in ("host", "guest"):
                self.wait_for(role, lambda v: v["browser"]["receivedFrames"] >= 4 and v["browser"]["readyBrowsers"] == 1, "actual browser page and ongoing pixels: " + role)
                evidence["gpuSamples"].append(self.gpu_sample(role, target))
            baseline = host("observe")["crc"]
            host("input_source", {"buttons": 256})
            self.wait_for("host", lambda v: v["crc"] and v["crc"] != baseline, "real controller changes source picture")
            host("input_source", {"buttons": 0})
            for role in ("host", "guest"):
                first = next(v for v in evidence["gpuSamples"] if v["role"] == role)
                changed = self.gpu_sample(role, target, first["textureCrc"])
                evidence["gpuSamples"].append(changed)
            self.assert_observer(guest("observe"))
            for role, filename in (("host", "24-desktop-screen-and-browser"), ("guest", "25-observer-screen-and-browser")):
                other_name, other_x = (guest_name, 16) if role == "host" else (host_name, 9)
                self.command("server", "position", {"player": other_name, "x": other_x, "z": 14.5})
                self.command("server", "browser_camera", {"role": role})
                self.command(role, "review_view", {"held": False}); self.settle(role)
                evidence["screenshots"].append(self.capture(role, ("source-resumed-" if source_resume else "resumed-" if resume else "") + filename))
            self.command("server", "position", {"player": host_name, "x": 13, "z": 11.5})
            self.command("server", "position", {"player": guest_name, "x": 13.5, "z": 13.5})
            host("browser_select", target)
            self.wait_for("server", lambda v: v["browser"]["bindings"] == 0, "normal second click unbinds")
            for role in ("host", "guest"):
                restored = self.wait_for(role, lambda v: v["browser"]["bindings"] == 0, "client binding removed")
                self.browser_restored(role, target, fixture["url"])
            evidence["unbindRestoresOriginalUrl"] = True
            host("browser_select", target)
            self.wait_for("server", lambda v: v["browser"]["bindings"] == 1, "second real binding")
            self.command("server", "browser_owner", {"owner": "guest"})
            self.wait_for("server", lambda v: v["browser"]["bindings"] == 0, "URL rights revocation invalidates binding")
            for role in ("host", "guest"):
                self.browser_restored(role, target, fixture["url"])
            evidence["rightsRevocationUnbinds"] = True
            self.command("server", "browser_owner", {"owner": "host"})
            host("browser_select", target)
            self.wait_for("server", lambda v: v["browser"]["bindings"] == 1, "third real binding")
            self.command("server", "browser_url", {"url": "about:blank#muxi-console-navigated"})
            self.wait_for("server", lambda v: v["browser"]["bindings"] == 0, "normal navigation invalidates old page")
            self.browser_restored("guest", target, "about:blank#muxi-console-navigated")
            evidence["newerNavigationPreserved"] = True
            self.command("server", "browser_url", {"url": fixture["url"]})
            host("browser_select", target)
            self.wait_for("server", lambda v: v["browser"]["bindings"] == 1, "final source-close binding")
            before = host("observe")
            host("leave")
            closed = self.wait_for("host", lambda v: not v["workers"] and not v["workerRunning"], "source worker normal close")
            self.wait_for("server", lambda v: v["browser"]["bindings"] == 0, "source leave cleans browser binding")
            for role in ("host", "guest"):
                self.browser_restored(role, target, fixture["url"])
            if closed["normalWorkerExits"] <= before["normalWorkerExits"] or closed["forcedWorkerExits"] or closed["failedWorkerExits"]:
                raise ValueError("Source worker did not record a normal native exit")
            self.assert_observer(guest("observe"))
            evidence["sourceLeaveUnbinds"] = True
            evidence["observer"] = guest("observe")
            evidence["normalSourceWorkerExit"] = True
            evidence["scenarioComplete"] = True
        except Exception as error:
            evidence["failure"] = str(error)
            raise
        finally:
            try:
                if host("observe")["workerRunning"]:
                    host("input_source", {"buttons": -1});host("leave")
                    self.wait_for("host", lambda v: not v["workers"] and not v["workerRunning"], "failure cleanup normal source leave")
            except Exception as cleanup:
                evidence["cleanupFailure"] = str(cleanup)
                evidence["scenarioComplete"] = False
            output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
        return evidence

    @staticmethod
    def assert_observer(value):
        if value["downloadsGranted"] != 0 or value["workers"] or value["workerRunning"] or value["room"] or value.get("cards") or value.get("cachedRomFiles") != 0:
            raise ValueError("Pure observer acquired a ROM, card, session or native worker")

    def gpu_sample(self, role, target, previous=None):
        deadline = time.monotonic() + 45
        last = None
        while time.monotonic() < deadline:
            last = self.command(role, "browser_native", target)["browserNative"]
            if last.get("gpuRead") and last.get("linkedNativeBrowser") and last.get("readyBrowsers") == 1 and last.get("javascriptSubmissions", 0) >= 4 and last.get("browserUrl", "").startswith("data:text/html;base64,") and not last.get("uniform") and last.get("chromaticPixels", 0) > 100 and (previous is None or last["textureCrc"] != previous):
                return {"role": role, **last}
            time.sleep(0.25)
        raise TimeoutError("Actual CEF GPU texture failed to show changed nonempty canvas: " + json.dumps(last))

    def browser_restored(self, role, target, url):
        deadline = time.monotonic() + 30
        last = None
        while time.monotonic() < deadline:
            last = self.command(role, "browser_native", target)["browserNative"]
            if last.get("bindings") == 0 and last.get("url") == url and last.get("browserUrl") == url:
                return last
            time.sleep(0.2)
        raise TimeoutError("Actual third-party browser did not restore the expected URL: " + json.dumps(last))

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
            host("button", {"text": "开机"}); host("button", {"text": "连接屏幕"})
            host("button", {"text": "按键与手柄"})
            self.wait_for("host", lambda value: value["screen"].endswith("ControlsScreen"), "real controls screen")
            evidence["screenshots"].append(self.capture("host", "console-controls"))
            host("button", {"text": "返回"}); host("button", {"text": "P1"})
            self.wait_for("host", lambda value: value["ready"] and value["workerRunning"], "P1 native worker ready")
            guest("slot", {"slot": 8}); guest("use_block", {"x": 10})
            self.wait_for("guest", lambda value: value["screen"].endswith("DeviceScreen"), "P2 real device screen")
            guest("button", {"text": "P2"})
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
    mode.add_argument("--model-review", action="store_true")
    mode.add_argument("--held-review", action="store_true")
    mode.add_argument("--shared-scenario", choices=("n64", "arcade"))
    mode.add_argument("--resume-shared-n64", action="store_true")
    mode.add_argument("--shared-n64-handover", action="store_true")
    mode.add_argument("--leave-during-join", action="store_true")
    mode.add_argument("--nes-regression", action="store_true")
    mode.add_argument("--quick-review", action="store_true")
    mode.add_argument("--held-live-review", action="store_true")
    mode.add_argument("--browser-scenario", action="store_true")
    mode.add_argument("--resume-browser", action="store_true")
    mode.add_argument("--resume-browser-source", action="store_true")
    mode.add_argument("--hardware-scenario", action="store_true")
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
    elif options.model_review:
        result = driver.model_review()
    elif options.held_review:
        result = driver.model_review(True)
    elif options.shared_scenario:
        from shared_drive import run
        result = run(driver, options.shared_scenario)
    elif options.resume_shared_n64:
        from shared_drive import run
        result=run(driver,"n64",True)
    elif options.shared_n64_handover:
        from shared_drive import handover
        result=handover(driver)
    elif options.leave_during_join or options.nes_regression or options.quick_review or options.held_live_review:
        from quick_review import leave_during_join,nes_regression,quick_review,held_live_review
        result=(leave_during_join if options.leave_during_join else nes_regression if options.nes_regression else held_live_review if options.held_live_review else quick_review)(driver)
    elif options.browser_scenario:
        result = driver.browser_scenario()
    elif options.resume_browser:
        result = driver.browser_scenario(True)
    elif options.resume_browser_source:
        result = driver.browser_scenario(source_resume=True)
    elif options.hardware_scenario:
        result = driver.hardware_scenario()
    elif options.continue_after_drop:
        result = driver.scenario(True, options.drop_x)
    else:
        result = driver.command(options.role, options.action, json.loads(options.body))
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
