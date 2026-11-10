"""Narrow, current-candidate native review of hands, focus, rebinding and real button feedback."""
from __future__ import annotations
import argparse
import json
import math
import time
from pathlib import Path
from drive import Driver,read_json,sha


def models(d:Driver,pause=False):
    target=d.channel/"handheld-model-refinement-result.json"
    if target.exists():raise ValueError("Preserve the original current-candidate model result")
    plan=d.build["modelReviewPlan"]
    if sha(Path(plan["path"]))!=plan["sha256"]:raise ValueError("Pinned model plan changed")
    stations=[s for s in read_json(plan["path"])["stations"] if s["form_factor"]in("handheld","viewer")]
    out={"runId":d.marker["runId"],"productSha256":d.build["product"]["sha256"],"scenarioComplete":False,"physicalOSInput":False,"visualAcceptance":False,"screenshots":[],"planSha256":plan["sha256"],"largeScreenScenarioSkipped":True}
    try:
        d.prepare();out["fixture"]=d.command("server","model_prepare");d.command("host","hand_arm",{"arm":"RIGHT"})
        for number,s in enumerate(stations):
            cmd=s["cmd"];d.command("server","model_camera",{"cmd":cmd,"held":True,"hand":"main"});d.command("host","slot",{"slot":7});d.command("host","review_view",{"held":True})
            v=d.wait_for("host",lambda v:v["heldModel"]==cmd and v["heldReview"]["render"].get("main",{}).get("model")==cmd,"current refined shell actually rendered with supporting hands")
            d.settle("host",12);v=d.command("host","observe");rr=v["heldReview"]["render"]["main"]
            if not(rr["armsVisible"]and rr["twoHands"]and rr["arm"]=="right"and rr["buttons"]==0 and rr["highlightedSurfaces"]==0):raise AssertionError("Current empty shell must render neutral with the actual two support arms")
            if rr["renderedArms"]!=2 or not rr["leftArmDrawn"]or not rr["rightArmDrawn"]:raise AssertionError("Both real PlayerRenderer arm branches must draw")
            row=d.capture("host","hand-model-"+s["platform"]+"-twohands");row.update(cmd=cmd,platform=s["platform"],render=rr,emptyFixtureHasNoCard=not bool(v["cards"]));out["screenshots"].append(row)
            if number==0 and pause:
                print(json.dumps({"firstHandsOriginal":row},ensure_ascii=False),flush=True)
                input("Review the untouched first-person hands image, then press Enter to continue: ")
        out["scenarioComplete"]=len(out["screenshots"])==10 and len({r["cmd"]for r in out["screenshots"]})==10
    except Exception as error:out["failure"]=str(error);raise
    finally:target.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding="utf-8")
    return out


