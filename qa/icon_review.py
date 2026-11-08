"""Capture two native item-icon pages; never prepares a world, issues cards or starts Minecraft."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import zipfile

from drive import Driver
from polish_review import check, record


def expected_hardware(d):
    # Read the exact product's catalog, not mutable assets or a separately maintained model table.
    with zipfile.ZipFile(d.build["product"]["path"]) as jar:
        info = jar.getinfo("assets/muxi_console/model_catalog.json")
        if info.file_size > 1024 * 1024:
            raise ValueError("Product icon catalog is unexpectedly large")
        catalog = json.loads(jar.read(info))
    models = catalog["entries"] + catalog["additional_styles"]
    if len(models) != 22 or {m["cmd"] for m in models} != set(range(1, 23)):
        raise ValueError("Exact product must declare all 22 distinct authored bodies")
    result = {("muxi_console:desk_console", 0), ("muxi_console:handheld", 0)}
    for model in models:
        kind = "handheld" if model.get("form_factor") in ("handheld", "viewer") else "desk_console"
        result.add(("muxi_console:" + kind, model["cmd"]))
    result.update(("muxi_console:" + name, 0) for name in ("cartridge_writer", "handheld_dock", "screen", "cartridge_case"))
    return result


def run(d, role="host"):
    path, out = record(d, "icons")
    out["scope"] = "Two actual GuiGraphics.renderItem pages: 28 hardware and 23 media entries, 51 distinct identity-free client previews. No inventory grants, machine/card UUIDs or ROM issuance; no physical-input or visual-acceptance claim."
    out["role"] = role
    expected = {
        "hardware": expected_hardware(d),
        "media": {("muxi_console:cartridge", cmd) for cmd in range(1, 22)} | {("muxi_console:blank_cartridge", 0), ("muxi_console:controller", 0)},
    }
    def inventory(view):
        return {k: view["cartridge"][k] for k in ("items", "cursor")}
    def complete(view, page):
        icons = view.get("icons", {})
        return icons.get("open") is True and icons.get("page") == page and icons.get("scale") == 2 and icons.get("renderSequence", 0) > 0 and icons.get("renderItemCalls") == icons.get("entryCount") == len(expected[page])
    def verify(view, page):
        icons = view["icons"]
        rows = icons["entries"]
        actual = {(r["item"], r["customModelData"]) for r in rows}
        check(out, complete(view, page) and len(rows) == len(actual) == len(expected[page]) and actual == expected[page], page + " native renderer draws every expected item/type/CMD once", icons)
        check(out, icons["sourceIconWidth"] == icons["sourceIconHeight"] == 16 and icons["unissuedPreviewsOnly"] is True and icons["inventoryWrites"] is False and all(r["unissued"] is True and r["slotGui"][2:] == [32, 32] for r in rows), page + " uses 16px identity-free previews enlarged 2x with actual slot bounds")
        return icons
    try:
        # Existing ordinary view action selects TutorialSteps.NONE; let old tutorial UI retire.
        d.command(role, "dock_view", {"hideGui": False})
        d.settle(role, 10)
        out["tutorialCleanup"] = {"action": "dock_view", "hideGui": False, "settledClientTicks": 10}
        initial = d.command(role, "observe")
        baseline = inventory(initial)
        check(out, initial["connected"] and not initial["room"] and not initial["workers"] and not initial["screen"], "icon review begins in the connected normal world after safe leave")
        for page in ("hardware", "media"):
            d.command(role, "icon_open", {"page": page, "scale": 2})
            before = d.wait_for(role, lambda v: complete(v, page), page + " actual icon render completion")
            before_icons = verify(before, page)
            capture = d.capture(role, "icons-" + page)
            after = d.command(role, "observe")
            after_icons = verify(after, page)
            check(out, after_icons["renderSequence"] >= before_icons["renderSequence"] and inventory(before) == inventory(after) == baseline, page + " remains the same actual page across native capture without changing inventory")
            capture.update(role=role, iconPage=page, before=before_icons, after=after_icons, scope="identity-free-native-item-preview")
            out["screenshots"].append(capture)
            d.command(role, "icon_close")
            d.wait_for(role, lambda v: not v["screen"] and not v.get("icons", {}).get("open"), page + " ordinary review screen close")
        final = d.command(role, "observe")
        check(out, inventory(final) == baseline and not final["room"] and not final["workers"] and len(out["screenshots"]) == 2, "all 51 previews leave original inventory and native-worker ownership unchanged")
        out["previewCount"] = sum(map(len, expected.values()))
        out["scenarioComplete"] = True
    except Exception as error:
        out["failure"] = str(error)
        raise
    finally:
        try:
            if d.command(role, "observe").get("icons", {}).get("open"):
                d.command(role, "icon_close")
        except Exception as error:
            out["cleanupFailure"] = str(error)
            out["scenarioComplete"] = False
        out["observationTimeouts"] = d.observation_timeouts
        path.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    return out


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--build", type=Path, required=True)
    parser.add_argument("--role", choices=("host", "guest"), default="host")
    args = parser.parse_args()
    result = run(Driver(args.run, args.build), args.role)
    print(json.dumps({"complete": result["scenarioComplete"], "previews": result.get("previewCount"), "originals": len(result["screenshots"])}))
