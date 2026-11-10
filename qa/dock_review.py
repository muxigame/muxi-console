"""Drive the already-running, hash-bound handheld/dock/thin-screen native review."""
from __future__ import annotations

import argparse
import json
from pathlib import Path

from drive import Driver


def run(driver: Driver):
    output=driver.channel/"dock-review-result.json"
    if output.exists():
        raise ValueError("Preserve prior native results; use a fresh run")
    evidence={"runId":driver.marker["runId"],"productSha256":driver.build["product"]["sha256"],
              "scenarioComplete":False,"dockFinalLeaveVerified":False,"physicalOSInput":False,"visualAcceptance":False,"checks":0,"screenshots":[],"orientations":[]}
    host=lambda action,body=None:driver.command("host",action,body)
    server=lambda action,body=None:driver.command("server",action,body)
    def check(value,label):
        evidence["checks"]+=1
        if not value:raise AssertionError(label)
    def state(value):
        return {key:value.get(key) for key in ("room","epoch","ready","screen","frames","crc","workers","workerRunning","restoredStateHash","savedStateHash","normalWorkerExits","forcedWorkerExits","failedWorkerExits","dock","inventory","cards")}
    def camera(x,z,yaw=180,pitch=12,y=90):
        server("dock_camera",dict(x=x,y=y,z=z,yaw=yaw,pitch=pitch))
        driver.settle("host",4)
    def select(item):
        rows=host("observe")["inventory"]
        slot=next(row["slot"] for row in rows if row["item"]=="muxi_console:"+item)
        check(slot<=8,"fixture item remains in actual hotbar")
        host("slot",{"slot":slot})
        return slot
    def capture(name):
        driver.settle("host",8)
        evidence["screenshots"].append(driver.capture("host",name))
    def own_screen_rows(value):
        return value["dock"]["cells"]
    try:
        driver.prepare()
        server("dock_prepare")
        server("dock_mode",{"mode":"survival"})
        driver.wait_for("host",lambda v:v["gameMode"]=="survival","real survival mode")
        # The screen is placed by the actual held ScreenBlockItem, never constructed by QA.
        camera(16.5,21,180,25);select("screen")
        server("dock_obstacle",{"present":True})
        host("dock_place",dict(x=16,y=89,z=18))
        driver.settle("host",8)
        blocked=server("observe")["dock"]
        check(not blocked["cells"] and blocked["screenInventory"]==1 and blocked["screenDrops"]==0,"obstructed footprint refuses every tile and preserves its item")
        evidence["blockedFootprint"]=blocked
        server("dock_obstacle",{"present":False})
        cases=[("south",180,(16.5,21),(17,91,18)),("north",0,(16.5,15),(15,91,18)),
               ("east",90,(19.5,18.5),(16,91,17)),("west",270,(13.5,18.5),(16,91,19))]
        expected={"south":[(16+i%3,90+i//3,18) for i in range(6)],
                  "north":[(16-i%3,90+i//3,18) for i in range(6)],
                  "east":[(16,90+i//3,18-i%3) for i in range(6)],
                  "west":[(16,90+i//3,18+i%3) for i in range(6)]}
        for facing,yaw,(x,z),broken in cases:
            camera(x,z,yaw,25);select("screen")
            host("dock_place",dict(x=16,y=89,z=18))
            placed=driver.wait_for("server",lambda v:len(own_screen_rows(v))==6,"six physical "+facing+" screen blocks")["dock"]
            rows=sorted(placed["cells"],key=lambda row:row["part"])
            check([(r["x"],r["y"],r["z"]) for r in rows]==expected[facing],"screen columns expand to the viewer's right in "+facing)
            check([r["part"] for r in rows]==list(range(1,7)) and all(r["facing"]==facing for r in rows),"six stable parts and actual facing")
            check(len({r["identity"] for r in rows})==1 and len({r["origin"] for r in rows})==1,"one physical screen identity and anchor")
            check(all(abs(min(r["sizeX"],r["sizeZ"])-.125)<1e-7 for r in rows),"screen collision thickness is one eighth block")
            check(placed["screenInventory"]==0 and placed["screenDrops"]==0,"one screen item consumed")
            select("screen") if placed["screenInventory"] else host("slot",{"slot":6})
            host("dock_break",dict(zip(("x","y","z"),broken)))
            removed=driver.wait_for("server",lambda v:not own_screen_rows(v),"breaking a non-anchor removes all six "+facing+" parts")["dock"]
            check(removed["screenInventory"]+removed["screenDrops"]==1,"breaking one non-anchor yields exactly one screen item")
            camera(broken[0]+.5,broken[2]+.5,yaw,25)
            driver.wait_for("server",lambda v:v["dock"]["screenInventory"]==1 and v["dock"]["screenDrops"]==0,"normal world pickup of the one screen")
            evidence["orientations"].append(dict(facing=facing,placed=placed,removed=removed))
        # Leave one final screen and dock in their real item placement forms.
        camera(16.5,9.5,180,25);select("screen");host("dock_place",dict(x=16,y=89,z=6))
        final_screen=driver.wait_for("server",lambda v:len(own_screen_rows(v))==6,"final real 3 by 2 display")
        camera(12.5,9.5,180,25);select("handheld_dock");host("dock_place",dict(x=12,y=89,z=6))
        driver.wait_for("server",lambda v:"device" in v["dock"],"real placed handheld dock")
        host("slot",{"slot":8});camera(8.5,11.5,180,18)
        host("use_block",{"x":8})
        driver.wait_for("host",lambda v:v["screen"].endswith("WriterScreen"),"actual writer screen")
        host("writer_drop",{"rom":"gb"});host("writer_confirm");host("button",{"text":"上传并刻录"})
        burnt=driver.wait_for("host",lambda v:any(c["platform"]=="gb" for c in v.get("cards",[])),"real rights-confirmed GB cartridge")
        card=next(c for c in burnt["cards"] if c["platform"]=="gb")
        check(card["hash"]==driver.build["gbDiagnosticRom"]["sha256"],"original GB diagnostic identity")
        evidence["card"]=card
        host("close_screen");host("handheld_load")
        held=driver.wait_for("host",lambda v:v["ready"] and v["workerRunning"] and v["room"]=="handheld/"+card["instance"] and v["frames"]>=420,"automatically playing handheld without opening a GUI")
        check(held["screen"]=="" and held["dock"]["worldPlaying"],"holding the loaded handheld starts world presentation without a GUI")
        host("dock_view",{"hideGui":False});camera(10.5,12,180,12)
        capture("dock-01-held-world")
        before=host("observe");host("input_source",{"buttons":256})
        changed=driver.wait_for("host",lambda v:v["frames"]>before["frames"] and v["crc"]!=before["crc"],"actual no-GUI handheld input changes the native picture")
        evidence["worldInput"]={"before":state(before),"after":state(changed)}
        host("input_source",{"buttons":0});host("dock_use_item")
        item_full=driver.wait_for("host",lambda v:v["screen"].endswith(".GameScreen") and not v["dock"].get("worldView",True),"ordinary world right-click opens fullscreen")
        check(item_full["workers"]==held["workers"] and item_full["epoch"]==held["epoch"],"actual item right-click keeps the existing running worker")
        evidence["rightClickFromWorld"]=state(item_full)
        capture("dock-03-fullscreen")
        host("dock_release");host("dock_f")
        focused=driver.wait_for("host",lambda v:v["screen"].endswith("HeldGameScreen") and v["dock"].get("worldView"),"transparent focused handheld control screen")
        check(focused["workers"]==held["workers"] and focused["epoch"]==held["epoch"],"F keeps the same worker and control epoch")
        capture("dock-02-held-controls")
        host("dock_right_click")
        full=driver.wait_for("host",lambda v:v["screen"].endswith(".GameScreen") and not v["dock"].get("worldView",True),"right click enables fitted fullscreen")
        check(full["workers"]==held["workers"] and full["epoch"]==held["epoch"],"right-click fullscreen preserves the same emulation instance")
        host("dock_esc")
        closed=driver.wait_for("host",lambda v:not v["room"] and not v["workers"],"Esc normal handheld save and close")
        driver.settle("host",20)
        check(not host("observe")["workers"],"Esc suppresses immediate automatic reopening of the still-held cartridge")
        saved=next(s for s in server("observe")["dock"]["states"] if s["card"]==card["instance"])
        check(saved["hash"]==closed["savedStateHash"],"Esc writes the same actual state to the server vault")
        evidence["heldSaved"]=saved
        host("slot",{"slot":8});driver.settle("host",6);host("slot",{"slot":2})
        restored=driver.wait_for("host",lambda v:v["ready"] and v["workerRunning"] and v["restoredStateHash"]==saved["hash"],"putting away then holding restores the same saved cartridge")
        check(restored["screen"]=="","re-holding stays in the world")
        # Move it into the backpack while running: ordinary hotbar selection must save, not leave a worker behind.
        host("slot",{"slot":8})
        stowed=driver.wait_for("host",lambda v:not v["workers"] and not v["room"],"normal hotbar stow saves and closes the handheld")
        stowed_save=next(s for s in server("observe")["dock"]["states"] if s["card"]==card["instance"])
        check(stowed_save["hash"]==stowed["savedStateHash"],"stow grace commits the actual final save")
        evidence["stowedSaved"]=stowed_save
        host("slot",{"slot":2})
        driver.wait_for("host",lambda v:v["ready"] and v["restoredStateHash"]==stowed_save["hash"],"handheld resumes the stowed save")
        host("dock_esc");closed=driver.wait_for("host",lambda v:not v["room"] and not v["workers"],"save before installing the handheld")
        last_save=next(s for s in server("observe")["dock"]["states"] if s["card"]==card["instance"])
        camera(12.5,9.5,180,25)
        host("dock_use_block",dict(x=12,y=90,z=6,face="SOUTH"))
        installed=driver.wait_for("server",lambda v:v["dock"].get("dockedHandheld",{}).get("instance")==card["instance"],"normal item use installs the intact handheld")["dock"]
        check(len(installed["instances"])==1 and installed["instances"][0]["location"]=="dock","the physical cartridge exists only once, inside the docked handheld")
        check(installed["dockedHandheld"]["model"]==5 and installed["dockedHandheld"]["qaPreserve"]=="original-gba-shell","dock preserves the authored shell and unrelated custom data")
        check(installed["nbtRoundTrip"],"complete docked item survives real block-entity NBT serialization")
        evidence["installed"]=installed
        host("slot",{"slot":8});host("dock_use_block",dict(x=12,y=90,z=6,face="SOUTH"))
        driver.wait_for("host",lambda v:v["screen"].endswith("DeviceScreen"),"actual dock device panel")
        host("button",{"text":"开机"});host("button",{"text":"连接屏幕"});host("button",{"text":"P1"})
        docked=driver.wait_for("host",lambda v:v["ready"] and v["workerRunning"] and not v["room"].startswith("handheld/") and v["restoredStateHash"]==last_save["hash"],"dock continues the same card save through its real P1 seat")
        evidence["dockedRuntime"]=state(docked)
        host("dock_release");host("dock_view",{"hideGui":True})
        camera(15.5,13.5,180,5,y=90.4);capture("dock-04-screen-front")
        camera(12.5,9.7,180,27);capture("dock-05-low-rotation-a")
        driver.settle("host",28);capture("dock-06-low-rotation-b")
        camera(19,10,157,10);capture("dock-07-screen-thin-side")
        camera(17.5,4.1,0,12);capture("dock-08-screen-back")
        current=server("observe")["dock"]
        check(all(r["source"]=="12, 90, 6" for r in current["cells"] if r["part"]==1),"the actual large-screen anchor is linked to the dock")
        leaving=host("observe")
        check(leaving["room"]==docked["room"] and leaving["ready"] and leaving["workerRunning"],"dock session is still active before the final normal leave")
        leaving_vault=next(s for s in server("observe")["dock"]["states"] if s["card"]==card["instance"])
        host("leave")
        ended=driver.wait_for("host",lambda v:not v["workers"] and not v["room"],"normal dock session save and worker exit")
        check(not ended["forcedWorkerExits"] and not ended["failedWorkerExits"],"all actual native workers closed naturally")
        final_saved=next(s for s in server("observe")["dock"]["states"] if s["card"]==card["instance"])
        check(final_saved["generation"]!=leaving_vault["generation"] and final_saved["hash"]==ended["savedStateHash"] and ended["normalWorkerExits"]>leaving["normalWorkerExits"],"final normal dock leave commits a new generation of the actual state and closes its worker")
        evidence["dockFinalLeaveVerified"]=True
        camera(12.5,9.5,180,25);host("slot",{"slot":8})
        host("dock_sneak_block",dict(x=12,y=90,z=6,face="SOUTH"))
        returned=driver.wait_for("host",lambda v:any(c["instance"]==card["instance"] for c in v["cards"]),"normal empty-hand sneak use returns the original intact handheld")
        returned_item=next(i for i in returned["inventory"] if any(c["slot"]==i["slot"] and c["instance"]==card["instance"] for c in returned["cards"]))
        check(returned_item["item"]=="muxi_console:handheld" and returned_item["model"]==5 and returned_item["qaPreserve"]=="original-gba-shell","retrieval preserves the whole handheld, card UUID, shell and custom data")
        final=server("observe")["dock"]
        check(len(final["instances"])==1 and final["dockedHandheld"]["count"]==0,"retrieving cannot duplicate the handheld or cartridge")
        evidence["returned"]=returned_item;evidence["final"]=final
        evidence["scenarioComplete"]=True
    except Exception as error:
        evidence["failure"]=str(error)
        raise
    finally:
        try:
            current=host("observe")
            if current["room"]:host("leave");driver.wait_for("host",lambda v:not v["workers"] and not v["room"],"normal scoped failure cleanup")
            server("dock_mode",{"mode":"creative"})
            evidence["finalClients"]={role:state(driver.command(role,"observe")) for role in ("host","guest")}
        except Exception as error:
            evidence["cleanupFailure"]=str(error);evidence["scenarioComplete"]=False
        evidence["observationTimeouts"]=driver.observation_timeouts
        output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding="utf-8")
    return evidence


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run",required=True,type=Path);parser.add_argument("--build",required=True,type=Path)
    options=parser.parse_args();driver=Driver(options.run,options.build)
    result=run(driver)
    print(json.dumps({"runId":result["runId"],"scenarioComplete":result["scenarioComplete"],"checks":result["checks"],"screenshots":len(result["screenshots"])}))