def run(d:Driver,pause=False):
    target=d.channel/"handheld-refinement-result.json"
    if target.exists():raise ValueError("Preserve the original refinement result; use a fresh run")
    out={"runId":d.marker["runId"],"productSha256":d.build["product"]["sha256"],"scenarioComplete":False,"physicalOSInput":False,"visualAcceptance":False,"checks":[],"screenshots":[],"cards":{},"largeScreenScenarioSkipped":True}
    source=d.channel/"handheld-model-refinement-result.json";m=read_json(source)
    if not m.get("scenarioComplete")or m["runId"]!=d.marker["runId"]or m["productSha256"]!=d.build["product"]["sha256"]:raise ValueError("Current candidate's ten refined model originals must finish first")
    out["modelResult"]={"path":str(source),"sha256":sha(source)}
    host=lambda a,b=None:d.command("host",a,b)
    server=lambda a,b=None:d.command("server",a,b)
    def check(value,label,evidence=None):
        out["checks"].append({"label":label,"passed":bool(value),"evidence":evidence})
        if not value:raise AssertionError(label)
    def mask(v):
        f=v["heldReview"]["input"]
        if "input" in f:return f["input"]["buttons"]
        return f["buttons"]
    def render(v,hand="main"):
        value=v["heldReview"]["render"]
        if hand not in value:raise ValueError("Actual rendered "+hand+" frame is missing")
        return value[hand]
    def key(code,pressed=True):return host("hand_key",{"key":code,"pressed":pressed})
    def camera(x=10.5,z=11.5,yaw=180,pitch=12):
        server("dock_camera",dict(x=x,y=90,z=z,yaw=yaw,pitch=pitch));d.settle("host",8)
    def capture(name,platform,hand="main",expected_arm=None,two=None,buttons=0):
        prior=host("observe");old=render(prior,hand).get("sequence",-1)
        v=d.wait_for("host",lambda v:render(v,hand).get("sequence",-1)>old and render(v,hand).get("buttons")==buttons and(expected_arm is None or render(v,hand).get("arm")==expected_arm)and(two is None or render(v,hand).get("twoHands")==two),"actual new renderer frame with current local input and synchronized handedness")
        rr=render(v,hand)
        check(rr["armsVisible"] and (expected_arm is None or rr["arm"]==expected_arm) and (two is None or rr["twoHands"]==two),"actual "+name+" arm choice and hand count",rr)
        check(rr["renderedArms"]==(2 if two else 1) and rr["leftArmDrawn"]==(bool(two)or expected_arm=="left")and rr["rightArmDrawn"]==(bool(two)or expected_arm=="right"),"actual PlayerRenderer arm draw branches for "+name,rr)
        check((rr["highlightedSurfaces"]>0)==bool(buttons),"real button surfaces follow "+name+" mask",rr)
        row=d.capture("host",name);row.update(platform=platform,hand=hand,render=rr,feedback=v["heldReview"]["input"],room=v["room"],nativeFrame=v["frames"],nativeCrc=v["crc"]);out["screenshots"].append(row)
        return v
    def open_focus(platform):
        host("dock_f");v=d.wait_for("host",lambda v:v["screen"].endswith("HeldGameScreen"),"normal transparent focused handheld controls")
        focus=host("hand_profile",{"platform":platform})["handCallback"]["profile"]["focusKey"];key(focus,False)
        host("hand_sample_callbacks",{"platform":platform});return v
    def resume_focus(platform):
        v=host("observe")
        if not v["screen"].endswith("HeldGameScreen"):open_focus(platform)
        else:host("hand_sample_callbacks",{"platform":platform})
    def release():
        host("hand_release_keys");host("dock_release");d.wait_for("host",lambda v:not v["screen"] and mask(v)==0,"released world is neutral")
    def burn(platform):
        host("slot",{"slot":8});camera(8.5,11.5,180,18);host("use_block",{"x":8});d.wait_for("host",lambda v:v["screen"].endswith("WriterScreen"),"normal writer")
        host("writer_drop",{"rom":platform});host("writer_confirm");host("button",{"text":"上传并刻录"})
        v=d.wait_for("host",lambda v:any(c["platform"]==platform for c in v["cards"]),"normal rights-confirmed "+platform+" burn")
        c=next(c for c in v["cards"] if c["platform"]==platform);check(c["hash"]==d.build["gbDiagnosticRom" if platform=="gb" else "gbaDiagnosticRom"]["sha256"],"first-party diagnostic identity: "+platform,c);host("close_screen");return c
    def save(instance):
        before=host("observe");check(before["ready"] and before["workerRunning"],"actual worker alive before normal save")
        host("dock_esc");ended=d.wait_for("host",lambda v:not v["room"] and not v["workers"],"normal Esc checkpoint and worker exit")
        state=next(s for s in server("observe")["dock"]["states"] if s["card"]==instance)
        check(state["hash"]==ended["savedStateHash"] and ended["normalWorkerExits"]>before["normalWorkerExits"] and ended["forcedWorkerExits"]==ended["failedWorkerExits"]==0,"normal same-card checkpoint and natural worker close",state);return state
    def find_bind(prefix):
        for _ in range(8):
            v=host("observe")
            if any(label.startswith(prefix)for label in v["heldReview"]["buttons"]):return host("hand_button_prefix",{"prefix":prefix})
            host("button",{"text":"下页"})
        raise ValueError("Visible profile binding not found: "+prefix)
    try:
        d.prepare();out["fixture"]=server("pixel_live_prepare");server("dock_mode",{"mode":"survival"});host("hand_arm",{"arm":"RIGHT"});host("dock_view",{"hideGui":False})
        # Ordinary item placement, unchanged large display omitted.
        camera(12.5,9.7,180,27);host("slot",{"slot":5});host("dock_place",dict(x=12,y=89,z=6));d.wait_for("server",lambda v:"device"in v["dock"],"normal empty dock placement")
        for platform,slot,cmd in (("gb",2,3),("gba",7,5)):
            card=burn(platform);out["cards"][platform]=card
            host("pixel_handheld_use",{"slot":slot,"load":True});v=d.wait_for("host",lambda v:v["ready"]and v["workerRunning"]and v["room"]=="handheld/"+card["instance"]and v["frames"]>=(420 if platform=="gb"else 30),"real "+platform+" inside corresponding authored shell")
            check(not v["screen"] and v["heldModel"]==cmd,"picking up presents the real loaded shell in the world")
            camera();capture("hand-"+platform+"-twohands-idle",platform,expected_arm="right",two=True)
            # Only the existing ordinary controller is moved to the other hand.
            host("hand_offhand_toggle");d.wait_for("host",lambda v:not v["heldReview"]["offhandEmpty"],"ordinary controller in off hand")
            capture("hand-"+platform+"-main-right",platform,expected_arm="right",two=False)
            host("swap_hands");d.wait_for("host",lambda v:v["offhandModel"]==cmd,"same real card moved by vanilla swap")
            capture("hand-"+platform+"-off-left",platform,"off",expected_arm="left",two=False)
            if platform=="gb"and pause:
                print(json.dumps({"firstSingleHandsOriginals":out["screenshots"][-2:]},ensure_ascii=False),flush=True)
                input("Review both untouched single-hand images, then press Enter to continue: ")
            host("swap_hands");d.wait_for("host",lambda v:v["heldModel"]==cmd,"same real handheld returned to main hand")
            if platform=="gb":
                host("hand_arm",{"arm":"LEFT"});capture("hand-gb-main-left",platform,expected_arm="left",two=False)
                host("swap_hands");d.wait_for("host",lambda v:v["offhandModel"]==cmd,"left-handed player swaps same handheld to right offhand")
                capture("hand-gb-off-right",platform,"off",expected_arm="right",two=False)
                host("swap_hands");d.wait_for("host",lambda v:v["heldModel"]==cmd,"same handheld back in dominant left hand")
                host("hand_offhand_toggle");d.wait_for("host",lambda v:v["heldReview"]["offhandEmpty"],"other hand empty through ordinary inventory swap")
                capture("hand-gb-twohands-leftdominant",platform,expected_arm="left",two=True);host("hand_arm",{"arm":"RIGHT"})
            else:
                host("hand_offhand_toggle");d.wait_for("host",lambda v:v["heldReview"]["offhandEmpty"],"GBA other hand empty")
            # A world walking callback must move the actual player and leave the emulator neutral.
            before=host("observe");host("hand_world_key",{"name":"forward","pressed":True});walked=d.settle("host",12);host("hand_world_key",{"name":"forward","pressed":False})
            dist=math.hypot(walked["x"]-before["x"],walked["z"]-before["z"])
            check(dist>.1 and mask(walked)==0,"walking moves only Minecraft while holding "+platform,{"distance":dist,"input":walked["heldReview"]["input"]});camera()
            focused=open_focus(platform);same_worker=focused["workers"]
            if platform=="gb":
                default=host("hand_profile",{"platform":"gb"})["handCallback"]["profile"];check(default["aKey"]==74 and default["bKey"]==75,"GB normal A=J B=K defaults",default)
                host("button",{"text":"按键设置"});d.wait_for("host",lambda v:v["screen"].endswith("ControlsScreen") and mask(v)==0,"settings release all gameplay input")
                find_bind("A ·");key(86);key(86,False);host("button",{"text":"保存"})
                host("hand_button_prefix",{"prefix":"操作视角（所有平台）"});key(72);key(72,False);host("button",{"text":"保存"})
                profile=host("hand_profile",{"platform":"gb"})["handCallback"]["profile"];other=host("hand_profile",{"platform":"gba"})["handCallback"]["profile"]
                check(profile["aKey"]==profile["diskAKey"]==86 and other["aKey"]==other["diskAKey"]==74 and profile["focusKey"]==profile["diskFocusKey"]==72,"normal UI rebinding persists and leaves GBA independent",{"gb":profile,"gba":other})
                row=d.capture("host","hand-gb-key-settings");row.update(platform="gb",profile=profile);out["screenshots"].append(row)
                host("button",{"text":"返回"});d.wait_for("host",lambda v:v["screen"].endswith("HeldGameScreen"),"normal return to same focus surface");host("hand_sample_callbacks",{"platform":platform})
                original_world=host("observe")["dock"]["worldView"];key(70);key(70,False);check(host("observe")["dock"]["worldView"]==original_world,"old focus key does not switch presentation after rebind")
                key(72);key(72,False);check(host("observe")["dock"]["worldView"]!=original_world,"rebound focus key switches actual presentation");key(72);key(72,False)
                key(74);oldkey=d.settle("host",6);key(74,False);check(mask(oldkey)==0,"old A binding no longer feeds GB")
            a=86 if platform=="gb"else 74
            capture("hand-"+platform+"-focus-idle",platform,expected_arm="right",two=True)
            before=host("observe");key(a)
            pressed=d.wait_for("host",lambda v:mask(v)==256 and v["frames"]>before["frames"]and(platform!="gb"or v["crc"]!=before["crc"]),"normal rebound/default key reaches the actual active core and GB diagnostic")
            if platform=="gb":check(pressed["crc"]!=before["crc"],"GB diagnostic visibly responds to rebound A")
            capture("hand-"+platform+"-focus-a-pressed",platform,expected_arm="right",two=True,buttons=256)
            key(a,False);d.wait_for("host",lambda v:mask(v)==0,"A released")
            capture("hand-"+platform+"-focus-a-released",platform,expected_arm="right",two=True)
            key(87);key(75)
            d.wait_for("host",lambda v:mask(v)==17,"profile direction and B combined input")
            capture("hand-"+platform+"-focus-up-b",platform,expected_arm="right",two=True,buttons=17)
            key(87,False);key(75,False);d.wait_for("host",lambda v:mask(v)==0,"direction and B released")
            function_keys=(257,344)if platform=="gb"else(81,69,257);function_mask=12 if platform=="gb"else 3080
            for code in function_keys:key(code)
            d.wait_for("host",lambda v:mask(v)==function_mask,"actual functional capsule/shoulder input")
            capture("hand-"+platform+"-focus-functions",platform,expected_arm="right",two=True,buttons=function_mask)
            for code in function_keys:key(code,False)
            d.wait_for("host",lambda v:mask(v)==0,"functional keys released")
            same=host("observe");key(a);d.wait_for("host",lambda v:mask(v)==256,"actual A held across same-session presentation switch")
            host("dock_right_click");full=d.wait_for("host",lambda v:v["dock"].get("worldView")is False,"ordinary right click switches the same session to fullscreen")
            check(full["room"]==same["room"]and full["epoch"]==same["epoch"]and full["workers"]==same["workers"],"right-click fullscreen keeps actual room, epoch and worker")
            key(a,False);d.wait_for("host",lambda v:mask(v)==0,"normal A release after right-click fullscreen")
            focus=host("hand_profile",{"platform":platform})["handCallback"]["profile"]["focusKey"];key(focus);key(focus,False)
            world=d.wait_for("host",lambda v:v["dock"].get("worldView")is True and mask(v)==0,"configured view key returns to transparent world presentation neutral")
            check(world["room"]==same["room"]and world["epoch"]==same["epoch"]and world["workers"]==same["workers"],"view key returns without restarting the actual core")
            # Use the ordinary Screen branch of keyboard/mouse dispatch, not a private KeyboardHandler hook or forced world KeyMapping.
            before=host("observe")
            callbacks=[]
            for code in (87,81,49,70):callbacks.append(key(code)["handCallback"])
            callbacks.append(host("hand_mouse",{"button":0})["handCallback"])
            blocked=d.settle("host",10);host("hand_release_keys")
            same_position=math.hypot(blocked["x"]-before["x"],blocked["z"]-before["z"])<.02
            check(all(c["consumed"]for c in callbacks) and same_position and blocked["inventory"]==before["inventory"] and blocked["heldReview"]["selected"]==before["heldReview"]["selected"] and blocked["heldReview"].get("hitState")==before["heldReview"].get("hitState") and not blocked["heldReview"]["swinging"],"focused controls consume movement, attacks, drop, swap and hotbar callbacks",{"before":before["heldReview"],"after":blocked["heldReview"],"callbacks":callbacks,"samePosition":same_position})
            resume_focus(platform);key(a);d.wait_for("host",lambda v:mask(v)==256,"key held before release-controls callback")
            key(297);released=d.wait_for("host",lambda v:not v["screen"] and mask(v)==0,"normal F8 releases gameplay and clears the held button")
            check(released["workers"]==same_worker and released["ready"],"F8 keeps the same actual worker and session")
            # Opening another normal menu must not retain any pad state.
            resume_focus(platform);key(a);d.wait_for("host",lambda v:mask(v)==256,"key active before settings")
            host("button",{"text":"按键设置"});neutral=d.wait_for("host",lambda v:v["screen"].endswith("ControlsScreen") and mask(v)==0,"settings transition neutralizes held gameplay keys")
            if platform=="gb":
                host("button",{"text":"恢复默认"});host("hand_button_prefix",{"prefix":"操作视角（所有平台）"});key(261);key(261,False);host("button",{"text":"保存"});reset=host("hand_profile",{"platform":platform})["handCallback"]["profile"];check(reset["aKey"]==reset["diskAKey"]==74 and reset["focusKey"]==reset["diskFocusKey"]==70,"normal UI platform and focus default reset persists",reset)
            host("button",{"text":"返回"});d.wait_for("host",lambda v:v["screen"].endswith("HeldGameScreen"),"return from profile settings")
            check(mask(host("observe"))==0,"return from settings never restores a stale held key")
            out[platform+"Save"]=save(card["instance"])
        # The refined body and real live plane still share the unchanged low rotating dock transform.
        gba=out["cards"]["gba"];camera(14.5,9.7,148,23);host("dock_use_block",dict(x=12,y=90,z=6,face="SOUTH"))
        installed=d.wait_for("server",lambda v:v["dock"]["dockedHandheld"].get("instance")==gba["instance"],"same complete GBA installs normally")["dock"]
        check(installed["nbtRoundTrip"] and installed["dockedHandheld"]["model"]==5,"refined GBA body preserves full item metadata",installed["dockedHandheld"])
        host("slot",{"slot":8});host("dock_use_block",dict(x=12,y=90,z=6,face="SOUTH"));d.wait_for("host",lambda v:v["screen"].endswith("DeviceScreen"),"normal dock panel")
        host("button",{"text":"开机"});host("button",{"text":"P1"});running=d.wait_for("host",lambda v:v["ready"] and v["workerRunning"] and v["restoredStateHash"]==out["gbaSave"]["hash"],"same saved GBA resumes in the dock")
        host("dock_release");host("dock_view",{"hideGui":True});camera(14.5,9.7,148,23)
        deadline=time.monotonic()+45
        while time.monotonic()<deadline:
            clock=server("pixel_world")["gameTime"];angle=(clock%7200)*.5%360
            if 170<=angle<=235:break
            d.settle("host",8)
        out["dockCaptureAngle"]=angle
        for number in (1,2):
            if number==2:d.settle("host",48)
            current=host("observe");check(current["ready"] and current["workers"]==running["workers"] and current["room"]==running["room"],"same live GBA before dock original")
            row=d.capture("host","hand-refined-dock-"+str(number));row.update(platform="gba",instance=gba["instance"],nativeFrame=current["frames"],nativeCrc=current["crc"]);out["screenshots"].append(row)
        before=host("observe");host("leave");ended=d.wait_for("host",lambda v:not v["room"] and not v["workers"],"normal dock checkpoint and natural exit")
        final=next(s for s in server("observe")["dock"]["states"] if s["card"]==gba["instance"]);check(final["hash"]==ended["savedStateHash"] and ended["normalWorkerExits"]>before["normalWorkerExits"] and ended["forcedWorkerExits"]==ended["failedWorkerExits"]==0,"dock normal final checkpoint closes worker",final);out["dockSave"]=final
        host("slot",{"slot":8});host("dock_sneak_block",dict(x=12,y=90,z=6,face="SOUTH"));returned=d.wait_for("server",lambda v:v["dock"]["dockedHandheld"]["count"]==0 and sum(c["instance"]==gba["instance"] for c in v["dock"]["instances"])==1,"normal unique complete handheld retrieval")["dock"]
        out["returned"]=returned["instances"];out["scenarioComplete"]=True
    except Exception as error:out["failure"]=str(error);raise
    finally:
        try:
            host("hand_release_keys");host("hand_arm",{"arm":"RIGHT"})
            if host("observe")["room"]:host("leave");d.wait_for("host",lambda v:not v["room"] and not v["workers"],"normal failed-scope cleanup save")
            out["finalWorkers"]={r:d.command(r,"observe")["workers"] for r in ("host","guest")}
        except Exception as e:out["cleanupFailure"]=str(e);out["scenarioComplete"]=False
        out["observationTimeouts"]=d.observation_timeouts;target.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding="utf-8")
    return out


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("--run",required=True,type=Path);p.add_argument("--build",required=True,type=Path);p.add_argument("--phase",choices=("models","live","all"),default="models");p.add_argument("--pause-after-first",action="store_true");a=p.parse_args();d=Driver(a.run,a.build)
    if a.phase in("models","all"):
        result=models(d,a.pause_after_first);print(json.dumps({"phase":"models","complete":result["scenarioComplete"],"originals":len(result["screenshots"])}),flush=True)
    if a.phase in("live","all"):
        result=run(d,a.pause_after_first);print(json.dumps({"phase":"live","complete":result["scenarioComplete"],"checks":len(result["checks"]),"originals":len(result["screenshots"])}))
