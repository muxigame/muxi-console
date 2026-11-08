"""Capture only this candidate's actual Minecraft hardware, then narrow GB/GBA/dock alignment."""
from __future__ import annotations
import argparse
import json
import time
from pathlib import Path
from drive import Driver, read_json, sha


def record(d,name):
    path=d.channel/name
    if path.exists():raise ValueError("Preserve original model evidence; use a fresh output scope")
    return path,{"runId":d.marker["runId"],"productSha256":d.build["product"]["sha256"],"physicalOSInput":False,"visualAcceptance":False,"scenarioComplete":False,"screenshots":[],"largeScreenScenarioSkipped":True}


def models(d,pause=False):
    output,evidence=record(d,"pixel-model-review-result.json")
    plan=d.build["modelReviewPlan"]
    if sha(Path(plan["path"]))!=plan["sha256"]:raise ValueError("Candidate-pinned model plan changed")
    stations=read_json(plan["path"])["stations"];evidence["planSha256"]=plan["sha256"]
    host=lambda action,body=None:d.command("host",action,body)
    def capture(station,held=False,hand="main"):
        cmd=station["cmd"];request={"cmd":cmd}
        if held:request.update(held=True,hand=hand)
        camera=d.command("server","model_camera",request)
        host("slot",{"slot":7 if held and hand=="main" else 8})
        host("review_view",{"held":held})
        if held:d.wait_for("host",lambda v:v["heldModel" if hand=="main" else "offhandModel"]==cmd,"current authored handheld is actually in the photographed hand")
        d.settle("host",16)
        name=Path(station["native_screenshot"]).stem
        if held:name="23-handhelds-first-person" if cmd==3 and hand=="main" else name+("-first-person" if hand=="main" else "-offhand-first-person")
        row=d.capture("host",name);row.update(cmd=cmd,held=held,hand=hand if held else "",camera=camera)
        evidence["screenshots"].append(row)
        return row
    try:
        d.prepare();evidence["fixture"]=d.command("server","model_prepare");evidence["accessories"]=d.command("server","pixel_props_prepare")
        nes=next(s for s in stations if s["cmd"]==1);gb=next(s for s in stations if s["cmd"]==3)
        first=[capture(nes),capture(gb,True,"main")]
        evidence["firstFramingSamples"]=first
        if pause:
            print(json.dumps({"firstModelAndHeldReady":first},ensure_ascii=False),flush=True)
            input("Review the two untouched Minecraft frames, then press Enter for the remaining bodies: ")
        capture({"cmd":0,"native_screenshot":"00-gallery-overview.png"})
        for station in stations:
            if station["cmd"]!=1:capture(station)
        for station in stations:
            if station["form_factor"] not in ("handheld","viewer"):continue
            for hand in ("main","off"):
                if station["cmd"]==3 and hand=="main":continue
                capture(station,True,hand)
        for group,name in (("media","pixel-all-21-media"),("accessories","pixel-accessories")):
            camera=d.command("server","pixel_props_camera",{"group":group});host("slot",{"slot":8});host("review_view",{"held":False});d.settle("host",16)
            row=d.capture("host",name);row.update(group=group,camera=camera);evidence["screenshots"].append(row)
        evidence["worldBodies"]=sum(1 for row in evidence["screenshots"] if row.get("cmd",0)>0 and not row.get("held"))
        evidence["heldBodies"]=sum(1 for row in evidence["screenshots"] if row.get("held"))
        evidence["nativeCaptureComplete"]=len(evidence["screenshots"])==45 and evidence["worldBodies"]==22 and evidence["heldBodies"]==20
        evidence["scenarioComplete"]=evidence["nativeCaptureComplete"]
        evidence["handednessRequiresVisualReview"]="North-facing local +X is viewer-left; the D-pad and A/B layout must be judged in these actual frames."
    except Exception as error:evidence["failure"]=str(error);raise
    finally:output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding="utf-8")
    return evidence


