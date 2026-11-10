"""Small follow-up scenarios against an owned run with a normally copied fixture world."""
from __future__ import annotations
import json
import sys
from PIL import Image


def adopted(driver):
    state=driver.command("server","observe")
    if not state["shared"].get("prepared"):
        adoption=driver.command("server","adopt_scene")
        if not adoption.get("adoptedCopiedFixture") or adoption.get("cartridgesMintedByQa")!=0 or adoption.get("inventoryReplaced"):
            raise ValueError("Existing authored fixture was not safely adopted")
        driver.command("server","supply_controllers")
    return driver.command("server","observe")


def save_result(driver,name,body,action):
    target=driver.channel/(name+"-result.json")
    if target.exists():
        raise ValueError("Preserve the existing result and use a new explicit continuation")
    body.update(runId=driver.marker["runId"],productSha256=driver.build["product"]["sha256"],scenarioComplete=False,physicalOSInput=False)
    try:
        action(body)
        body["scenarioComplete"]=True
    except Exception as error:
        body["failure"]=str(error)
        raise
    finally:
        body["observationTimeouts"]=driver.observation_timeouts
        target.write_text(json.dumps(body,ensure_ascii=False,indent=2),encoding="utf-8")
    return body


def block_device(state,x,z=8):
    position=(x<<38)|(z<<12)|90
    values=state["devices"] if z==8 else state["shared"]["devices"]
    return next(row for row in values if row["position"]==position)


def panel(driver,role,x,z=8):
    if z==20:
        driver.command("server","shared_position",{"role":role,"platform":"n64" if x==8 else "arcade"})
    else:
        current=driver.command(role,"observe")
        driver.command("server","position",{"player":current["playerName"],"x":10.5 if role=="host" else 11.5,"z":11.5})
    inventory=driver.command(role,"observe")["inventory"]
    empty=next(slot for slot in range(8,-1,-1) if not any(row["slot"]==slot for row in inventory))
    driver.command(role,"slot",{"slot":empty})
    driver.command(role,"use_block",{"x":x,"z":z})
    return driver.wait_for(role,lambda value:value["screen"].endswith("DeviceScreen"),"actual existing console panel")


def start(driver,role,x,z=8,port=0):
    panel(driver,role,x,z)
    state=driver.command("server","observe")
    if not block_device(state,x,z)["powered"]:
        driver.command(role,"button",{"text":"开机"})
    driver.command(role,"button",{"text":"P%d"%(port+1)})


def idle(value):
    return not value["room"] and not value["workers"] and not value["workerRunning"]


def leave_during_join(driver):
    def run(out):
        state=adopted(driver)
        if state["rooms"] or state["pendingUploads"]:
            raise ValueError("Follow-up requires no live room or upload")
        card=block_device(state,8,20)["card"]
        if card["platform"]!="n64" or card["hash"]!=driver.build["n64DiagnosticRom"]["sha256"]:
            raise ValueError("Copied source is not the exact existing authored N64 cartridge")
        out["genuineCartridge"]=card
        identities={row["name"]:row["uuid"] for row in state["players"]}
        start(driver,"host",8,20)
        host=driver.wait_for("host",lambda v:v["ready"] and v["workerRunning"] and v["frames"]>=45,"restored physical N64 source",timeout=180)
        guest_before=driver.command("guest","observe")
        start(driver,"guest",8,20,1)
        guest_id=identities[guest_before["playerName"]];host_id=identities[host["playerName"]]
        waiting=driver.wait_for("server",lambda v:any(r["pendingJoin"]==guest_id for r in v["rooms"]),"real guest is awaiting source checkpoint")
        room=next(r for r in waiting["rooms"] if r["pendingJoin"]==guest_id)
        driver.command("host","leave")
        # The history is sampled from public Room fields on real server ticks.
        accepted=driver.wait_for("server",lambda v:any(any(r["key"]==room["key"] and r["pendingJoin"]==guest_id and host_id in r["requestedLeaves"] for r in sample["rooms"]) for sample in v["roomHistory"]),"host leave accepted while the guest join is still pending")
        proof=next(sample for sample in accepted["roomHistory"] if any(r["key"]==room["key"] and r["pendingJoin"]==guest_id and host_id in r["requestedLeaves"] for r in sample["rooms"]))
        out["acceptedDuringPendingJoin"]={"before":room,"accepted":proof,"normalLeaveCalls":1,"powerOffUsed":False}
        closed=driver.wait_for("host",idle,"queued host leave commits and closes native source",timeout=240)
        remote=driver.wait_for("guest",idle,"pending guest automatically receives session end",timeout=90)
        final=driver.wait_for("server",lambda v:not v["rooms"] and not v["pendingUploads"],"server naturally removes the saved room")
        snapshot=next(row for row in final["states"] if row["card"]==card["instance"])
        if snapshot["hash"]!=closed["savedStateHash"] or snapshot["frame"]<room["frame"] or closed["normalWorkerExits"]<=host["normalWorkerExits"] or closed["forcedWorkerExits"] or closed["failedWorkerExits"] or remote["downloadsGranted"]!=guest_before["downloadsGranted"]:
            raise ValueError("Queued departure did not prove the final authoritative save and normal native closure")
        out["committedCheckpoint"]=snapshot
        out["normalWorkerExitVerified"]=True
        out["pendingGuestDownloadedRom"]=False
        out["finalRoles"]={role:{key:value[key] for key in ("room","workers","normalWorkerExits","forcedWorkerExits","failedWorkerExits")} for role,value in (("host",closed),("guest",remote))}
    return save_result(driver,"leave-during-join",{},run)


