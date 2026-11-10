"""Reuse four genuinely burned case-stage cards to test physical-machine save ownership."""
from __future__ import annotations
import argparse
import json
import time
from pathlib import Path
from drive import Driver, read_json, sha
from polish_review import record, check, reference
from cartridge_review import Front, at, items, raw, inserted, same_card, cleanup


def run(d, resume=None):
    path, out = record(d, "machine-continuation" if resume else "machine")
    host, guest = Front(d, out), Front(d, out, "guest")
    def world(): return d.command("server", "machine_observe")
    def wait_world(predicate, label):
        deadline = time.monotonic() + 60
        while time.monotonic() < deadline:
            value = world()
            if predicate(value): return value
            time.sleep(.2)
        raise TimeoutError(label)
    def free(f): return next(s for s in range(9, 36) if at(f.see(), s)["empty"])
    def stow(f):
        v = f.see()
        if v["screen"] and not v["screen"].endswith("InventoryScreen"): f.close()
        f.inv()
        if not at(f.see(), 8)["empty"]:
            f.do("cartridge_swap", {"slot": free(f), "hotbar": 8})
            f.wait(lambda v: at(v, 8)["empty"], "native swap preserves items and leaves a truly empty hand")
        f.do("slot", {"slot": 8})
        f.close()
    def choose(f, item, model=None):
        v = f.see()
        row = next(i for i in items(v) if i["item"] == item and (model is None or i["model"] == model))
        slot = row["slot"]
        if slot > 8:
            f.inv(); f.do("cartridge_swap", {"slot": slot, "hotbar": 8})
            f.wait(lambda v: at(v, 8)["item"] == item and (model is None or at(v, 8)["model"] == model), "native hotbar swap of real hardware")
            f.close(); slot = 8
        f.do("slot", {"slot": slot})
    def install(f, card, slot):
        stow(f); f.inv(); f.install(card, slot); f.close()
    def eject(f, card, slot):
        stow(f); f.inv(); f.eject(slot, card, free(f)); f.close()
    def offered_install(f, card, slot):
        if f.see()["screen"]: f.close()
        v = f.see()
        check(out, at(v, 40)["empty"] and inserted(v, slot)["empty"] and same_card(raw(v, card["instance"]), card), "actual raw borrowed card, empty offhand and empty second shell before ordinary offered-card use")
        f.do("select_card", {"instance": card["instance"]}); f.do("swap_hands")
        f.wait(lambda v: same_card(at(v, 40), card), "the actual borrowed card moves to the explicit other hand")
        f.do("slot", {"slot": slot}); f.do("cartridge_shift", {"pressed": True})
        f.do("cartridge_use", {"hand": "MAIN_HAND"})
        v = f.wait(lambda v: same_card(inserted(v, slot), card) and at(v, 40)["empty"], "normal offered other-hand insertion transfers the complete original card")
        check(out, at(v, slot)["storedSlots"] == 1, "second physical machine receives the original offered card exactly once")
        f.do("cartridge_shift", {"pressed": False})
    def offered_eject(f, card, slot):
        if f.see()["screen"]: f.close()
        f.do("cartridge_shift", {"pressed": True}); f.do("cartridge_use", {"hand": "MAIN_HAND"})
        f.wait(lambda v: inserted(v, slot)["empty"] and any(same_card(i, card) for i in items(v)), "normal Shift-use returns the borrowed complete card after safe save")
        f.do("cartridge_shift", {"pressed": False})
    def live(f, card, expected=None, minimum=30):
        v = f.wait(lambda v: v["ready"] and v["workerRunning"] and v["frames"] >= minimum and v["cartridge"]["progress"].get("card") == card["instance"] and (expected is None or v["restoredStateHash"] == expected), "real core and expected physical-machine checkpoint", 60)
        check(out, v["machine"].get("content") == card["hash"] and bool(v["machine"].get("machine")), "session save namespace carries the actual machine UUID and exact ROM SHA", v["machine"])
        return v
    def start(f, card, slot, expected=None, minimum=30):
        f.do("slot", {"slot": slot}); v = live(f, card, expected, minimum)
        check(out, at(v, slot).get("machine") == v["machine"]["machine"] and v["room"] == "handheld/" + v["machine"]["machine"], "handheld room uses the real physical item machine identity", {"item": at(v, slot), "owner": v["machine"], "room": v["room"]})
        return v
    def color(v, index):
        p = v.get("picture", {})
        return p.get("present") and all(p[q][index] > 200 and all(c < 50 for j, c in enumerate(p[q]) if j != index) for q in ("upper", "lower"))
    def paint(f, slot, key, channel, label):
        f.do("pixel_handheld_use", {"slot": slot, "load": False})
        f.wait(lambda v: v["screen"].endswith("GameScreen"), "normal handheld MODEL controls")
        f.do("polish_callbacks", {"platform": "gba"})
        f.do("polish_key", {"key": key, "pressed": True})
        changed = f.wait(lambda v: color(v, channel), "actual diagnostic " + label + " pixels")
        f.do("polish_key", {"key": key, "pressed": False})
        released = f.wait(lambda v: v["heldReview"]["input"]["input"]["buttons"] == 0 and color(v, channel), "real key release retains diagnostic register/color")
        check(out, changed["crc"] == released["crc"], label + " actual framebuffer persists after release", released["picture"])
        return released
    def saved(f, ended, owner, label):
        w = world(); state = next(s for s in w["saved"] if s["machine"] == owner["machine"] and s["content"] == owner["content"])
        expected = Path(w["worldStorage"]) / "states" / "machines" / owner["machine"] / owner["content"] / "cards" / owner["machine"] / (state["generation"] + ".state")
        check(out, state["diskSha256"] == state["hash"] == ended["savedStateHash"] and state["diskBytes"] == state["size"] > 0 and Path(state["path"]) == expected, label + " native checkpoint matches actual machine/ROM world path and disk bytes", state)
        return state
    def device(pos): return next(i for i in world()["devices"] if i["blockPosition"] == pos)
    def panel(f, x, z, face="SOUTH"):
        stow(f); f.do("dock_use_block", dict(x=x, y=90, z=z, face=face))
        return f.wait(lambda v: v["screen"].endswith("DeviceScreen"), "actual hardware management panel")
    def join(f, card, x, z, expected, face="SOUTH"):
        panel(f, x, z, face)
        if not device(f"{x}, 90, {z}")["powered"]: f.do("button", {"text": "开机"})
        f.do("button", {"text": "P1"})
        return live(f, card, expected)
    def panel_save(f, owner, label):
        before = f.see(); check(out, before["ready"] and before["workerRunning"], label + " is live before safe panel save")
        f.do("button", {"text": "保存并离开游戏"})
        ended = f.wait(lambda v: not v["room"] and not v["workers"], label + " normal panel checkpoint and close")
        check(out, ended["normalWorkerExits"] == before["normalWorkerExits"] + 1 and ended["forcedWorkerExits"] == ended["failedWorkerExits"] == 0, label + " worker exits naturally")
        return saved(f, ended, owner, label)
    try:
        previous = reference(d, out, "case"); cards = out["cards"] = previous["cards"]
        check(out, set(cards) == {"gba_a", "gba_b", "gb", "nes"} and cards["gba_a"]["hash"] == cards["gba_b"]["hash"] and cards["gba_a"]["instance"] != cards["gba_b"]["instance"], "reuse exactly four original case-stage cards, including two distinct same-ROM identities")
        a, b, gb, nes = (cards[k] for k in ("gba_a", "gba_b", "gb", "nes"))
        if resume:
            source = d.channel / "machine-result.json"; base = read_json(source)
            if sha(source) != resume or base["runId"] != d.marker["runId"] or base["productSha256"] != d.build["product"]["sha256"] or base["scenarioComplete"] or base.get("failure") != "java.lang.IllegalStateException: java.lang.IllegalStateException: Actual vanilla inventory screen required" or len(base["checks"]) != 19 or not all(c["passed"] for c in base["checks"]) or len(base["screenshots"]) != 2 or base.get("cleanupFailure") or any(c["workers"] for c in base["finalClients"].values()) or base["cards"] != cards or base.get("observationTimeouts"):
                raise ValueError("Only the exact normally cleaned original second-player creative-inventory scope may continue")
            out["machine"] = {"path": str(source), "sha256": resume}
            out["scope"] = "Keep original false/19 passed checks/two originals. The actual borrowed card is already picked up by guest. Complete only the remaining machine tests using ordinary other-hand insertion; do not replay burn, A first/second checkpoints or transfer."
            for key in ("machineA", "aFirstSave", "aSecondSave", "borrowedDrop"): out[key] = base[key]
            owner_a, a2 = out["machineA"], out["aSecondSave"]
            h, g = host.see(), guest.see(); actual = world()
            state = next(s for s in actual["saved"] if s["machine"] == owner_a["machine"] and s["content"] == a["hash"])
            check(out, state["hash"] == state["diskSha256"] == a2["hash"] and all(same_card(raw(h, c["instance"]), c) for c in (a, gb, nes)) and same_card(raw(g, b["instance"]), b) and at(h, 2).get("machine") == owner_a["machine"] and inserted(h, 2)["empty"] and inserted(g, 7)["empty"] and all(not v["room"] and not v["workers"] and v["cartridge"]["cursor"]["empty"] for v in (h, g)), "strict continuation starts from the actual same-card pickup, A checkpoint and idle machines", state)
        else:
            v = host.see()
            check(out, all(same_card(raw(v, c["instance"]), c) for c in cards.values()) and not v["room"] and not v["workers"], "all four exact full-NBT original cards are physically present before machine continuation")
            out["fixture"] = d.command("server", "machine_prepare")
            stow(host); host.camera(12.5, 9.5, 180, 20); choose(host, "muxi_console:handheld_dock")
            host.do("dock_place", dict(x=12, y=89, z=6)); d.wait_for("server", lambda v: "device" in v["dock"], "ordinary empty dock placement")
            stow(host)
            install(host, a, 2); first = start(host, a, 2, "")
            owner_a = out["machineA"] = first["machine"]
            check(out, color(first, 2), "new machine A starts original GBA blue rather than another machine's progress", first["picture"])
            red = paint(host, 2, 74, 0, "A-red"); host.pic("machine-a-original-card-red", red)
            a1 = saved(host, host.leave("A original cartridge"), owner_a, "A original cartridge"); out["aFirstSave"] = a1
            eject(host, a, 2); install(host, b, 2); second = start(host, b, 2, a1["hash"])
            check(out, second["machine"] == owner_a and color(second, 0), "a different cartridge UUID with the same ROM restores A's exact checkpoint and red framebuffer", second["picture"])
            host.pic("machine-a-second-card-same-progress", second)
            a2 = saved(host, host.leave("A second same-ROM cartridge"), owner_a, "A second same-ROM cartridge"); out["aSecondSave"] = a2; eject(host, b, 2)
            # Real dropped entity coordinates, never a guessed pickup position or an inventory grant.
            host.camera(10.5, 11.5, 180, 12); guest.camera(5.5, 14.5, 180, 12)
            host.do("select_card", {"instance": b["instance"]}); host.do("drop")
            host.wait(lambda v: not any(i.get("instance") == b["instance"] for i in items(v)), "borrowed physical card leaves the host")
            dropped = d.wait_for("server", lambda v: any(i.get("instance") == b["instance"] for i in v["dock"]["drops"]), "actual borrowed card world entity")["dock"]["drops"]
            entity = next(i for i in dropped if i.get("instance") == b["instance"])
            out["borrowedDrop"] = entity; guest.camera(entity["x"], entity["z"], 180, 20)
            guest.wait(lambda v: any(same_card(i, b) for i in items(v)), "guest picks up the same real complete card")
        offered_install(guest, b, 7); fresh = start(guest, b, 7, "")
        owner_b = out["machineB"] = fresh["machine"]
        check(out, owner_b["machine"] != owner_a["machine"] and owner_b["content"] == owner_a["content"] and color(fresh, 2), "borrowed red-A card starts independent blue progress on physical machine B", fresh["picture"])
        green = paint(guest, 7, 257, 1, "B-green"); guest.pic("machine-b-borrowed-card-independent-green", green)
        out["bFirstSave"] = saved(guest, guest.leave("B independent progress"), owner_b, "B independent progress")
        offered_eject(guest, b, 7)

        install(host, gb, 2); other = start(host, gb, 2, "", 420)
        owner_gb = other["machine"]
        check(out, owner_gb["machine"] == owner_a["machine"] and owner_gb["content"] != owner_a["content"], "another game on A has its own first checkpoint namespace", owner_gb)
        out["aGbSave"] = saved(host, host.leave("A different GB game"), owner_gb, "A different GB game")
        eject(host, gb, 2); install(host, a, 2); restored = start(host, a, 2, a2["hash"])
        check(out, restored["machine"] == owner_a and color(restored, 0), "returning to GBA after GB restores A's original red state rather than B's green state", restored["picture"])
        host.pic("machine-a-return-after-other-game", restored)
        a3 = saved(host, host.leave("A before docking"), owner_a, "A before docking"); out["preDockSave"] = a3

        host.camera(12.5, 4.1, 0, 25)
        host.do("dock_use_block", dict(x=12, y=90, z=6, face="NORTH"))
        d.wait_for("server", lambda v: v["dock"]["dockedHandheld"].get("instance") == a["instance"], "same physical A enters the dock")
        check(out, device("12, 90, 6")["machineRead"] == owner_a["machine"], "dock preserves installed handheld A's machine identity")
        docked = join(host, a, 12, 6, a3["hash"], "NORTH")
        check(out, docked["machine"] == owner_a and color(docked, 0), "dock restores the same handheld machine/ROM red checkpoint")
        host.close(); host.do("dock_view", {"hideGui": True}); host.pic("machine-a-dock-same-identity", docked)
        host.do("dock_view", {"hideGui": False}); panel(host, 12, 6, "NORTH")
        out["dockSave"] = panel_save(host, owner_a, "same A in dock")
        host.do("button", {"text": "取出掌机"})
        returned = host.wait(lambda v: any(i.get("machine") == owner_a["machine"] and i["item"] == "muxi_console:handheld" for i in items(v)), "normal whole A retrieval after dock save")
        shell = next(i for i in items(returned) if i.get("machine") == owner_a["machine"])
        check(out, same_card(shell.get("inserted", {}), a), "retrieved A retains the complete exact original card")
        host.close()

        # Desktop placement and survival break use the exact real dropped machine item.
        host.camera(16.5, 21.5, 180, 25); stow(host); choose(host, "muxi_console:desk_console", 1)
        host.do("dock_place", dict(x=16, y=89, z=18))
        wait_world(lambda v: any(i["blockPosition"] == "16, 90, 18" for i in v["devices"]), "ordinary NES desktop placement")
        desk_id = device("16, 90, 18")["machineRead"]; out["desktopMachine"] = desk_id
        host.do("select_card", {"instance": nes["instance"]}); host.do("dock_use_block", dict(x=16, y=90, z=18, face="SOUTH"))
        host.wait(lambda v: not any(i.get("instance") == nes["instance"] for i in items(v)), "actual original NES insertion")
        choose(host, "muxi_console:controller"); host.do("dock_use_block", dict(x=16, y=90, z=18, face="SOUTH"))
        desktop = join(host, nes, 16, 18, "")
        owner_d = out["desktopOwner"] = desktop["machine"]
        check(out, owner_d == {"machine": desk_id, "content": nes["hash"]}, "desktop session uses placed machine identity and exact NES game")
        host.close(); host.do("dock_view", {"hideGui": True}); host.pic("machine-desktop-before-break", desktop)
        host.do("dock_view", {"hideGui": False}); panel(host, 16, 18)
        desk_save = out["desktopFirstSave"] = panel_save(host, owner_d, "desktop before break")
        host.do("upgrade_click", {"slot": 0, "button": 0, "type": "QUICK_MOVE"}); host.wait(lambda v: any(same_card(i, nes) for i in items(v)), "NES full card retrieval after save"); host.close()
        choose(host, "minecraft:diamond_pickaxe"); host.do("dock_break", dict(x=16, y=90, z=18))
        broken = wait_world(lambda v: any(i.get("machine") == desk_id and i["item"] == "muxi_console:desk_console" for i in v["drops"]), "survival break emits actual identity-carrying desktop item")
        drop = next(i for i in broken["drops"] if i.get("machine") == desk_id)
        out["desktopDrop"] = drop; host.camera(drop["x"], drop["z"], 180, 25)
        host.wait(lambda v: any(i.get("machine") == desk_id and i["item"] == "muxi_console:desk_console" for i in items(v)), "ordinary pickup of the original desktop")
        host.camera(16.5, 21.5, 180, 25); choose(host, "muxi_console:desk_console", 1)
        host.do("dock_place", dict(x=16, y=89, z=18)); wait_world(lambda v: any(i.get("machineRead") == desk_id and i["blockPosition"] == "16, 90, 18" for i in v["devices"]), "same dropped physical desktop is placed again")
        host.do("select_card", {"instance": nes["instance"]}); host.do("dock_use_block", dict(x=16, y=90, z=18, face="SOUTH"))
        host.wait(lambda v: not any(i.get("instance") == nes["instance"] for i in items(v)), "original NES reinsertion after desk relocation")
        choose(host, "muxi_console:controller"); host.do("dock_use_block", dict(x=16, y=90, z=18, face="SOUTH"))
        replaced = join(host, nes, 16, 18, desk_save["hash"])
        check(out, replaced["machine"] == owner_d, "survival desktop break/re-place restores the original machine checkpoint")
        host.close(); host.do("dock_view", {"hideGui": True}); host.pic("machine-desktop-after-replace", replaced)
        host.do("dock_view", {"hideGui": False}); panel(host, 16, 18)
        out["desktopFinalSave"] = panel_save(host, owner_d, "replaced original desktop")
        host.do("upgrade_click", {"slot": 0, "button": 0, "type": "QUICK_MOVE"}); host.wait(lambda v: any(same_card(i, nes) for i in items(v)), "final exact NES retrieval"); host.close()

        final = out["finalWorld"] = world()
        keys = {(s["machine"], s["content"]) for s in final["saved"]}
        check(out, keys == {(owner_a["machine"], a["hash"]), (owner_a["machine"], gb["hash"]), (owner_b["machine"], b["hash"]), (desk_id, nes["hash"])}, "exactly four actual machine/game save namespaces exist", final["saved"])
        check(out, all(sum(i.get("card") == c["instance"] for i in final["items"]) == 1 for c in cards.values()) and not any(i.get("card") in {c["instance"] for c in cards.values()} for i in final["drops"]), "all four original cards survive exactly once with no loose or duplicated original identity")
        for c in cards.values():
            row = next(i for i in final["items"] if i.get("card") == c["instance"])
            check(out, row.get("insertedNbt", row["nbt"]) == c["nbt"], "final full original card NBT preserved for " + c["instance"], row)
        root = Path(final["worldStorage"])
        check(out, root.is_relative_to(d.root / "server") and root.name == "muxi-console", "storage root belongs to this actual server world", str(root))
        out["contentFiles"] = []
        for content in sorted({c["hash"] for c in cards.values()}):
            card = next(c for c in cards.values() if c["hash"] == content)
            file = root / "content" / content[:2] / (content + ".rom")
            row = {"path": str(file), "sha256": sha(file), "bytes": file.stat().st_size}
            check(out, row["sha256"] == content and row["bytes"] == card["bytes"], "actual server world ROM bytes agree with the original issued media", row)
            out["contentFiles"].append(row)
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
    p.add_argument("--continue-machine", action="store_true")
    p.add_argument("--source-sha256")
    a = p.parse_args()
    if a.continue_machine != bool(a.source_sha256): p.error("Continuation requires the exact preserved original result SHA")
    value = run(Driver(a.run, a.build), a.source_sha256)
    print(json.dumps(dict(complete=value["scenarioComplete"], checks=len(value["checks"]), originals=len(value["screenshots"]))))
