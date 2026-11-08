"""Finish only the missing GBA/dock scope after the retained 05 reply-replacement failure."""
from __future__ import annotations
import argparse
import json
import math
import re
import time
from pathlib import Path
from drive import Driver,read_json,sha


def run(d:Driver,source_hash:str,checkpoint_hash:str):
    if not all(re.fullmatch(r"[a-f0-9]{64}",v)for v in(source_hash,checkpoint_hash)):raise ValueError("Exact source and observed checkpoint SHA256 are required")
    source=d.channel/"handheld-refinement-result.json";base=read_json(source)
    if sha(source)!=source_hash or base.get("scenarioComplete")is not False or base.get("failure")!="QA command timed out: host/hand_key"or base.get("cleanupFailure")or base.get("finalWorkers")!={"host":[],"guest":[]}:raise ValueError("Only the preserved, normally cleaned 05 callback-reply failure is resumable")
    if base["runId"]!=d.marker["runId"]or base["productSha256"]!=d.build["product"]["sha256"]or len(base["checks"])!=77 or not all(c["passed"]for c in base["checks"])or len(base["screenshots"])!=20 or "gbaSave"in base:raise ValueError("Do not widen the continuation to a different candidate or stage")
    target=d.channel/"handheld-refinement-continuation-result.json"
    if target.exists():raise ValueError("Preserve an existing continuation")
    host=lambda a,b=None:d.command("host",a,b)
    server=lambda a,b=None:d.command("server",a,b)
    gba=base["cards"]["gba"];card=gba["instance"]
    log=d.root/"host/boot.log";lines=log.read_text(encoding="utf-8",errors="replace").splitlines();matching=[(i+1,line)for i,line in enumerate(lines)if"Muxi Console QA refused:"in line and"reply-host.tmp ->"in line and"reply-host.json"in line]
    if len(matching)!=1:raise ValueError("The exact original atomic reply replacement failure must remain observable")
    out={"runId":d.marker["runId"],"productSha256":d.build["product"]["sha256"],"sourceResult":{"path":str(source),"sha256":source_hash},"modelResult":base["modelResult"],"originalScenarioComplete":False,"originalPassedChecks":77,"originalLiveOriginals":20,"scenarioComplete":False,"physicalOSInput":False,"visualAcceptance":False,"screenshots":[],"checks":[],"gbaCard":gba,"originalReplyFailure":{"path":str(log),"line":matching[0][0],"text":matching[0][1]},"missingScopeOnly":["GBA right-click/view-key same-worker transition","GBA world action isolation","GBA F8 and settings neutral release","GBA normal checkpoint and exit","same-card dock restore, two new originals, normal final save and complete retrieval"]}
    def check(ok,label,evidence=None):
        out["checks"].append({"label":label,"passed":bool(ok),"evidence":evidence})
        if not ok:raise AssertionError(label)
    def mask(v):return v["heldReview"]["input"]["input"]["buttons"]
    def key(code,pressed=True):return host("hand_key",{"key":code,"pressed":pressed})
    def camera(x=10.5,z=11.5,yaw=180,pitch=12):server("dock_camera",dict(x=x,y=90,z=z,yaw=yaw,pitch=pitch));d.settle("host",8)
    def focus():
        if not host("observe")["screen"].endswith("HeldGameScreen"):host("dock_f");d.wait_for("host",lambda v:v["screen"].endswith("HeldGameScreen"),"normal restored handheld focus")
        key(70,False);host("hand_sample_callbacks",{"platform":"gba"})
    try:
        h=host("observe");s=server("observe");guest=d.command("guest","observe")
        state=next(x for x in s["dock"]["states"]if x["card"]==card)
        check(not h["room"]and not h["workers"]and not guest["workers"]and h["normalWorkerExits"]==2 and h["forcedWorkerExits"]==h["failedWorkerExits"]==0,"original cleanup left both clients idle with two natural worker closes")
        check(state["hash"]==checkpoint_hash==h["savedStateHash"]and state["size"]==462928 and any(c["instance"]==card and c["slot"]==7 for c in h["cards"])and any(i["slot"]==7 and i["model"]==5 and i["qaPreserve"]=="pixel-review-gba-shell"for i in h["inventory"]),"actual original GBA checkpoint and full shell before resume",state)
        out["cleanupCheckpoint"]=state;out["initialHostReplyId"]=read_json(d.channel/"reply-host.json")["id"]
        camera();host("pixel_handheld_use",{"slot":7,"load":False})
        restored=d.wait_for("host",lambda v:v["ready"]and v["workerRunning"]and v["room"]=="handheld/"+card and v["restoredStateHash"]==checkpoint_hash,"fresh real GBA worker restores the exact retained cleanup checkpoint")
        check(restored["normalWorkerExits"]==2 and len(restored["workers"])==1,"only one new worker restores the original GBA")
        out["restored"]={k:restored[k]for k in("room","epoch","workers","restoredStateHash","frames")}
        host("dock_release");focus();profile=host("hand_profile",{"platform":"gba"})["handCallback"]["profile"]
        check(profile["aKey"]==profile["diskAKey"]==74 and profile["focusKey"]==profile["diskFocusKey"]==70,"restored continuation uses the actual saved GBA/default-focus profile",profile)
        same=host("observe");key(74);d.wait_for("host",lambda v:mask(v)==256,"fresh restored GBA receives normal A callback")
        host("dock_right_click");full=d.wait_for("host",lambda v:v["dock"].get("worldView")is False,"normal right click enters same-session fullscreen")
        check(full["room"]==same["room"]and full["epoch"]==same["epoch"]and full["workers"]==same["workers"],"GBA fullscreen preserves exact room, epoch and worker")
        key(74,False);d.wait_for("host",lambda v:mask(v)==0,"normal GBA key release after fullscreen")
        key(70);key(70,False);world=d.wait_for("host",lambda v:v["dock"].get("worldView")is True and mask(v)==0,"configured view key returns neutral to held world view")
        check(world["room"]==same["room"]and world["epoch"]==same["epoch"]and world["workers"]==same["workers"],"view return never restarts the GBA core")
        before=host("observe");callbacks=[key(code)["handCallback"]for code in(87,81,49,70)];callbacks.append(host("hand_mouse",{"button":0})["handCallback"])
        blocked=d.settle("host",10);host("hand_release_keys");distance=math.hypot(blocked["x"]-before["x"],blocked["z"]-before["z"])
        check(all(c["consumed"]for c in callbacks)and distance<.02 and blocked["inventory"]==before["inventory"]and blocked["heldReview"]["selected"]==before["heldReview"]["selected"]and blocked["heldReview"].get("hitState")==before["heldReview"].get("hitState")and not blocked["heldReview"]["swinging"],"GBA focus consumes movement, drop, hotbar, swap and attack callbacks",{"distance":distance,"before":before["heldReview"],"after":blocked["heldReview"]})
        focus();key(74);d.wait_for("host",lambda v:mask(v)==256,"real GBA input before F8")
        key(297);released=d.wait_for("host",lambda v:not v["screen"]and mask(v)==0,"F8 clears input and returns to the world")
        check(released["ready"]and released["workers"]==same["workers"],"F8 preserves the same restored GBA worker")
        focus();key(74);d.wait_for("host",lambda v:mask(v)==256,"real held GBA input before settings")
        host("button",{"text":"按键设置"});d.wait_for("host",lambda v:v["screen"].endswith("ControlsScreen")and mask(v)==0,"settings clear GBA gameplay keys")
        host("button",{"text":"返回"});d.wait_for("host",lambda v:v["screen"].endswith("HeldGameScreen")and mask(v)==0,"returning from GBA settings keeps released state")
        before=host("observe");check(before["ready"]and before["workerRunning"],"restored GBA alive immediately before ordinary Esc save")
        host("dock_esc");ended=d.wait_for("host",lambda v:not v["room"]and not v["workers"],"ordinary Esc commits restored GBA checkpoint and exits")
        saved=next(x for x in server("observe")["dock"]["states"]if x["card"]==card)
        check(saved["hash"]==ended["savedStateHash"]and ended["normalWorkerExits"]==before["normalWorkerExits"]+1 and ended["forcedWorkerExits"]==ended["failedWorkerExits"]==0,"new actual same-card GBA checkpoint and natural worker exit",saved);out["gbaSave"]=saved
        camera(14.5,9.7,148,23);host("dock_use_block",dict(x=12,y=90,z=6,face="SOUTH"))
        installed=d.wait_for("server",lambda v:v["dock"]["dockedHandheld"].get("instance")==card,"same complete GBA normally installs in the existing dock")["dock"]
        check(installed["nbtRoundTrip"]and installed["dockedHandheld"]["model"]==5 and installed["dockedHandheld"]["qaPreserve"]=="pixel-review-gba-shell"and sum(c["instance"]==card for c in installed["instances"])==1,"original GBA shell/card/tag remain unique and round-trip in dock",installed["dockedHandheld"])
        host("slot",{"slot":8});host("dock_use_block",dict(x=12,y=90,z=6,face="SOUTH"));d.wait_for("host",lambda v:v["screen"].endswith("DeviceScreen"),"normal dock device panel")
        host("button",{"text":"启动 / 停止"});host("button",{"text":"P1 加入"});running=d.wait_for("host",lambda v:v["ready"]and v["workerRunning"]and not v["room"].startswith("handheld/")and v["restoredStateHash"]==saved["hash"],"docked same GBA restores the new normal save")
        host("dock_release");host("dock_view",{"hideGui":True});camera(14.5,9.7,148,23)
        deadline=time.monotonic()+45
        while time.monotonic()<deadline:
            clock=server("pixel_world")["gameTime"];angle=(clock%7200)*.5%360
            if 170<=angle<=235:break
            d.settle("host",8)
        out["dockCaptureAngle"]=angle
        for number in(1,2):
            if number==2:d.settle("host",48)
            current=host("observe");check(current["ready"]and current["room"]==running["room"]and current["workers"]==running["workers"],"same real dock worker remains active before original "+str(number))
            row=d.capture("host","hand-resumed-dock-"+str(number));row.update(platform="gba",instance=card,nativeFrame=current["frames"],nativeCrc=current["crc"],room=current["room"]);out["screenshots"].append(row)
        before=host("observe");check(before["ready"]and before["workerRunning"],"actual dock worker alive immediately before normal final leave")
        host("leave");ended=d.wait_for("host",lambda v:not v["room"]and not v["workers"],"normal final dock checkpoint and worker exit")
        final=next(x for x in server("observe")["dock"]["states"]if x["card"]==card)
        check(final["hash"]==ended["savedStateHash"]and ended["normalWorkerExits"]==before["normalWorkerExits"]+1 and ended["forcedWorkerExits"]==ended["failedWorkerExits"]==0,"real dock checkpoint matches client and closes naturally",final);out["dockSave"]=final
        host("slot",{"slot":8});host("dock_sneak_block",dict(x=12,y=90,z=6,face="SOUTH"));returned=d.wait_for("server",lambda v:v["dock"]["dockedHandheld"]["count"]==0 and sum(c["instance"]==card for c in v["dock"]["instances"])==1,"normal unique complete GBA retrieval")["dock"]
        item=next(c for c in returned["instances"]if c["instance"]==card)
        check(item["model"]==5 and item["qaPreserve"]=="pixel-review-gba-shell"and item["hash"]==gba["hash"],"returned handheld preserves original identity, platform shell, ROM and custom data",item)
        out["returned"]=returned["instances"];out["scenarioComplete"]=True
    except Exception as error:out["failure"]=str(error);raise
    finally:
        try:
            host("hand_release_keys");host("hand_arm",{"arm":"RIGHT"})
            if host("observe")["room"]:host("leave");d.wait_for("host",lambda v:not v["room"]and not v["workers"],"normal scoped continuation cleanup")
            out["finalWorkers"]={r:d.command(r,"observe")["workers"]for r in("host","guest")}
            out["finalHostReplyId"]=read_json(d.channel/"reply-host.json")["id"]
        except Exception as error:out["cleanupFailure"]=str(error);out["scenarioComplete"]=False
        out["observationTimeouts"]=d.observation_timeouts;target.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding="utf-8")
    return out


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("--run",required=True,type=Path);p.add_argument("--build",required=True,type=Path);p.add_argument("--source-sha256",required=True);p.add_argument("--checkpoint-sha256",required=True);a=p.parse_args();r=run(Driver(a.run,a.build),a.source_sha256,a.checkpoint_sha256);print(json.dumps({"complete":r["scenarioComplete"],"checks":len(r["checks"]),"originals":len(r["screenshots"])}))
