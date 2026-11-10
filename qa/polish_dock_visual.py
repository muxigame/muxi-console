"""Same-card dock framing supplement; preserve the occluded originals and all prior results."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
from drive import Driver,read_json,sha
from polish_review import record,check


def run(d,pause=False,resume=False):
    path,out=record(d,"polish-dock-visual-resumed"if resume else"polish-dock-visual");source=d.channel/"polish-play-continuation-result.json";previous=read_json(source)
    if previous["runId"]!=d.marker["runId"]or previous["productSha256"]!=d.build["product"]["sha256"]or not previous["scenarioComplete"]or previous["finalWorkers"]!={"host":[],"guest":[]}or len(previous["checks"])!=38 or len(previous["screenshots"])!=13:raise ValueError("Exact normally closed same-card continuation required")
    out["polish-play-continuation"]={"path":str(source),"sha256":sha(source)};out["sourceScope"]="The original two dock photos are occluded by the upgraded starter monitor. Retain them as activity evidence, supplement low rotating handheld geometry here."
    failed=None
    if resume:
        prior=d.channel/"polish-dock-visual-result.json";failed=read_json(prior)
        if failed["runId"]!=d.marker["runId"]or failed["productSha256"]!=d.build["product"]["sha256"]or failed["scenarioComplete"]or failed["screenshots"]or failed.get("cleanupFailure")or failed["finalWorkers"]!={"host":[],"guest":[]}or"getAsBoolean()"not in failed.get("failure",""):raise ValueError("Only the normally saved pre-photo QA boolean-argument failure may resume")
        out["polish-dock-visual"]={"path":str(prior),"sha256":sha(prior)}
    host=lambda a,b=None:d.command("host",a,b);server=lambda a,b=None:d.command("server",a,b);card=previous["cards"]["gba"]
    def camera():
        server("dock_camera",dict(x=12.5,y=90,z=4.1,yaw=0,pitch=25));d.settle("host",8)
    def capture(name):
        v=host("observe");check(out,v["ready"]and v["workerRunning"]and v["room"]==out["room"]and v["workers"]==out["workers"],"same live restored dock worker before "+name)
        row=d.capture("host",name);row["room"]=v["room"];row["workers"]=v["workers"];row["clock"]=server("pixel_world")["gameTime"];out["screenshots"].append(row)
    try:
        initial=host("observe");physical=server("observe")["dock"];state=next(s for s in physical["states"]if s["card"]==card["instance"])
        item=next(i for i in physical["instances"]if i["instance"]==card["instance"])
        expected=failed["finalCounters"]["host"]["savedStateHash"]if resume else previous["dockSave"]["hash"]
        check(out,not initial["room"]and not initial["workers"]and state["hash"]==expected and initial["savedStateHash"]==expected and physical["dockedHandheld"]["count"]==(1 if resume else 0)and item["location"]==("dock"if resume else"DebugHost/2")and item["qaPreserve"]=="original-gba-shell","original GBA and exact final checkpoint remain intact",state);out["sourceCheckpoint"]=state
        # PauseScreen is an ordinary UI that prevents auto-start while selecting the shell.
        if not resume:
            host("polish_pause");host("slot",{"slot":2});host("close_screen");camera()
            v=host("observe")
            if v["room"]:
                restored=d.wait_for("host",lambda v:v["ready"]and v["workerRunning"],"ordinary reheld session ready before safe stow");check(out,restored["room"]=="handheld/"+card["instance"]and restored["restoredStateHash"]==state["hash"],"ordinary reholding restores the exact prior final dock checkpoint");host("leave");d.wait_for("host",lambda v:not v["room"]and not v["workers"],"normal save suppresses reheld auto-start")
                state=next(s for s in server("observe")["dock"]["states"]if s["card"]==card["instance"]);out["preDockCheckpoint"]=state
            host("dock_use_block",dict(x=12,y=90,z=6,face="NORTH"));d.wait_for("server",lambda v:v["dock"]["dockedHandheld"].get("instance")==card["instance"],"same tagged GBA normally reinstalled")
            d.wait_for("host",lambda v:not v["room"]and not v["workers"],"handheld stow lifecycle complete before dock launch")
        camera()
        host("slot",{"slot":8});host("dock_use_block",dict(x=12,y=90,z=6,face="NORTH"));d.wait_for("host",lambda v:v["screen"].endswith("DeviceScreen"),"normal dock panel")
        if not server("observe")["dock"]["device"]["powered"]:host("button",{"text":"开机"})
        host("button",{"text":"P1"});v=d.wait_for("host",lambda v:v["ready"]and v["workerRunning"]and not v["room"].startswith("handheld/")and v["restoredStateHash"]==state["hash"],"same original GBA checkpoint restores through dock")
        out["room"]=v["room"];out["workers"]=v["workers"];out["restoredStateHash"]=v["restoredStateHash"];host("dock_esc");host("dock_view",{"hideGui":True});camera()
        capture("polish-dock-clean-a")
        if pause:print(json.dumps({"firstDockClean":out["screenshots"][-1]},ensure_ascii=False),flush=True);input("Review unobstructed raw dock image: ")
        d.settle("host",40);capture("polish-dock-clean-b");before=host("observe");host("leave");ended=d.wait_for("host",lambda v:not v["room"]and not v["workers"],"normal supplemented dock final save")
        final=next(s for s in server("observe")["dock"]["states"]if s["card"]==card["instance"])
        check(out,final["hash"]==ended["savedStateHash"]and ended["normalWorkerExits"]==before["normalWorkerExits"]+1 and ended["forcedWorkerExits"]==ended["failedWorkerExits"]==0,"same supplemented dock saves and its worker naturally closes",final);out["finalSave"]=final
        host("dock_sneak_block",dict(x=12,y=90,z=6,face="NORTH"));physical=d.wait_for("server",lambda v:v["dock"]["dockedHandheld"]["count"]==0,"normal supplemented intact retrieval")["dock"]
        items=[i for i in physical["instances"]if i["instance"]==card["instance"]];check(out,len(items)==1 and items[0]["model"]==5 and items[0]["qaPreserve"]=="original-gba-shell","exact original complete GBA returns once after supplement",items);out["returned"]=items[0];out["scenarioComplete"]=True
    except Exception as e:out["failure"]=str(e);raise
    finally:
        try:
            if host("observe")["room"]:host("leave");d.wait_for("host",lambda v:not v["room"]and not v["workers"],"normal supplement cleanup")
            clients={r:d.command(r,"observe")for r in("host","guest")};out["finalWorkers"]={r:v["workers"]for r,v in clients.items()};out["finalCounters"]={r:{k:v.get(k)for k in("normalWorkerExits","forcedWorkerExits","failedWorkerExits","savedStateHash","qaChannel")}for r,v in clients.items()}
        except Exception as e:out["cleanupFailure"]=str(e);out["scenarioComplete"]=False
        out["observationTimeouts"]=d.observation_timeouts;path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding="utf-8")
    return out


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("--run",required=True,type=Path);p.add_argument("--build",required=True,type=Path);p.add_argument("--pause-after-first",action="store_true");p.add_argument("--resume-failed",action="store_true");a=p.parse_args();v=run(Driver(a.run,a.build),a.pause_after_first,a.resume_failed);print(json.dumps({"complete":v["scenarioComplete"],"checks":len(v["checks"]),"originals":len(v["screenshots"])}))