def nes_regression(driver):
    def run(out):
        state=adopted(driver)
        if state["rooms"]:raise ValueError("NES regression requires idle roles")
        device=block_device(state,10)
        if device["card"]["platform"]!="nes" or device["card"]["hash"]!=driver.build["diagnosticRom"]["sha256"]:raise ValueError("Existing authored NES source required")
        out["genuineCartridge"]=device["card"]
        while device["ports"]<2:
            panel(driver,"host",10)
            inventory=driver.command("host","observe")["inventory"]
            controller=next(row for row in inventory if row["item"]=="muxi_console:controller" and row["slot"]<=8)
            driver.command("host","slot",{"slot":controller["slot"]});driver.command("host","use_block",{"x":10})
            device=driver.wait_for("server",lambda v:block_device(v,10)["ports"]>device["ports"],"normal controller installs physical NES second port")["devices"]
            device=next(row for row in device if row["position"]==(10<<38)|(8<<12)|90)
        start(driver,"host",10)
        first=driver.wait_for("host",lambda v:v["ready"] and v["workerRunning"] and v["frames"]>=30,"ordinary NES P1 native ready",timeout=120)
        start(driver,"guest",10,port=1)
        driver.wait_for("host",lambda v:v["epoch"]>first["epoch"],"ordinary NES checkpoint advances epoch",timeout=120)
        two=driver.wait_for("guest",lambda v:v["ready"] and v["workerRunning"] and v["frames"]>=30,"ordinary NES P2 has its own lockstep native worker",timeout=150)
        one=driver.wait_for("host",lambda v:v["ready"] and v["epoch"]==two["epoch"] and v["crc"]==two["crc"],"both ordinary NES native pictures match",timeout=90)
        if two["port"]!=1 or one["port"]!=0 or len(one["workers"])!=1 or len(two["workers"])!=1 or one["restoredStateHash"]!=two["restoredStateHash"]:raise ValueError("Ordinary NES two-client restored lockstep differs")
        out["twoNativeWorkers"]={"host":one["workers"],"guest":two["workers"],"epoch":one["epoch"],"crc":one["crc"],"checkpoint":one["restoredStateHash"]}
        driver.command("guest","leave")
        driver.wait_for("guest",idle,"ordinary NES guest normally saves and closes",timeout=180)
        continuing=driver.wait_for("host",lambda v:v["ready"] and v["workerRunning"] and v["epoch"]>one["epoch"] and v["frames"]>=30,"ordinary NES P1 resumes after guest departure",timeout=150)
        driver.command("host","leave")
        final=driver.wait_for("host",idle,"ordinary NES final host save and native closure",timeout=180)
        driver.wait_for("server",lambda v:not v["rooms"],"ordinary NES room ends normally")
        if final["forcedWorkerExits"] or final["failedWorkerExits"] or final["normalWorkerExits"]<=continuing["normalWorkerExits"]:raise ValueError("Ordinary NES native closure failed")
        out["ordinaryGuestLeaveHostContinues"]=True;out["normalFinalHostLeave"]=True
    return save_result(driver,"nes-regression",{},run)


