"""Narrow complete-pack smoke through the unified runner's existing native agent; no extra QA mod."""
from __future__ import annotations
import argparse
import hashlib
import json
import sys
import time
import re
from pathlib import Path
from drive import WORKSPACE, read_json, sha, environment

sys.path.insert(0, str(WORKSPACE / "better-mc-remake/scripts"))
import local_mc_debug as native


def run(run_path, profile_path, plan_path, profile_bytes, result_name="console-fullpack-smoke.json", previous_sha=None):
    root = run_path.absolute()
    if root != root.resolve(strict=True): raise ValueError("Canonical unified run path required")
    environment.require_run_path(root)
    plan = read_json(plan_path); profile_path = profile_path.resolve(strict=True)
    marker = read_json(root / "local-mc-owner.json")
    if type(profile_bytes) is not int or not 0 < profile_bytes <= 16 * 1024**2 or profile_path.stat().st_size != profile_bytes:
        raise ValueError("Full profile requires its explicit exact size, bounded to 16MiB")
    if marker["releaseProfile"]["path"] != str(profile_path): raise ValueError("Profile differs from the running owner")
    with profile_path.open("rb") as stream: raw = stream.read(profile_bytes + 1)
    if len(raw) != profile_bytes or profile_path.stat().st_size != profile_bytes or hashlib.sha256(raw).hexdigest() != marker["releaseProfile"]["sha256"]:
        raise ValueError("Full profile size/hash differs from the running owner")
    profile = json.loads(raw.decode("utf-8-sig"))
    if profile.get("schema") != "muxigame-release-profile/v1" or any(not isinstance(profile.get(side + "Files"), list) or not 0 < len(profile[side + "Files"]) <= 50000 for side in ("client", "server")):
        raise ValueError("Unexpected full release-profile schema or file count")
    if result_name not in ("console-fullpack-smoke.json", "console-fullpack-smoke-02.json") or bool(previous_sha) != (result_name == "console-fullpack-smoke-02.json"):
        raise ValueError("Only the named same-run pre-mutation continuation is supported")
    path = root / result_name
    if path.exists(): raise ValueError("Preserve the original smoke; never replay a partial scope")
    product = plan["candidate"]
    for side in ("client", "server"):
        rows = [r for r in profile[side + "Files"] if r["path"] == product["path"]]
        if len(rows) != 1 or rows[0]["sha256"] != product["sha256"]: raise ValueError("Exact Console candidate missing from full profile")
        if any(any(i.endswith("_qa") or "_qa_" in i for i in r.get("modIds", [])) for r in profile[side + "Files"]): raise ValueError("No private Console QA mod belongs in this full-pack profile")
    out = dict(runId=marker["runId"], productSha256=product["sha256"], profile=str(profile_path), profileBytes=profile_bytes, profileSha256=sha(profile_path),
               plan=str(plan_path.resolve()), planSha256=sha(plan_path), scenarioComplete=False, physicalOSInput=False,
               visualAcceptance=False, scope="Full-pack startup, two real clients, empty product item/menu/writer network roundtrips and canonical native drops; no ROM/core/save matrix", checks=[], receipts=[], probes=[])
    if previous_sha:
        original = root / "console-fullpack-smoke.json"; old = read_json(original)
        if sha(original) != previous_sha or old.get("scenarioComplete") or old.get("failure") != "host has genuinely empty ordinary fixture slots" or old["runId"] != out["runId"] or old["productSha256"] != out["productSha256"] or old["profileSha256"] != out["profileSha256"] or old["planSha256"] != out["planSha256"] or len(old["checks"]) != 7 or not all(c["passed"] for c in old["checks"][:6]) or old["checks"][-1]["passed"] or len(old["receipts"]) != 2:
            raise ValueError("Exact original pre-mutation empty-slot failure required")
        for role, row in zip(("server", "host"), old["receipts"]):
            command = read_json(root / "coordinator" / ("command-" + role + ".json"))
            if row["role"] != role or row["id"] != 1 or not row["ok"] or command != {"type": "observe", "id": 1, "runId": marker["runId"]} or row.get("assistedServerCommand") or row.get("assistedInventoryFixture"):
                raise ValueError("The original channel must still prove observe-only commands before continuation")
        if (root / "coordinator/command-guest.json").exists(): raise ValueError("Unexpected intervening guest command")
        out["previousResult"] = {"path": str(original), "sha256": previous_sha}
        out["continuationScope"] = "Original false has only two observe receipts and no mutation. Recheck these read-only gates, then use two empty slots; case replaces only our own injected empty handheld."
    def verify():
        environment.verify_slot_lease(root)
        actual = read_json(root / "local-mc-owner.json")
        if actual["runId"] != marker["runId"] or actual["instanceRoot"] != str(root) or set(actual["roles"]) != {"server", "host", "guest"} or not actual.get("offlineLoopback") or actual.get("productionMutation"): raise ValueError("Foreign run")
        if actual["releaseProfile"]["path"] != str(profile_path) or actual["releaseProfile"]["sha256"] != out["profileSha256"] or sha(profile_path) != out["profileSha256"]: raise ValueError("Full profile changed")
        for role in ("server", "host", "guest"):
            p = actual["processes"][role]; created = environment.process_created(p["pid"])
            if created is None or abs(created - p["processCreateTime"]) > .1 or Path(p["root"]).resolve() != root / role: raise ValueError("Role process generation differs")
        return actual
    def check(value, label, evidence=None):
        out["checks"].append(dict(passed=bool(value), label=label, evidence=evidence))
        if not value: raise AssertionError(label)
    def call(role, value):
        verify(); row = native.command(root, role, value, timeout=60); row["request"] = value; out["receipts"].append(row); return row
    def see(role): return call(role, {"type": "observe"})["status"]
    def screen(role, current, expected):
        deadline = time.monotonic() + 60
        while current.get("screen") != expected:
            if not current.get("connected") or time.monotonic() >= deadline: raise TimeoutError(role + " expected actual " + expected)
            current = see(role)
        return current
    def server_command(text): return call("server", {"type": "execute", "command": text})
    def probe(dimension, condition, label):
        token = "MUXI_CONSOLE_QA_" + marker["runId"] + "_" + str(len(out["probes"]))
        row = dict(label=label, dimension=dimension, condition=condition, token=token, attempts=0)
        out["probes"].append(row)
        for attempt in range(15):
            server_command(f"execute in {dimension} {condition} run say {token}")
            row["attempts"] += 1
            for log in (root / "server/logs/latest.log", root / "server/boot.log"):
                if not log.is_file(): continue
                with log.open("rb") as stream:
                    stream.seek(max(0, log.stat().st_size - 4 * 1024**2)); text = stream.read(4 * 1024**2).decode("utf-8", "replace")
                line = next((line for line in text.splitlines() if "[Server] " + token in line), None)
                if line:
                    row.update(log=str(log), matchedLine=line)
                    check(True, label + " confirmed by actual conditional server feedback", row.copy())
                    return
            time.sleep(1)
        raise TimeoutError(label + ": no successful conditional server feedback; world mutations are not replayed")
    fixtures = {}; selected = {}
    try:
        verify()
        for role in ("server", "host", "guest"):
            actual = root / role / "mods" / Path(product["path"]).name
            check(actual.is_file() and sha(actual) == product["sha256"], "actual " + role + " candidate bytes match the overlay")
        world = root / "server/qa-world"
        template = plan["worldTemplate"]
        check(all((world / r["path"]).is_file() and (world / r["path"]).stat().st_size == r["size"] and sha(world / r["path"]) == r["sha256"] for r in template["files"]), "actual fresh server world contains all twelve verified official map-data inputs")
        server = see("server"); check(server["players"] == 2 and all(p["network"] for p in server["playerStates"]), "two real network players are connected", server)
        names = marker["clientNames"]
        for role in ("host", "guest"):
            v = see(role); check(v["connected"] and v["playersSeen"] == 2, role + " observes the real two-player connection", v)
            inv = server["byName"][names[role]]["inventoryBySlot"]
            empty = [s for s in range(9) if str(s) not in inv]
            check(len(empty) >= 2, role + " has two genuinely empty ordinary fixture slots")
            selected[role] = v["selected"]; fixtures[role] = dict(handheld=empty[0], case=empty[0], diamond=empty[1])
        out["fixtureSlots"] = fixtures
        initial = out["initialPlayerStates"] = {role: server["byName"][names[role]] for role in ("host", "guest")}
        dimensions = {p["dimension"] for p in initial.values()}
        if any(not re.fullmatch(r"[a-z0-9_.-]+:[a-z0-9_/.-]+", dim) for dim in dimensions): raise ValueError("Invalid actual player dimension")
        for dim in sorted(dimensions):
            server_command(f"execute in {dim} run forceload add 1024 1024 1055 1055")
            probe(dim, "if loaded 1024 90 1024 if loaded 1038 94 1035", "QA chunks loaded in " + dim)
            server_command(f"execute in {dim} run fill 1024 90 1024 1038 94 1035 minecraft:air")
            server_command(f"execute in {dim} run fill 1024 89 1024 1038 89 1035 minecraft:smooth_stone")
            server_command(f"execute in {dim} run setblock 1029 90 1028 muxi_console:cartridge_writer")
            probe(dim, "if block 1029 90 1028 muxi_console:cartridge_writer if block 1029 89 1031 minecraft:smooth_stone if block 1031 89 1031 minecraft:smooth_stone if block 1029 90 1031 minecraft:air if block 1031 90 1031 minecraft:air", "real writer, safe floor and headroom in " + dim)
        for role, x in (("host", 1029.5), ("guest", 1031.5)):
            original = initial[role]; dim = original["dimension"]
            if original["mode"] not in ("survival", "creative", "adventure", "spectator"): raise ValueError("Unexpected actual player mode")
            server_command(f"gamemode creative {names[role]}")
            server_command(f"execute in {dim} run tp {names[role]} {x} 90 1031.5")
            probe(dim, f"positioned {x} 90 1031.5 if entity @a[name={names[role]},distance=..0.5]", role + " is at the loaded safe position in its original dimension")
            server_command(f"gamemode {original['mode']} {names[role]}")
            present = see("server")["byName"][names[role]]
            check(present["dimension"] == dim and present["mode"] == original["mode"] and present["health"] >= original["health"] > 0, role + " restores its original mode and remains unharmed before interaction", present)
            for key, item in (("handheld", "muxi_console:handheld"), ("case", "muxi_console:cartridge_case")):
                slot = fixtures[role][key]
                if key == "case":
                    own = see("server")["byName"][names[role]]["inventoryBySlot"][str(slot)]
                    check(own["item"] == "muxi_console:handheld" and own["count"] == 1, role + " case replaces only our injected empty handheld in the shared auxiliary slot", own)
                call("server", dict(type="inventory-set", player=names[role], slot=slot, item=item, count=1))
                call(role, dict(type="select", slot=slot))
                v = call(role, dict(type="use", duration=1))["status"]
                expected = "HandheldModelScreen" if key == "handheld" else "ContainerScreen"
                v = screen(role, v, expected)
                check(v["screen"] == expected and v["connected"], role + " normal " + key + " use opens its actual client screen", v)
            # An empty slot avoids passing a cartridge or held console into writer interaction.
            call(role, dict(type="select", slot=fixtures[role]["diamond"]))
            probe(dim, "if block 1029 90 1028 muxi_console:cartridge_writer", role + " writer still exists before native use")
            v = call(role, dict(type="block-interact", x=1029, y=90, z=1028))["status"]
            v = screen(role, v, "WriterScreen")
            check(v["screen"] == "WriterScreen" and v["connected"], role + " actual writer registry/network roundtrip", v)
        role = "host"; slot = fixtures[role]["diamond"]
        call("server", dict(type="inventory-set", player=names[role], slot=slot, item="minecraft:diamond", count=3))
        call(role, dict(type="select", slot=slot)); call(role, dict(type="use", duration=1))
        call(role, dict(type="drop", all=False)); v = see("server")
        check(v["byName"][names[role]]["inventoryBySlot"][str(slot)]["count"] == 2, "normal one-item drop removes exactly one assisted diamond")
        call(role, dict(type="drop", all=True)); v = see("server")
        check(str(slot) not in v["byName"][names[role]]["inventoryBySlot"], "normal whole-stack drop empties only the assisted diamond slot", v)
        # The canonical agent's looseDiamonds counter sees only minecraft:overworld.
        # Query actual Item.count through vanilla scoreboard arithmetic in the real dimension.
        objective = "mcqa" + marker["runId"][:10]; dim = initial["host"]["dimension"]
        selector = '@e[type=minecraft:item,x=1024,y=89,z=1024,dx=14,dy=6,dz=11,nbt={Item:{id:"minecraft:diamond"}}]'
        server_command(f"scoreboard objectives add {objective} dummy")
        server_command(f"scoreboard players set total {objective} 0")
        server_command(f"execute in {dim} as {selector} store result score @s {objective} run data get entity @s Item.count")
        server_command(f"execute in {dim} as {selector} run scoreboard players operation total {objective} += @s {objective}")
        probe(dim, f"if score total {objective} matches 3", "actual current-dimension dropped diamond stack counts sum to three")
        server_command(f"scoreboard objectives remove {objective}")
        for role in ("host", "guest"):
            call(role, dict(type="select", slot=selected[role]))
        out["finalServer"] = see("server")
        check(out["finalServer"]["players"] == 2, "both real players remain connected after product UI and native drop smoke")
        for dim in sorted(dimensions): server_command(f"execute in {dim} run forceload remove 1024 1024 1055 1055")
        out["scenarioComplete"] = True
    except Exception as error:
        out["failure"] = str(error)
        raise
    finally:
        path.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    return out


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--run", type=Path, required=True); p.add_argument("--profile", type=Path, required=True); p.add_argument("--plan", type=Path, required=True)
    p.add_argument("--profile-bytes", type=int, required=True, help="Exact size of this hash-bound prepared profile (max16MiB)")
    p.add_argument("--result-name", default="console-fullpack-smoke.json"); p.add_argument("--previous-result-sha256")
    a = p.parse_args(); v = run(a.run, a.profile, a.plan, a.profile_bytes, a.result_name, a.previous_result_sha256)
    print(json.dumps(dict(complete=v["scenarioComplete"], checks=len(v["checks"]), receipts=len(v["receipts"]))))
