"""Audit the already-live reversed Pong seats after the recorded QA file-sharing failure."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
from drive import Driver,read_json,sha
from shared_drive import matched_batches,seat


def finish(driver):
    original_path=driver.channel/"shared-arcade-result.json"
    original=read_json(original_path)
    output=driver.channel/"shared-arcade-finalized-result.json"
    if output.exists():
        raise ValueError("Preserve existing finalization evidence")
    evidence={"runId":driver.marker["runId"],"productSha256":driver.build["product"]["sha256"],"physicalOSInput":False,"visualAcceptance":False,"scenarioComplete":False,"screenshots":[],"originalFailure":{"path":str(original_path),"sha256":sha(original_path),"failure":original.get("failure")},"finalEscSaveVerified":False}
    try:
        host,guest=(driver.command(role,"observe") for role in ("host","guest"));server=driver.command("server","observe");card=original.get("genuineCartridge",{})
        source=next((d for d in server["shared"]["devices"] if d.get("position")==((14<<38)|(20<<12)|90)),{})
        if original.get("runId")!=driver.marker["runId"] or "WinError 5" not in original.get("failure","") or not original.get("pongPaddle") or not original.get("relayAfterInput") or source.get("card",{}).get("instance")!=card.get("instance") or not seat(guest,True) or not seat(host,False) or host["room"]!=guest["room"] or host["epoch"]!=guest["epoch"] or host["workers"] or not guest["workers"] or host["forcedWorkerExits"] or guest["forcedWorkerExits"] or not guest["restoredStateHash"]:
            raise ValueError("Finalization requires exactly the already-running reversed genuine Pong seats")
        evidence["genuineCartridge"]=card
        evidence["handover"]={"newHostRole":"guest","newHostWorker":guest["workers"],"newHostRestoredHash":guest["restoredStateHash"],"oldHostWorkers":host["workers"],"oldHostReceivedVideo":host["shared"]["receivedVideo"],"oldHostReceivedAudio":host["shared"]["receivedAudio"],"epoch":host["epoch"],"mameExactPixelContinuityClaimed":False}
        evidence["reversedRelay"]=matched_batches(driver,"guest","host",True)
        for role in ("host","guest"):
            driver.command(role,"repeat_join")
            driver.wait_for(role,lambda v:v["screen"].endswith("GameScreen"),"normal same-epoch UI reopens existing control seat")
            driver.settle(role)
            evidence["screenshots"].append(driver.capture(role,"shared-arcade-handover-completed-"+role))
        evidence["liveRoleAuditComplete"]=True
        # The user-facing power button is an ordinary supported stop. An earlier
        # leave_request was rejected during synchronization, so final Esc-save is
        # explicitly not claimed; already committed autosaves remain authoritative.
        driver.command("guest","slot",{"slot":8});driver.command("guest","use_block",{"x":14,"z":20})
        driver.wait_for("guest",lambda v:v["screen"].endswith("DeviceScreen"),"actual powered cabinet control panel")
        before=driver.command("guest","observe")
        driver.command("guest","button",{"text":"启动 / 停止"})
        final=driver.wait_for("guest",lambda v:not v["room"] and not v["workers"],"ordinary cabinet power-off closes the one native host",timeout=180)
        driver.wait_for("host",lambda v:not v["room"] and not v["workers"],"cabinet power-off closes the remote control seat",timeout=180)
        if final["normalWorkerExits"]<=before["normalWorkerExits"] or final["forcedWorkerExits"] or final["failedWorkerExits"]:
            raise ValueError("Ordinary cabinet stop did not produce a normal worker exit")
        evidence["normalProductPowerOff"]=True
        evidence["scenarioComplete"]=True
    except Exception as error:
        evidence["failure"]=str(error)
        raise
    finally:
        evidence["qaObservationTimeouts"]=driver.observation_timeouts
        output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding="utf-8")
    return evidence


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run",type=Path,required=True);parser.add_argument("--build",type=Path,required=True)
    args=parser.parse_args()
    print(json.dumps(finish(Driver(args.run,args.build)),ensure_ascii=False,indent=2))
