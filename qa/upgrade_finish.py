"""Finish only the six unphotographed card styles and daylight hardware after exact retained QA partials."""
from pathlib import Path
import argparse,json,re,sys
from drive import Driver,read_json,sha
from polish_review import record,check
from cartridge_review import cleanup,at,cursor,raw,inserted,same_card
from upgrade_review import Upgrade,inventory_slot

SOURCES=[("upgrade", "3f0ffe64234cb465e8a9e3d8e5b4746e28ff2e918631e5867ff8ae0b39ecfc8b",25,8),("upgrade-continuation","2e87023fab3d63249775fd4fd5d8a7fc4588ee478e6de9b8d6fe5e9c1dca7f61",16,2),("upgrade-presentation","2e3d6bfd457c1543106c98b487ff1d988f8ef9c63493016b89b7678dec8d90bc",6,15)]


def sources(d):
    found=[]
    for name,digest,checks,images in SOURCES:
        path=d.channel/(name+"-result.json");v=read_json(path)
        if sha(path)!=digest or v["runId"]!=d.marker["runId"]or v["productSha256"]!=d.build["product"]["sha256"]or v["scenarioComplete"]or v.get("cleanupFailure")or len(v["checks"])!=checks or not all(c["passed"]for c in v["checks"])or len(v["screenshots"])!=images:raise ValueError("Exact retained partial source required: "+name)
        found.append((path,v))
    cards=found[0][1]["cards"]
    if any(v["cards"]!=cards for _,v in found):raise ValueError("Original complete cards changed between sources")
    return found


def run(d):
    originals=sources(d);names=["upgrade-prop-"+hand+"-"+kind for kind in("card-nes","card-gba","card-wii")for hand in("right","left")]+["upgrade-day-held-ndscore","upgrade-day-held-n3dscore"]
    if any(not re.fullmatch("[a-z0-9-]{1,64}",name)for name in names):raise ValueError("All literal screenshot names must pass native whitelist before any action")
    if any((d.root/"host/screenshots"/(name+".png")).exists()for name in names):raise ValueError("Existing originals are immutable")
    path,out=record(d,"upgrade-finish");out.update(driverSha256=sha(Path(__file__)),sources=[dict(path=str(p),sha256=sha(p),scenarioComplete=False,passed=len(v["checks"]),images=len(v["screenshots"]))for p,v in originals],cards=originals[0][1]["cards"],fixture=originals[0][1]["fixture"],scope="Only remaining six card appearance images, requested two daylight held images, and final actual card/key preservation. No burns, games, crafting or key edits replayed.");f=Upgrade(d,out);cards=out["cards"];slots=out["fixture"]["fixtures"]
    try:
        v=f.see();guest=d.command("guest","observe");check(out,all(not x["room"]and not x["workers"]and cursor(x)["empty"]for x in(v,guest)),"adopt same normally cleaned real clients with no active worker")
        check(out,same_card(inserted(v,2),cards["gb"])and same_card(raw(v,cards["gba"]["instance"]),cards["gba"])and at(v,8)["empty"],"same original complete GB/GBA cards remain in their actual locations")
        core=f.player(2);out["sourceWorld"]=d.command("server","upgrade_observe");out["sourceInventory"]=f.view()["inventory"]
        # Ordinary canonical server commands change only lighting, after the previous QA
        # driver exited. No parallel QA channel, inventory setters or world rebuilding.
        sys.path.insert(0,str(Path(__file__).resolve().parents[2]/"better-mc-remake/scripts"));import local_mc_debug
        out["lightingCommands"]=[]
        for text in("time set 6000","gamerule doDaylightCycle false"):
            d.verify();out["lightingCommands"].append(local_mc_debug.command(d.root,"server",dict(type="execute",command=text)))
        f.settle(8)
        for kind in("card_nes","card_gba","card_wii"):
            d.command("server","upgrade_prop",dict(kind=kind));f.do("slot",dict(slot=slots["prop"]));f.settle(14);f.picture("upgrade-prop-right-"+kind)
            f.inv();f.click_menu(inventory_slot(slots["prop"]),40,"SWAP");f.close();f.do("slot",dict(slot=8));f.settle(14);f.picture("upgrade-prop-left-"+kind)
            f.inv();f.click_menu(inventory_slot(slots["prop"]),40,"SWAP");f.close()
        for key in("ndsCore","n3dsCore"):
            before=f.player(slots[key]);f.inv();f.click_menu(inventory_slot(slots[key]),8,"SWAP");f.close();f.do("slot",dict(slot=8));f.settle(14);f.picture("upgrade-day-held-"+key)
            f.inv();f.click_menu(inventory_slot(slots[key]),8,"SWAP");f.close();check(out,f.player(slots[key])["nbt"]==before["nbt"],"daylight observation returns the exact "+key+" stack without crafting or identity changes")
        out["worldEvidence"]=d.command("server","upgrade_observe");saved=out["worldEvidence"]["saved"];before=out["sourceWorld"]["saved"]
        check(out,saved==before and all((core["machine"],card["hash"])in{(s["machine"],s["content"])for s in saved}for card in cards.values()),"both real original machine/game disk checkpoints remain unchanged during appearance-only completion",saved)
        final=f.view();physical=[]
        for item in final["inventory"]:
            if item.get("item")=="muxi_console:cartridge"and item.get("instance"):physical.append(item)
            if item.get("inserted",{}).get("instance"):physical.append(item["inserted"])
        check(out,final["cursor"]["empty"]and f.player(2)["machine"]==core["machine"]and all(sum(i.get("instance")==card["instance"]and i.get("nbt")==card["nbt"]and i.get("count")==1 for i in physical)==1 for card in cards.values()),"each issued complete card remains exactly once and original machine UUID survives")
        check(out,final["keys"]["gb"]["8"]=="key.keyboard.j"and final["keys"]["wii"]["device"]==1,"previous actual UI tests returned GB key and Wii device to their original persisted choices")
        out["finalInventory"]=final["inventory"];out["scenarioComplete"]=True
    except Exception as failure:out["failure"]=str(failure);out["scenarioComplete"]=False;raise
    finally:cleanup(d,out);out["observationTimeouts"]=d.observation_timeouts;path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding="utf-8")
    return out


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("--run",type=Path,required=True);p.add_argument("--build",type=Path,required=True);a=p.parse_args();v=run(Driver(a.run,a.build));print(json.dumps(dict(complete=v["scenarioComplete"],checks=len(v["checks"]),images=len(v["screenshots"]))))
