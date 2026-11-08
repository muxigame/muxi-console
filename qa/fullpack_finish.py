"""Close only the exact full-pack scope using existing historical item evidence; never replay fixtures/drops."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import time
from fullpack_smoke import native, environment, read_json, sha


def run(supplied, original_sha, wide_sha):
    root = supplied.absolute()
    if root != root.resolve(strict=True): raise ValueError("Canonical run required")
    environment.require_run_path(root)
    source_path = root / "console-fullpack-smoke.json"; source = read_json(source_path)
    wide_path = root / "console-fullpack-drop-wide-observation.json"; wide = read_json(wide_path)
    marker = read_json(root / "local-mc-owner.json"); result_path = root / "console-fullpack-finish.json"
    if result_path.exists(): raise ValueError("Preserve the original finish result")
    if sha(source_path) != original_sha or source.get("scenarioComplete") or len(source["checks"]) != 27 or not all(r["passed"] for r in source["checks"]) or source["failure"] != "actual current-dimension dropped diamond stack counts sum to three: no successful conditional server feedback; world mutations are not replayed" or source["runId"] != marker["runId"]:
        raise ValueError("Exact original 27-pass spatial-probe failure required")
    if sha(wide_path) != wide_sha or len(wide["receipts"]) != 2: raise ValueError("Exact existing wide observation required")
    dimension = source["initialPlayerStates"]["host"]["dimension"]
    for row, number, tick, field in zip(wide["receipts"], (60, 61), (10387, 10389), ("Item", "Pos")):
        expected = f'execute in {dimension} as @e[type=minecraft:item,nbt={{Item:{{id:"minecraft:diamond"}}}},limit=10] run data get entity @s {field}'
        if row["role"] != "server" or row["runId"] != marker["runId"] or row["id"] != number or row["status"]["tick"] != tick or not row["ok"] or row["request"] != expected:
            raise ValueError("Historical query/tick/run identity differs")
    log = root / "server/logs/latest.log"; lines = log.read_bytes().splitlines()
    bodies = (b'21:51:15.379', b'21:51:15.379', b'21:51:15.479', b'21:51:15.479')
    values = (b'{id: "minecraft:diamond", count: 1}', b'{id: "minecraft:diamond", count: 2}',
              b'[1029.5785921270503d, 90.0d, 1034.4929815384949d]', b'[1029.3671389856174d, 66.0d, 1037.2231286059937d]')
    witnesses = []
    for stamp, value in zip(bodies, values):
        found = [(n + 1, row) for n, row in enumerate(lines) if stamp in row and b'Diamond has the following entity data: ' + value in row]
        if len(found) != 1: raise ValueError("Exact original historical entity witness missing or duplicated")
        number, row = found[0]; witnesses.append({"line": number, "text": row.decode("utf-8", "replace"), "sha256": hashlib.sha256(row).hexdigest()})
    plan_path = Path(source["plan"]); plan = read_json(plan_path)
    if sha(plan_path) != source["planSha256"] or plan["candidate"]["sha256"] != source["productSha256"]: raise ValueError("Plan/product drift")
    out = dict(runId=marker["runId"], productSha256=source["productSha256"], scenarioComplete=False, physicalOSInput=False,
               original={"path": str(source_path), "sha256": original_sha}, historicalObservation={"path": str(wide_path), "sha256": wide_sha},
               historicalDiamonds={"observedCount": 3, "entityStacks": [1, 2], "serverTicks": [10387, 10389], "log": str(log), "witnesses": witnesses},
               currentDiamondCountClaimed=False, scope="Keep original false/27 passes; bind already observed real 1+2 item stacks before natural despawn, then close UI/restore selection/cleanup and observe current two healthy clients. No inventory, drop, teleport or fill replay.", checks=[], receipts=[])
    def verify():
        environment.verify_slot_lease(root); current = read_json(root / "local-mc-owner.json")
        if current["runId"] != marker["runId"] or current["instanceRoot"] != str(root) or current["releaseProfile"]["sha256"] != source["profileSha256"] or current["releaseProfile"]["path"] != source["profile"] or sha(Path(source["profile"])) != source["profileSha256"]: raise ValueError("Owner/profile changed")
        for role in ("server", "host", "guest"):
            p = current["processes"][role]; created = environment.process_created(p["pid"])
            if created is None or abs(created - p["processCreateTime"]) > .1 or Path(p["root"]).resolve() != root / role: raise ValueError("PID generation differs")
    def check(value, label):
        out["checks"].append({"passed": bool(value), "label": label})
        if not value: raise AssertionError(label)
    def call(role, request):
        verify(); row = native.command(root, role, request, timeout=60); row["request"] = request; out["receipts"].append(row); return row
    def preserved(status):
        if status["players"] != 2: return False
        for role, initial in source["initialPlayerStates"].items():
            actual = status["byName"][initial["name"]]
            if not actual["network"] or actual["health"] != 20 or actual["mode"] != initial["mode"] or actual["dimension"] != initial["dimension"]: return False
            if any(actual["inventoryBySlot"].get(slot) != item for slot, item in initial["inventoryBySlot"].items()): return False
        return True
    try:
        verify(); check(True, "existing same-run historical native queries prove three real dropped diamonds; no current-existence claim")
        for role in ("server", "host", "guest"):
            artifact = root / role / "mods" / Path(plan["candidate"]["path"]).name
            check(artifact.is_file() and sha(artifact) == source["productSha256"], role + " exact product SHA is unchanged")
        before = call("server", {"type": "observe"})["status"]; check(preserved(before), "current two real clients have 20hp, original dimension/mode and every original starter item")
        for role, initial in source["initialPlayerStates"].items():
            row = call(role, {"type": "select", "slot": initial["selected"]})
            check(row["status"]["screen"] == "NONE" and row["status"]["selected"] == initial["selected"], role + " normal callback closes remaining UI and restores selection")
        objective = "mcqa" + marker["runId"][:10]
        call("server", {"type": "execute", "command": f"scoreboard objectives remove {objective}"})
        for dim in sorted({s["dimension"] for s in source["initialPlayerStates"].values()}):
            call("server", {"type": "execute", "command": f"execute in {dim} run forceload remove 1024 1024 1055 1055"})
        final = call("server", {"type": "observe"})["status"]; out["finalServer"] = final
        check(preserved(final) and all(final["byName"][s["name"]]["selected"] == s["selected"] for s in source["initialPlayerStates"].values()), "cleanup leaves two real healthy clients, restored selection and intact starter inventories")
        out["scenarioComplete"] = True
    except Exception as error:
        out["failure"] = str(error); raise
    finally:
        result_path.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    return out


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--run", type=Path, required=True); p.add_argument("--source-sha256", required=True); p.add_argument("--wide-sha256", required=True)
    a = p.parse_args(); v = run(a.run, a.source_sha256, a.wide_sha256)
    print(json.dumps({"complete": v["scenarioComplete"], "checks": len(v["checks"])}))