def server_command(driver,command):
    # The existing unified runner's owned native debug adapter executes vanilla commands.
    from drive import WORKSPACE
    sys.path.insert(0,str(WORKSPACE/"better-mc-remake/scripts"))
    import local_mc_debug as lab
    driver.verify()
    result=lab.command(driver.root,"server",{"type":"execute","command":command},timeout=30)
    if not result.get("ok"):raise RuntimeError("Owned vanilla command failed")
    return {"ok":True,"command":command}


def quick_review(driver):
    def run(out):
        adopted(driver);driver.browser_dependencies()
        out["availableGamepads"]={role:driver.command(role,"observe")["availableGamepads"] for role in ("host","guest")}
        out["physicalGamepadButtonsTested"]=False;out["physicalHotplugTested"]=False
        start(driver,"host",10)
        driver.wait_for("host",lambda v:v["ready"] and v["workerRunning"] and v["frames"]>=30,"existing NES source for visual follow-up",timeout=120)
        state=driver.command("server","observe");target={"position":state["browser"]["position"],"side":state["browser"]["side"]}
        player=driver.command("host","observe")["playerName"]
        driver.command("server","position",{"player":player,"x":13,"z":11.5})
        inventory=driver.command("host","observe")["inventory"]
        controller=next(row for row in inventory if row["item"]=="muxi_console:controller" and row["slot"]<=8)
        target["slot"]=controller["slot"]
        driver.command("host","browser_select",target)
        driver.wait_for("server",lambda v:v["browser"]["bindings"]==1,"actual existing display binds the same NES source")
        driver.wait_for("host",lambda v:v["browser"]["readyBrowsers"]==1 and v["browser"]["receivedFrames"]>=4,"actual CEF is rendering NES video",timeout=120)
        out["browserGpu"]=driver.gpu_sample("host",target)
        out["screenshots"]=[];samples={}
        guestname=driver.command("guest","observe")["playerName"]
        driver.command("server","position",{"player":guestname,"x":16,"z":14.5})
        for phase,tick in (("day",6000),("night",18000)):
            server_command(driver,"time set "+str(tick))
            driver.command("server","browser_camera",{"role":"host"});driver.command("host","review_view",{"held":False});driver.settle("host",20)
            picture=driver.capture("host","09-display-"+phase);out["screenshots"].append(picture)
            with Image.open(picture["path"]) as original:
                image=original.convert("RGB");w,h=image.size
                samples[phase]={name:list(image.getpixel((round(x*w/960),round(y*h/540)))) for name,(x,y) in {"builtinWhite":(455,300),"builtinPanel":(405,300),"browserWhite":(820,250),"browserPanel":(690,250)}.items()}
        out["readOnlyOriginalPngSamples"]=samples
        # Original screenshots are the visual acceptance evidence. Samples are
        # diagnostic until their actual projected positions have been reviewed.
        out["sampleCoordinatesRequireVisualReview"]=True
        driver.command("server","position",{"player":player,"x":13,"z":11.5});driver.command("host","browser_select",target)
        driver.wait_for("server",lambda v:v["browser"]["bindings"]==0,"visual follow-up unbind restores original URL")
        driver.command("host","leave");driver.wait_for("host",idle,"NES visual source saves and closes normally",timeout=180)
        driver.command("host","handheld_load");driver.command("host","handheld_use")
        portable=driver.wait_for("host",lambda v:v["ready"] and v["workerRunning"] and v["room"].startswith("handheld/") and v["frames"]>=30,"saved original GB runs in the existing GBA shell",timeout=120)
        out["handheldNative"]={key:portable[key] for key in ("room","crc","picture","heldModel")}
        driver.command("server","position",{"player":player,"x":10.5,"z":11.5})
        for phase,tick in (("day",6000),("night",18000)):
            server_command(driver,"time set "+str(tick))
            for hand in ("main","off"):
                if hand=="off":driver.command("host","swap_hands");driver.command("host","slot",{"slot":8})
                else:driver.command("host","slot",{"slot":2})
                driver.command("host","review_view",{"held":True});driver.settle("host",20)
                out["screenshots"].append(driver.capture("host","09-gba-gb-"+phase+"-"+hand))
                if hand=="off":driver.command("host","slot",{"slot":2});driver.command("host","swap_hands")
        driver.command("host","leave");driver.wait_for("host",idle,"GB visual source saves and closes normally",timeout=120)
        driver.command("host","handheld_load")
        out["normalNativeClosure"]=True;out["visualAcceptance"]=False
    return save_result(driver,"quick-review",{},run)


