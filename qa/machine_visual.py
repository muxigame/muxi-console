"""Two fresh native pictures from the original machines/cards in a normally closed copied world."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
from drive import Driver, read_json, sha
from polish_review import record, check
from cartridge_review import Front, at, items, same_card, cleanup


def run(d, source_run):
    source = source_run.absolute()
    if source != source.resolve(strict=True) or source == d.root or source.parent.name != "runs" or source.parent.parent.parent != d.root.parent.parent.parent:
        raise ValueError("A separate canonical original run is required")
    marker_file = source / "local-mc-owner.json"; marker = read_json(marker_file)
    closure_file = source / "run-result.json"; closure = read_json(closure_file)
    audit = closure.get("nativeChildAudit", {})
    if marker["instanceRoot"] != str(source) or marker["runId"] == d.marker["runId"] or not closure.get("normalJavaExit") or closure.get("exitCodes") != {"server": 0, "host": 0, "guest": 0} or closure.get("normalStopBlocked") or not audit.get("complete") or any(audit.get(k) for k in ("remaining", "unknown", "errors", "actions")):
        raise ValueError("The source world must already be normally closed")
    if "loopback/offline copied fixture world" not in d.marker.get("releaseProfile", {}).get("fixtureOverrides", []):
        raise ValueError("The unified runner must declare its normal copied-world path")
    original_file = source / "console-qa/machine-result.json"; original = read_json(original_file)
    continued_file = source / "console-qa/machine-continuation-result.json"; previous = read_json(continued_file)
    case_file = source / "console-qa/case-result.json"; case = read_json(case_file)
    if original.get("scenarioComplete") or original.get("failure") != "java.lang.IllegalStateException: java.lang.IllegalStateException: Actual vanilla inventory screen required" or len(original["checks"]) != 19 or not all(c["passed"] for c in original["checks"]) or len(original["screenshots"]) != 2 or not previous.get("scenarioComplete") or len(previous["checks"]) != 57 or not all(c["passed"] for c in previous["checks"]) or len(previous["screenshots"]) != 5 or previous.get("machine") != {"path": str(original_file), "sha256": sha(original_file)} or not case.get("scenarioComplete") or len(case["checks"]) != 35 or not all(c["passed"] for c in case["checks"]) or len(case["screenshots"]) != 4 or previous["cards"] != original["cards"] or previous["cards"] != case["cards"] or any(v["workers"] or v["forcedWorkerExits"] or v["failedWorkerExits"] for v in previous["finalClients"].values()):
        raise ValueError("The preserved original failure plus completed same-card machine continuation is required")
    for v in (original, previous, case):
        if v["runId"] != marker["runId"] or v["productSha256"] != original["productSha256"]:
            raise ValueError("Source evidence must belong to the same original run/product")
    path, out = record(d, "machine-visual")
    out["scope"] = "Only current-candidate native world LCD and body MODEL restoration, using the original closed world/card/machine. Original 01 dark pictures and failed QA inventory scope stay unchanged. Storage/case matrix belongs to its original candidate evidence."
    out["sourceRun"] = {"path": str(source), "runId": marker["runId"], "productSha256": original["productSha256"]}
    out["sources"] = {p.name: {"path": str(p), "sha256": sha(p)} for p in (marker_file, closure_file, case_file, original_file, continued_file)}
    card = out["card"] = previous["cards"]["gba_a"]
    owner = out["saveOwner"] = previous["machineA"]
    host = Front(d, out)
    try:
        host.do("dock_view", {"hideGui": False}); host.settle(10)
        actual = d.command("server", "machine_observe")
        out["copiedWorldBefore"] = actual
        expected = {(s["machine"], s["content"]): s for s in previous["finalWorld"]["saved"]}
        current = {(s["machine"], s["content"]): s for s in actual["saved"]}
        check(out, set(current) == set(expected) and all(current[k]["hash"] == current[k]["diskSha256"] == expected[k]["hash"] and current[k]["generation"] == expected[k]["generation"] for k in expected), "the copied world preserves every original machine/game checkpoint byte for byte", actual["saved"])
        view = host.see()
        machine = next(i for i in items(view) if i.get("machine") == owner["machine"] and i["item"] == "muxi_console:handheld")
        check(out, same_card(machine.get("inserted", {}), card), "the copied player inventory contains the original physical A with its exact full card NBT", machine)
        if view["screen"]: host.close()
        if machine["slot"] != 2:
            host.inv(); host.do("cartridge_swap", {"slot": machine["slot"], "hotbar": 2})
            host.wait(lambda v: at(v, 2).get("machine") == owner["machine"] and same_card(at(v, 2).get("inserted", {}), card), "normal real inventory swap selects the original machine for review")
            host.close()
        host.do("slot", {"slot": 2})
        checkpoint = expected[(owner["machine"], owner["content"])]["hash"]
        live = host.wait(lambda v: v["ready"] and v["workerRunning"] and v["frames"] >= 30 and v["restoredStateHash"] == checkpoint and v["machine"] == owner and v["cartridge"]["progress"].get("card") == card["instance"], "same original machine/card restores the copied world checkpoint")
        check(out, live["room"] == "handheld/" + owner["machine"], "new renderer source is the actual machine-owned handheld room")
        check(out, all(live["picture"][q][0] > 200 and live["picture"][q][1] < 50 and live["picture"][q][2] < 50 for q in ("upper", "lower")), "restored original machine A still contains its actual red diagnostic framebuffer", live["picture"])
        host.do("review_view", {"held": True}); host.settle(12)
        held = host.pic("machine-fixed-original-world-lcd", live)
        held["heldRender"] = host.see()["heldReview"]["render"]
        host.do("pixel_handheld_use", {"slot": 2, "load": False})
        model = host.wait(lambda v: v["polish"].get("presentation", {}).get("mode") == "model" and v["polish"]["presentation"].get("modelRendered") is True and v["polish"]["presentation"].get("renderedModel") == 5, "current renderer actually draws original machine A's full body MODEL")
        check(out, model["machine"] == owner and model["workers"] == live["workers"] and not model["cartridge"]["progress"]["loading"], "MODEL keeps the same restored machine worker and completed picture", model["polish"]["presentation"])
        host.pic("machine-fixed-original-model", live)
        ended = host.leave("current candidate original-machine visual review")
        if host.see()["screen"]: host.close()
        final = out["finalWorld"] = d.command("server", "machine_observe")
        state = next(s for s in final["saved"] if s["machine"] == owner["machine"] and s["content"] == owner["content"])
        check(out, state["hash"] == state["diskSha256"] == ended["savedStateHash"] and state["diskBytes"] == state["size"] > 0, "current candidate safely saves the same original machine namespace to actual copied-world disk bytes", state)
        out["scenarioComplete"] = True
    except Exception as error:
        out["failure"] = str(error)
        raise
    finally:
        cleanup(d, out)
        out["observationTimeouts"] = d.observation_timeouts
        path.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    return out


if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument("--run", type=Path, required=True)
    p.add_argument("--build", type=Path, required=True)
    p.add_argument("--source-run", type=Path, required=True)
    a = p.parse_args(); result = run(Driver(a.run, a.build), a.source_run)
    print(json.dumps(dict(complete=result["scenarioComplete"], checks=len(result["checks"]), originals=len(result["screenshots"]))))
