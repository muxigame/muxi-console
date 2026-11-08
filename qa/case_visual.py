"""Thin-case native follow-up on the normally closed copied world; never rebuilds fixtures or cards."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
from drive import Driver
from polish_review import record, check
from cartridge_review import cleanup
from case_review import CaseFront, CASE, at, box, original_box, cap


class ExistingCaseFront(CaseFront):
    def camera(self, x=22.5, z=25, yaw=180, pitch=35):
        self.d.command("server", "case_camera", dict(x=x, y=90, z=z, yaw=yaw, pitch=pitch))
        return self.settle(4)


def contents(item):
    return [entry["nbt"] for entry in item.get("contents", [])]


def permanent(world):
    """Identity and committed bytes are compared independently of inventory movement."""
    cards = sorted((item["card"], item["content"], item.get("insertedNbt", item["nbt"]))
                   for item in world["items"] if "card" in item)
    machines = sorted((item["machine"], item["item"], item["nbt"])
                      for item in world["items"] if "machine" in item)
    devices = sorted((item["blockPosition"], item.get("machineRead", "")) for item in world["devices"])
    saved = sorted((item["machine"], item["content"], item["generation"], item["hash"], item["diskSha256"])
                   for item in world["saved"])
    return dict(cards=cards, machines=machines, devices=devices, saved=saved)


def run(d):
    path, out = record(d, "case-visual")
    f = ExistingCaseFront(d, out)
    try:
        f.do("dock_view", {"hideGui": False})
        f.settle(10)
        # This only adopts existing objects. No prepare, clear, give, mint or burn command is used.
        out["existing"] = d.command("server", "case_existing")
        machine_before = out["machineBefore"] = d.command("server", "machine_observe")
        physical_machines = {item["machine"] for item in machine_before["items"] if "machine" in item}
        physical_machines.update(item["machineRead"] for item in machine_before["devices"] if item.get("machineRead"))
        check(out, len({item["card"] for item in machine_before["items"] if "card" in item}) == 4 and len(physical_machines) == 3,
              "the copied world starts with the four original card identities and three actual machines")
        out["iconFixtures"] = d.command("server", "case_icon_fixtures")["iconFixtures"]
        before = f.see()
        check(out, not before["room"] and not before["workers"], "previous machine session is normally saved and closed before case follow-up")
        for fixture in out["iconFixtures"].values():
            actual = at(before, fixture["slot"])
            check(out, actual["item"] == fixture["item"] and actual["count"] == 1 and
                  (not fixture["given"] or ("machine" not in actual and "instance" not in actual)),
                  "ordinary icon-only device is transparent and has no minted card or machine identity", fixture)
        out["scope"] = "Original copied-world case, 23 blanks, four issued cards and three machines are preserved. Only ordinary empty writer/dock inventory icon fixtures may be newly supplied to empty slots; installed devices remain untouched."
        original = box(before)
        out["originalCase"] = original
        check(out, len(contents(original)) == 23 and all(entry.get("item") == "muxi_console:blank_cartridge" and entry["count"] == 1 for entry in original["contents"]),
              "the actual original case still contains its 23 original blank stacks", original)
        f.inv()
        f.picture("case-thin-existing-inventory")
        source = original["slot"]
        if source > 8:
            destination = next(slot for slot in range(9) if at(f.see(), slot)["empty"])
            f.do("case_inventory_click", dict(slot=source, type="SWAP", button=destination))
            f.wait(lambda view: original_box(at(view, destination)), "normal inventory swap of original case into an empty hotbar slot")
            source = destination
        f.close()
        f.camera()
        f.do("slot", {"slot": source})
        f.do("dock_sneak_block", dict(x=22, y=89, z=22, face="UP"))
        placed = d.wait_for("server", lambda view: view.get("case", {}).get("block") == CASE,
                            "ordinary sneak placement of original case under the new thin model")["case"]
        out["placed"] = placed
        check(out, contents(placed["placed"]) == contents(original) and placed["placed"]["data"] == original["data"],
              "thin placed case preserves all 23 full blank stacks and original outer custom data")
        f.do("dock_view", {"hideGui": True})
        f.picture("case-thin-placed-folio")
        f.do("dock_view", {"hideGui": False})
        f.do("dock_use_block", dict(x=22, y=90, z=22, face="SOUTH"))
        view = f.wait(lambda view: view["case"].get("caseMenu") and view["case"].get("sourceSlot") == -1,
                      "normal native menu of original thin placed case")
        check(out, [cap(view, slot)["nbt"] for slot in range(27) if not cap(view, slot)["empty"]] == contents(original),
              "placed 27-slot native menu retains the original complete contents")
        f.picture("case-thin-native-container")
        f.close_case()
        view = f.see()
        tool = next(item for item in view["case"]["items"] if item.get("item") == "minecraft:diamond_pickaxe")
        tool_slot = tool["slot"]
        if tool_slot > 8:
            f.inv()
            f.do("case_inventory_click", dict(slot=tool_slot, type="SWAP", button=source))
            f.wait(lambda view: at(view, source).get("item") == "minecraft:diamond_pickaxe", "native inventory swap of existing break tool")
            f.close()
            tool_slot = source
        f.do("slot", {"slot": tool_slot})
        f.do("dock_break", dict(x=22, y=90, z=22))
        world = d.wait_for("server", lambda view: view.get("case", {}).get("block") == "minecraft:air" and bool(view.get("case", {}).get("drops")),
                           "normal survival break of thin case")["case"]
        check(out, len(world["drops"]) == 1 and world["drops"][0].get("item") == CASE and world["drops"][0]["count"] == 1,
              "thin case survival break returns exactly one whole original box", world["drops"])
        dropped = world["drops"][0]
        check(out, contents(dropped) == contents(original) and dropped["data"] == original["data"],
              "actual sole dropped case preserves all original complete stacks and outer custom data")
        out["wholeDrop"] = dropped
        f.camera(dropped["x"], dropped["z"], 180, 25)
        f.wait(lambda view: any(original_box(item) for item in view["case"]["items"]), "natural player pickup of the original thin case")
        returned = box(f.see())
        check(out, contents(returned) == contents(original) and returned["nbt"] == original["nbt"],
              "natural pickup preserves all 23 original blank stacks and byte-identical complete outer case NBT", returned)
        out["returnedCase"] = returned
        f.inv()
        f.picture("case-thin-returned-inventory")
        f.close()
        out["finalWorld"] = d.command("server", "case_observe")
        check(out, not out["finalWorld"]["drops"] and out["finalWorld"]["block"] == "minecraft:air",
              "thin-case follow-up leaves the original plate empty and no loose drops")
        out["machineAfter"] = d.command("server", "machine_observe")
        check(out, permanent(out["machineAfter"]) == permanent(machine_before),
              "four original full cards, three machine identities and every committed checkpoint remain unchanged through icon supply and thin-case use")
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
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", type=Path, required=True)
    parser.add_argument("--build", type=Path, required=True)
    args = parser.parse_args()
    result = run(Driver(args.run, args.build))
    print(json.dumps(dict(complete=result["scenarioComplete"], checks=len(result["checks"]), originals=len(result["screenshots"]))))
