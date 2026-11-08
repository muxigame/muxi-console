"""Real writer-issued cards through the portable, placed and survival-broken cartridge case."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
from drive import Driver
from polish_review import record, check
from cartridge_review import Front, cleanup, items, raw, same_card


CASE = "muxi_console:cartridge_case"


def invslot(slot):
    if 0 <= slot <= 8:
        return 54 + slot
    if 9 <= slot <= 35:
        return 27 + slot - 9
    raise ValueError("Case menu has no visible offhand slot")


def at(view, slot):
    return view["case"]["items"][slot]


def cap(view, slot):
    return view["case"]["slots"][slot]


def cursor(view):
    return view["case"]["cursor"]


def original_box(item):
    return item.get("item") == CASE and "qa_case_sentinel" in item.get("data", "")


def box(view):
    return next(i for i in view["case"]["items"] if original_box(i))


def content_cards(item):
    return {i["instance"]: i for i in item.get("contents", []) if "instance" in i}


class CaseFront(Front):
    def click_case(self, slot, button=0, kind="PICKUP"):
        self.do("case_click", dict(slot=slot, button=button, type=kind))
        return self.settle(2)

    def open_case(self, source, hand="MAIN_HAND"):
        if hand == "MAIN_HAND":
            self.do("slot", {"slot": source})
        self.do("case_use", {"hand": hand})
        return self.wait(lambda v: v["case"].get("caseMenu") and v["case"].get("sourceSlot") == source,
                         "actual portable 27-slot case menu")

    def close_case(self):
        self.close()
        return self.wait(lambda v: not v["case"].get("caseMenu"), "ordinary case menu close")

    def put_case(self, inventory, destination):
        self.click_case(invslot(inventory))
        self.click_case(destination)
        return self.wait(lambda v: cursor(v)["empty"], "native card placement in case")

    def picture(self, name):
        before = self.see()
        row = self.d.capture(self.role, name)
        after = self.see()
        row.update(role=self.role, before=before["case"], after=after["case"],
                   screenBefore=before["screen"], screenAfter=after["screen"])
        self.out["screenshots"].append(row)


def run(d):
    path, out = record(d, "case")
    f = CaseFront(d, out)
    out["cards"] = {}
    try:
        d.prepare()
        d.command("server", "dock_prepare")
        out["fixture"] = d.command("server", "case_prepare")
        slots = out["fixture"]["fixtureSlots"]
        source = slots["case"]
        f.do("dock_view", {"hideGui": False})
        # The ordinary writer performs every upload, rights confirmation and issuance.
        for key, platform, pin in (("gba_a", "gba", "gbaDiagnosticRom"), ("gba_b", "gba", "gbaDiagnosticRom"),
                                   ("gb", "gb", "gbDiagnosticRom"), ("nes", "nes", "diagnosticRom")):
            existing = {i.get("instance") for i in items(f.see())}
            f.camera(8.5, 11.5, 180, 18)
            f.do("slot", {"slot": 8})
            f.do("use_block", {"x": 8})
            f.wait(lambda v: v["screen"].endswith("WriterScreen"), "normal writer for " + key)
            f.do("writer_drop", {"rom": platform})
            f.do("writer_confirm")
            f.do("button", {"text": "上传并刻录"})
            view = f.wait(lambda v: any(i.get("hash") == d.build[pin]["sha256"] and i.get("instance") not in existing
                                       and i.get("item") == "muxi_console:cartridge" for i in items(v)),
                          "normal rights-confirmed issuance of " + key)
            card = next(i for i in items(view) if i.get("hash") == d.build[pin]["sha256"]
                        and i.get("instance") not in existing and i.get("item") == "muxi_console:cartridge")
            out["cards"][key] = card
            f.close()
            check(out, card["count"] == 1 and card["nbt"], key + " has one complete real writer-issued stack", card)
        cards = out["cards"]
        check(out, cards["gba_a"]["hash"] == cards["gba_b"]["hash"] and
              len({c["instance"] for c in cards.values()}) == 4,
              "same GBA ROM has distinct actual card identities; four normal cards exist")
        check(out, f.see()["downloadsGranted"] == 0, "burning and case storage do not download or run ROMs")
        view = f.open_case(source)
        check(out, len(view["case"]["slots"]) == 63 and all(cap(view, i)["empty"] for i in range(27)),
              "portable menu exposes exactly 27 empty storage slots plus 36 player slots")
        initial_box = at(view, source)
        # Real offered stacks exercise each rejection, rather than calling filter methods.
        for key in ("stone", "handheld", "spareCase"):
            origin = slots[key]
            f.click_case(invslot(origin))
            offered = cursor(f.see())
            view = f.click_case(4)
            check(out, cursor(view).get("nbt") == offered["nbt"] and cap(view, 4)["empty"],
                  "native menu rejects " + key + " without moving or splitting its complete stack")
            f.click_case(invslot(origin))
            f.wait(lambda v: cursor(v)["empty"], "rejected stack returned to its actual source")
        for index, card in enumerate(cards.values()):
            origin = raw(f.see(), card["instance"])["slot"]
            view = f.put_case(origin, index)
            check(out, same_card(cap(view, index), card), "actual card " + str(index) + " preserves its full NBT in case")
        # The native slot protects the actual source under pickup, shift, number, offhand and double-click inputs.
        for kind, button in (("PICKUP", 0), ("QUICK_MOVE", 0), ("SWAP", (source + 1) % 9),
                             ("SWAP", 40), ("PICKUP_ALL", 0)):
            view = f.click_case(invslot(source), button, kind)
            check(out, original_box(at(view, source)) and cursor(view)["empty"] and
                  all(same_card(cap(view, i), c) for i, c in enumerate(cards.values())),
                  "source slot stays locked for native " + kind + "/" + str(button))
        view = f.click_case(4, source, "SWAP")
        check(out, original_box(at(view, source)) and cap(view, 4)["empty"],
              "number key cannot move the held source case into an empty storage slot")
        # Right-drag one blank across a normal slot and the locked source; no direct container writes.
        f.click_case(invslot(slots["blank"]))
        f.click_case(-999, 4, "QUICK_CRAFT")
        f.click_case(4, 5, "QUICK_CRAFT")
        f.click_case(invslot(source), 5, "QUICK_CRAFT")
        view = f.click_case(-999, 6, "QUICK_CRAFT")
        check(out, cap(view, 4).get("item") == "muxi_console:blank_cartridge" and cap(view, 4)["count"] == 1
              and original_box(at(view, source)), "native right-drag deposits one blank while excluding the source slot")
        for destination in range(5, 27):
            f.click_case(destination, 1)
        f.click_case(invslot(slots["blank"]))
        view = f.wait(lambda v: cursor(v)["empty"], "remaining ordinary blanks returned to inventory")
        check(out, all(not cap(view, i)["empty"] for i in range(27)) and
              all(cap(view, i).get("item") == "muxi_console:blank_cartridge" for i in range(4, 27)),
              "all 27 actual slots accept four real cards and 23 blank-card stacks")
        durable = d.command("server", "case_observe")
        persisted = next(b for b in durable["boxes"] if "qa_case_sentinel" in b.get("data", ""))
        check(out, all(content_cards(persisted).get(c["instance"], {}).get("nbt") == c["nbt"] for c in cards.values()),
              "portable source component already contains all complete cards before menu close", persisted)
        f.picture("case-portable-27-slots")
        f.close_case()
        view = f.open_case(source)
        check(out, all(same_card(cap(view, i), c) for i, c in enumerate(cards.values())),
              "normal close and reopen preserve every original full card")
        f.close_case()
        # Move the same full case to the real offhand through a vanilla inventory swap.
        f.inv()
        f.do("case_inventory_click", dict(slot=source, type="SWAP", button=40))
        f.wait(lambda v: original_box(at(v, 40)) and at(v, source)["empty"], "normal complete-case offhand swap")
        f.close()
        f.open_case(40, "OFF_HAND")
        view = f.click_case(0, 40, "SWAP")
        check(out, original_box(at(view, 40)) and same_card(cap(view, 0), cards["gba_a"]),
              "offhand source cannot swap into a real card slot with key 40")
        f.close_case()
        f.inv()
        f.do("case_inventory_click", dict(slot=source, type="SWAP", button=40))
        f.wait(lambda v: original_box(at(v, source)) and at(v, 40)["empty"], "same whole case returns from offhand")
        f.close()
        before_place = box(f.see())
        out["beforePlace"] = before_place
        f.camera(22.5, 25, 180, 35)
        f.do("slot", {"slot": source})
        f.do("dock_sneak_block", dict(x=22, y=89, z=22, face="UP"))
        world = d.wait_for("server", lambda v: v.get("case", {}).get("block") == CASE,
                           "ordinary survival sneak placement of the real full case")["case"]
        check(out, all(content_cards(world["placed"]).get(c["instance"], {}).get("nbt") == c["nbt"] for c in cards.values())
              and "qa_case_sentinel" in world["placed"].get("data", ""),
              "placed block retains full original cards and outer custom data", world)
        out["placed"] = world
        f.do("dock_view", {"hideGui": True})
        f.picture("case-placed-thin-shell")
        f.do("dock_view", {"hideGui": False})
        f.do("dock_use_block", dict(x=22, y=90, z=22, face="SOUTH"))
        view = f.wait(lambda v: v["case"].get("caseMenu") and v["case"].get("sourceSlot") == -1,
                      "ordinary placed case opens its native block menu")
        check(out, all(same_card(cap(view, i), c) for i, c in enumerate(cards.values())),
              "placed case menu exposes the same complete actual cards")
        f.click_case(0, 0, "QUICK_MOVE")
        view = f.wait(lambda v: any(same_card(i, cards["gba_a"]) for i in items(v)) and cap(v, 0)["empty"],
                      "ordinary shift extraction from placed case")
        f.put_case(raw(view, cards["gba_a"]["instance"])["slot"], 0)
        f.close_case()
        f.do("slot", {"slot": 6})
        f.do("dock_break", dict(x=22, y=90, z=22))
        world = d.wait_for("server", lambda v: v.get("case", {}).get("block") == "minecraft:air" and
                           bool(v.get("case", {}).get("drops")), "ordinary survival block break and actual item drop")["case"]
        check(out, len(world["drops"]) == 1 and world["drops"][0]["item"] == CASE and world["drops"][0]["count"] == 1,
              "survival break drops exactly one whole case and no loose cards or blanks", world["drops"])
        dropped = world["drops"][0]
        check(out, all(content_cards(dropped).get(c["instance"], {}).get("nbt") == c["nbt"] for c in cards.values()),
              "the sole actual world drop retains every original full card NBT", dropped)
        out["wholeDrop"] = dropped
        f.picture("case-broken-whole-drop")
        f.camera(dropped["x"], dropped["z"], 180, 25)
        f.wait(lambda v: any(original_box(i) for i in v["case"]["items"]), "real player picks up the sole complete case")
        returned = box(f.see())
        check(out, all(content_cards(returned).get(c["instance"], {}).get("nbt") == c["nbt"] for c in cards.values())
              and "qa_case_sentinel" in returned.get("data", ""), "picked-up case preserves original cards and outer custom data", returned)
        out["returnedCase"] = returned
        f.inv()
        f.picture("case-returned-whole-inventory")
        returned_slot = returned["slot"]
        if returned_slot > 8:
            f.do("case_inventory_click", dict(slot=returned_slot, type="SWAP", button=source))
            f.wait(lambda v: original_box(at(v, source)), "normal inventory swap makes returned case usable")
            returned_slot = source
        f.close()
        f.open_case(returned_slot)
        for index, card in enumerate(cards.values()):
            f.click_case(index, 0, "QUICK_MOVE")
            f.wait(lambda v: any(same_card(i, card) for i in items(v)) and cap(v, index)["empty"],
                   "normal final retrieval of original " + card["instance"])
        f.close_case()
        final = f.see()
        for key, original in tuple(cards.items()):
            matches = [i for i in items(final) if i.get("instance") == original["instance"]]
            check(out, len(matches) == 1 and same_card(matches[0], original),
                  key + " returns exactly once to the host native inventory with byte-identical full NBT")
            cards[key] = dict(matches[0], nbtSha256=hashlib.sha256(matches[0]["nbt"].encode("utf-8")).hexdigest())
        out["finalWorld"] = d.command("server", "case_observe")
        check(out, not out["finalWorld"]["drops"] and not final["room"] and not final["workers"]
              and not any("instance" in i for b in out["finalWorld"]["boxes"] for i in b.get("contents", [])),
              "case phase leaves four original raw cards, no loose drop, no card in any box and no emulator worker")
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
