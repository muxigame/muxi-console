"""Supplement first-run framing and final-save evidence without rewriting its original result."""
from __future__ import annotations
import argparse
import json
import re
from pathlib import Path
from drive import Driver, read_json, sha


def run(d: Driver,pause_after_first=False,name=""):
    if name and not re.fullmatch(r"[a-z]+(?:-[a-z]+)*",name):raise ValueError("Simple supplemental label required")
    prefix="dock-visual"+("-"+name if name else "")
    original=d.channel/"dock-review-result.json";out=d.channel/(prefix+"-result.json")
    if out.exists():raise ValueError("Keep the prior visual result and choose a fresh run")
    prior=read_json(original)
    if prior["runId"]!=d.marker["runId"] or prior["productSha256"]!=d.build["product"]["sha256"]:raise ValueError("Native source generation differs")
    card=prior["card"];host=lambda a,b=None:d.command("host",a,b);server=lambda a,b=None:d.command("server",a,b)
    result={"runId":d.marker["runId"],"productSha256":d.build["product"]["sha256"],"sourceResult":{"path":str(original),"sha256":sha(original)},
            "sourceScope":{"screenPlacementAndHeldVerified":True,"dockFinalLeaveVerified":False,"lowRotationFramingAccepted":False,
                "notes":["Original photos 05/06 crop the dock below the framebuffer; they are preserved.","Original camera 07 exceeded the dock's eight-block control radius and ended that session; its following empty-room leave did not prove a normal final save."]},
            "scenarioComplete":False,"dockFinalLeaveVerified":False,"physicalOSInput":False,"visualAcceptance":False,"checks":0,"screenshots":[]}
    failed=d.channel/"dock-visual-result.json"
    if name:
        source=read_json(failed)
        if source.get("scenarioComplete") or source.get("runId")!=d.marker["runId"] or source.get("screenshots"):raise ValueError("This continuation requires the untouched failed pre-photo supplemental scope")
        result["previousSupplement"]={"path":str(failed),"sha256":sha(failed),"failure":source.get("failure")}
    def check(ok,label):
        result["checks"]+=1
        if not ok:raise AssertionError(label)
    def camera(x,z,yaw,pitch,y=90):
        distance=(x-12.5)**2+(y-90.5)**2+(z-6.5)**2
        check(distance<=64,"native capture remains inside the real dock control radius")
        server("dock_camera",dict(x=x,y=y,z=z,yaw=yaw,pitch=pitch));d.settle("host",8)
    def capture(name):
        current=host("observe")
        check(current["ready"] and current["workerRunning"] and current["room"]==result["dockedRoom"],"the actual docked core remains active before every visual capture")
        result["screenshots"].append(d.capture("host",name))
    try:
        current=server("observe")["dock"]
        check(len(current["instances"])==1 and current["instances"][0]["instance"]==card["instance"] and current["dockedHandheld"]["count"]==0,"the original retrieved card and empty dock are unchanged")
        host("dock_view",{"hideGui":False});host("slot",{"slot":8});d.settle("host",8);host("slot",{"slot":2})
        reopened=d.wait_for("host",lambda v:v["ready"] and v["workerRunning"] and v["room"]=="handheld/"+card["instance"],"normal re-holding of the original card")
        host("dock_esc")
        saved=d.wait_for("host",lambda v:not v["room"] and not v["workers"],"normal handheld save before reinstallation")
        vault=next(s for s in server("observe")["dock"]["states"] if s["card"]==card["instance"])
        check(vault["hash"]==saved["savedStateHash"],"the original handheld's normal final save reached the actual vault")
        result["preDockSave"]=vault
        camera(12.5,9.7,180,27)
        host("dock_use_block",dict(x=12,y=90,z=6,face="SOUTH"))
        installed=d.wait_for("server",lambda v:v["dock"]["dockedHandheld"].get("instance")==card["instance"],"normal reinstallation of the same intact handheld")["dock"]
        check(installed["nbtRoundTrip"] and len(installed["instances"])==1,"single intact original handheld after installation")
        host("slot",{"slot":8});host("dock_use_block",dict(x=12,y=90,z=6,face="SOUTH"))
        d.wait_for("host",lambda v:v["screen"].endswith("DeviceScreen"),"real dock panel")
        host("button",{"text":"启动 / 停止"});host("button",{"text":"连接附近屏幕"});host("button",{"text":"P1 加入"})
        running=d.wait_for("host",lambda v:v["ready"] and v["workerRunning"] and not v["room"].startswith("handheld/") and v["restoredStateHash"]==vault["hash"],"same dock P1 restores the original saved state")
        result["dockedRoom"]=running["room"];result["workerPids"]=running["workers"];result["restoredStateHash"]=running["restoredStateHash"]
        host("dock_release");host("dock_view",{"hideGui":True})
        camera(12.5,9.7,180,27);capture(prefix+"-01-low-rotation-a")
        if pause_after_first:
            print(json.dumps({"firstPhotoReady":result["screenshots"][0]},ensure_ascii=False),flush=True)
            input("Review the original frame, then press Enter to continue: ")
        d.settle("host",40);capture(prefix+"-02-low-rotation-b")
        camera(19,10,157,10);capture(prefix+"-03-screen-thin-side")
        camera(17.5,4.1,0,12);capture(prefix+"-04-screen-back")
        camera(15.5,13.5,180,5,y=90.4);capture(prefix+"-05-screen-front")
        before=host("observe");before_vault=next(s for s in server("observe")["dock"]["states"] if s["card"]==card["instance"])
        check(before["room"]==running["room"] and before["ready"] and before["workerRunning"] and before["workers"]==running["workers"],"the same active dock worker is still present immediately before normal leave")
        host("leave")
        ended=d.wait_for("host",lambda v:not v["room"] and not v["workers"],"real normal dock leave and worker close")
        final_vault=next(s for s in server("observe")["dock"]["states"] if s["card"]==card["instance"])
        check(final_vault["hash"]==ended["savedStateHash"],"normal dock leave committed its actual final client state")
        check(final_vault["generation"]!=before_vault["generation"] and final_vault["frame"]>=before["frames"],"normal leave advanced the actual card's vault generation")
        check(ended["normalWorkerExits"]>before["normalWorkerExits"] and not ended["forcedWorkerExits"] and not ended["failedWorkerExits"],"same native worker exited naturally without forced or failed closes")
        result["finalSave"]={"beforeVault":before_vault,"afterVault":final_vault,"clientSavedHash":ended["savedStateHash"],"normalWorkerExitsBefore":before["normalWorkerExits"],"normalWorkerExitsAfter":ended["normalWorkerExits"]}
        result["dockFinalLeaveVerified"]=True
        camera(12.5,9.7,180,27);host("slot",{"slot":8});host("dock_sneak_block",dict(x=12,y=90,z=6,face="SOUTH"))
        returned=d.wait_for("server",lambda v:v["dock"]["dockedHandheld"]["count"]==0 and len(v["dock"]["instances"])==1,"normal retrieval after the final verified dock save")["dock"]
        check(returned["instances"][0]["instance"]==card["instance"] and returned["instances"][0]["model"]==5 and returned["instances"][0]["qaPreserve"]=="original-gba-shell","final retrieval keeps the original card, shell and unrelated data exactly once")
        result["returned"]=returned["instances"][0];result["scenarioComplete"]=True
    except Exception as error:
        result["failure"]=str(error)
        raise
    finally:
        try:
            current=host("observe")
            if current["room"]:host("leave");d.wait_for("host",lambda v:not v["room"] and not v["workers"],"normal scoped supplemental cleanup")
            result["finalClients"]={role:d.command(role,"observe")["workers"] for role in ("host","guest")}
        except Exception as error:result["cleanupFailure"]=str(error);result["scenarioComplete"]=False
        out.write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding="utf-8")
    return result


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument("--run",type=Path,required=True);parser.add_argument("--build",type=Path,required=True);parser.add_argument("--pause-after-first",action="store_true");parser.add_argument("--name",default="");a=parser.parse_args()
    result=run(Driver(a.run,a.build),a.pause_after_first,a.name)
    print(json.dumps({"scenarioComplete":result["scenarioComplete"],"dockFinalLeaveVerified":result["dockFinalLeaveVerified"],"checks":result["checks"],"screenshots":len(result["screenshots"])}))