def live(d):
    output,out=record(d,"pixel-live-review-result.json")
    source=d.channel/"pixel-model-review-result.json";model=read_json(source)
    if not model.get("scenarioComplete") or model["runId"]!=d.marker["runId"] or model["productSha256"]!=d.build["product"]["sha256"]:raise ValueError("This exact candidate's model capture must finish first")
    if "gbaDiagnosticRom" not in d.build:raise ValueError("QA must be built with --pixel-review and its own GBA ROM pin")
    out["modelResult"]={"path":str(source),"sha256":sha(source)};out["checks"]=0
    host=lambda a,b=None:d.command("host",a,b);server=lambda a,b=None:d.command("server",a,b)
    def check(ok,label):
        out["checks"]+=1
        if not ok:raise AssertionError(label)
    def camera(x,z,yaw=180,pitch=12):
        server("dock_camera",dict(x=x,y=90,z=z,yaw=yaw,pitch=pitch));d.settle("host",10)
    def capture(name,slot,cmd,instance,hand="main"):
        if hand=="off":host("swap_hands");host("slot",{"slot":8})
        host("dock_view",{"hideGui":False})
        v=d.wait_for("host",lambda v:v["ready"] and v["workerRunning"] and v["heldModel" if hand=="main" else "offhandModel"]==cmd and any(c["instance"]==instance and c["slot"]==(40 if hand=="off" else slot) for c in v["cards"]),"real live cartridge is in the pictured authored shell")
        d.settle("host",12);row=d.capture("host",name);row.update(platform="gb" if cmd==3 else "gba",cmd=cmd,hand=hand,instance=instance,nativeFrame=v["frames"],nativeCrc=v["crc"]);out["screenshots"].append(row)
        if hand=="off":host("slot",{"slot":slot});host("swap_hands");d.wait_for("host",lambda v:any(c["instance"]==instance and c["slot"]==slot for c in v["cards"]),"vanilla hand swap returns the same loaded handheld")
    def burn(platform):
        host("slot",{"slot":8});camera(8.5,11.5,180,18);host("use_block",{"x":8});d.wait_for("host",lambda v:v["screen"].endswith("WriterScreen"),"normal writer for "+platform)
        host("writer_drop",{"rom":platform});host("writer_confirm");host("button",{"text":"上传并刻录"})
        value=d.wait_for("host",lambda v:any(c["platform"]==platform for c in v["cards"]),"normal own "+platform+" cartridge burn")
        card=next(c for c in value["cards"] if c["platform"]==platform)
        check(card["hash"]==d.build["gbDiagnosticRom" if platform=="gb" else "gbaDiagnosticRom"]["sha256"],"original diagnostic identity for "+platform)
        host("close_screen");return card
    def save(instance):
        before=host("observe");check(before["ready"] and before["workerRunning"],"active real worker before normal save")
        host("dock_esc");ended=d.wait_for("host",lambda v:not v["room"] and not v["workers"],"normal authored handheld save and exit")
        vault=next(s for s in server("observe")["dock"]["states"] if s["card"]==instance)
        check(vault["hash"]==ended["savedStateHash"] and ended["normalWorkerExits"]>before["normalWorkerExits"] and not ended["forcedWorkerExits"] and not ended["failedWorkerExits"],"actual same-card state and natural worker close")
        return vault
    try:
        out["fixture"]=server("pixel_live_prepare")
        # A normal dock placement is sufficient; unchanged screen and networking matrices are skipped.
        camera(12.5,9.7,180,27);host("slot",{"slot":5});host("dock_place",dict(x=12,y=89,z=6));d.wait_for("server",lambda v:"device" in v["dock"],"ordinary dock item placement")
        cards={}
        for platform,slot,cmd in (("gb",2,3),("gba",7,5)):
            card=burn(platform);cards[platform]=card
            host("pixel_handheld_use",{"slot":slot,"load":True})
            running=d.wait_for("host",lambda v:v["ready"] and v["workerRunning"] and v["room"]=="handheld/"+card["instance"] and v["frames"]>=(420 if platform=="gb" else 30),"actual "+platform+" diagnostic in its own new shell")
            check(running["screen"]=="" and running["heldModel"]==cmd,"direct world presentation of authored "+platform+" shell")
            before=host("observe");host("input_source",{"buttons":256});changed=d.wait_for("host",lambda v:v["frames"]>before["frames"] and v["crc"]!=before["crc"],"real native "+platform+" input changes the picture");host("input_source",{"buttons":0})
            out[platform+"Input"]={"beforeCrc":before["crc"],"afterCrc":changed["crc"],"workers":changed["workers"]}
            camera(10.5,11.5,180,12);capture("pixel-live-"+platform+"-main",slot,cmd,card["instance"]);capture("pixel-live-"+platform+"-off",slot,cmd,card["instance"],"off")
            out[platform+"Save"]=save(card["instance"])
            # Keep GB inside its GB shell. The GBA loader must find only the newly burned loose GBA card.
        gba=cards["gba"];camera(14.5,9.7,148,23);host("dock_use_block",dict(x=12,y=90,z=6,face="SOUTH"))
        installed=d.wait_for("server",lambda v:v["dock"]["dockedHandheld"].get("instance")==gba["instance"],"same new GBA handheld installs normally")["dock"]
        check(installed["nbtRoundTrip"] and installed["dockedHandheld"]["model"]==5 and installed["dockedHandheld"]["qaPreserve"]=="pixel-review-gba-shell" and sum(c["instance"]==gba["instance"] for c in installed["instances"])==1,"complete authored GBA shell, card and metadata stay unique in the dock")
        host("slot",{"slot":8});host("dock_use_block",dict(x=12,y=90,z=6,face="SOUTH"));d.wait_for("host",lambda v:v["screen"].endswith("DeviceScreen"),"normal dock device screen")
        host("button",{"text":"启动 / 停止"});host("button",{"text":"P1 加入"})
        running=d.wait_for("host",lambda v:v["ready"] and v["workerRunning"] and not v["room"].startswith("handheld/") and v["restoredStateHash"]==out["gbaSave"]["hash"],"same docked GBA card restores its actual checkpoint")
        host("dock_release");host("dock_view",{"hideGui":True});camera(14.5,9.7,148,23)
        # Read the real world clock only; wait for a visible front quarter without changing rotation or native output.
        deadline=time.monotonic()+45
        while time.monotonic()<deadline:
            clock=server("pixel_world")["gameTime"];angle=(clock%7200)*.5%360
            if 170<=angle<=235:break
            d.settle("host",8)
        out["dockCaptureAngle"]=angle
        for number in (1,2):
            if number==2:d.settle("host",48)
            current=host("observe");check(current["ready"] and current["workerRunning"] and current["room"]==running["room"] and current["workers"]==running["workers"],"same active docked core before each low-rotation original")
            row=d.capture("host","pixel-live-dock-rotation-"+str(number));row.update(instance=gba["instance"],nativeFrame=current["frames"],nativeCrc=current["crc"]);out["screenshots"].append(row)
        before=host("observe");check(before["ready"] and before["workerRunning"] and before["room"]==running["room"],"real dock worker remains active immediately before normal leave")
        host("leave");closed=d.wait_for("host",lambda v:not v["room"] and not v["workers"],"normal dock save and natural exit")
        final=next(s for s in server("observe")["dock"]["states"] if s["card"]==gba["instance"])
        check(final["hash"]==closed["savedStateHash"] and closed["normalWorkerExits"]>before["normalWorkerExits"] and not closed["forcedWorkerExits"] and not closed["failedWorkerExits"],"dock alignment spotcheck ends in a real same-card save and natural exit")
        out["dockSave"]=final;camera(14.5,9.7,148,23);host("slot",{"slot":8});host("dock_sneak_block",dict(x=12,y=90,z=6,face="SOUTH"))
        returned=d.wait_for("server",lambda v:v["dock"]["dockedHandheld"]["count"]==0 and sum(c["instance"]==gba["instance"] for c in v["dock"]["instances"])==1,"same GBA handheld retrieved after the visual spotcheck")["dock"]
        out["returned"]=returned["instances"];out["cards"]=cards;out["scenarioComplete"]=len(out["screenshots"])==6
    except Exception as error:out["failure"]=str(error);raise
    finally:
        try:
            if host("observe")["room"]:host("leave");d.wait_for("host",lambda v:not v["room"] and not v["workers"],"normal scoped pixel-review cleanup")
            out["finalWorkers"]={role:d.command(role,"observe")["workers"] for role in ("host","guest")}
        except Exception as error:out["cleanupFailure"]=str(error);out["scenarioComplete"]=False
        out["observationTimeouts"]=d.observation_timeouts;output.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding="utf-8")
    return out


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("--run",required=True,type=Path);p.add_argument("--build",required=True,type=Path);p.add_argument("--phase",choices=("models","live","all"),default="models");p.add_argument("--pause-after-first",action="store_true");a=p.parse_args();d=Driver(a.run,a.build)
    if a.phase in ("models","all"):result=models(d,a.pause_after_first);print(json.dumps({"phase":"models","complete":result["scenarioComplete"],"screenshots":len(result["screenshots"])}),flush=True)
    if a.phase=="all":
        from pixel_media_front import run as fronts
        fronts(d,"media");fronts(d,"accessories")
    if a.phase in ("live","all"):result=live(d);print(json.dumps({"phase":"live","complete":result["scenarioComplete"],"screenshots":len(result["screenshots"])}))