def held_live_review(driver):
    """Cold restore and photograph the existing genuine GB card; no new burn."""
    def run(out):
        state=adopted(driver)
        if state["rooms"]:raise ValueError("Cold handheld follow-up needs idle roles")
        current=driver.command("host","observe")
        cards=[card for card in current["cards"] if card["platform"]=="gb"]
        if len(cards)!=1 or cards[0]["hash"]!=driver.build["gbDiagnosticRom"]["sha256"]:raise ValueError("Unique pre-existing authored GB card required")
        card=cards[0];out["genuineCartridge"]=card
        saved=next(row for row in state["states"] if row["card"]==card["instance"])
        out["existingCheckpoint"]=saved
        if card["slot"]!=2:driver.command("host","handheld_load")
        driver.wait_for("host",lambda v:any(c["instance"]==card["instance"] and c["slot"]==2 for c in v["cards"]),"existing GB is loaded normally into original GBA shell")
        driver.command("host","handheld_use")
        portable=driver.wait_for("host",lambda v:v["ready"] and v["workerRunning"] and v["frames"]>=45 and v["room"]=="handheld/"+card["instance"] and v["restoredStates"]>current["restoredStates"],"fresh native worker cold-restores original GB state",timeout=120)
        if portable["restoredStateHash"]!=saved["hash"]:raise ValueError("GB native cold restore did not load the exact original authoritative checkpoint")
        out["coldRestore"]={"expectedHash":saved["hash"],"actualHash":portable["restoredStateHash"],"workers":portable["workers"],"frame":portable["frames"],"picture":portable["picture"]}
        out["availableGamepads"]={role:driver.command(role,"observe")["availableGamepads"] for role in ("host","guest")};out["physicalGamepadButtonsTested"]=False;out["physicalHotplugTested"]=False
        driver.command("server","position",{"player":portable["playerName"],"x":10.5,"z":11.5})
        out["screenshots"]=[]
        for phase,tick in (("day",6000),("night",18000)):
            server_command(driver,"time set "+str(tick))
            for hand in ("main","off"):
                driver.command("host","slot",{"slot":2})
                if hand=="off":driver.command("host","swap_hands");driver.command("host","slot",{"slot":8})
                driver.command("host","review_view",{"held":True})
                key="heldModel" if hand=="main" else "offhandModel"
                driver.wait_for("host",lambda v:v[key]==5,"actual loaded GBA shell in photographed hand")
                live=driver.settle("host",20)
                photo=driver.capture("host","gb-cold-restored-"+phase+"-"+hand)
                photo.update(phase=phase,hand=hand,nativeFrame=live["frames"],nativeCrc=live["crc"])
                out["screenshots"].append(photo)
                if hand=="off":driver.command("host","slot",{"slot":2});driver.command("host","swap_hands")
        before=driver.command("host","observe")
        driver.command("host","leave")
        closed=driver.wait_for("host",idle,"cold-restored GB normally saves and exits",timeout=120)
        state=driver.wait_for("server",lambda v:not v["rooms"] and not v["pendingUploads"],"GB final checkpoint committed")
        final=next(row for row in state["states"] if row["card"]==card["instance"])
        if final["hash"]!=closed["savedStateHash"] or final["frame"]<before["frames"] or closed["normalWorkerExits"]<=before["normalWorkerExits"] or closed["forcedWorkerExits"] or closed["failedWorkerExits"]:raise ValueError("Cold-restored GB final save or normal exit failed")
        out["finalCheckpoint"]=final;out["normalNativeClosure"]=True
        driver.command("host","handheld_load")
        unloaded=driver.wait_for("host",lambda v:any(c["instance"]==card["instance"] and c["slot"]!=2 for c in v["cards"]),"same GB card normally removed")
        shell=next(row for row in unloaded["inventory"] if row["slot"]==2)
        if shell["model"]!=5 or shell["qaPreserve"]!="original-gba-shell":raise ValueError("Cold restore altered GBA shell metadata")
        out["originalShellPreserved"]=True;out["visualAcceptance"]=False
    return save_result(driver,"held-live-review",{},run)
