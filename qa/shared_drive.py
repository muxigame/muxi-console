"""Real one-host/two-controller native QA, using only the already owned Driver channel."""
from __future__ import annotations

import json
import time
import math
from PIL import Image


def seat(value, leader):
    lease = value.get("shared", {}).get("lease", {})
    return value["ready"] and lease.get("leader") is leader and lease.get("port") == (0 if leader else 1)


def matched_batches(driver, host_role, guest_role, audio=True):
    deadline = time.monotonic() + 90
    last = None
    while time.monotonic() < deadline:
        host, guest = (driver.command(role, "observe") for role in (host_role, guest_role))
        if host["room"] != guest["room"] or host["epoch"] != guest["epoch"]:
            continue
        matches = {}
        streams=[("sharedVideoHistory", "PNG")]+([("sharedAudioHistory", "PCM-S16LE")] if audio else [])
        for stream, name in streams:
            common = set(host[stream]) & set(guest[stream])
            equal = [frame for frame in common if host[stream][frame] == guest[stream][frame]]
            if equal:
                frame = max(equal, key=int)
                matches[name] = {"frame": int(frame), "crc": host[stream][frame]}
        if len(matches) == len(streams):
            return {"room": host["room"], "epoch": host["epoch"], "wireBatches": matches,
                    "hostShared": host["shared"], "guestShared": guest["shared"]}
        last = {"host": host["shared"], "guest": guest["shared"]}
    raise TimeoutError("No corresponding real PNG/PCM batch matched: " + json.dumps(last))


def paddle_png(driver, role, name, state):
    """Measure the untouched native GameScreen PNG. Never writes a crop or substitute image."""
    capture=driver.capture(role,name)
    options=(driver.root/role/"options.txt").read_text(encoding="utf-8")
    configured=next(int(row.split(":",1)[1]) for row in options.splitlines() if row.startswith("guiScale:"))
    with Image.open(capture["path"]) as original:
        pixels=original.convert("RGB")
        width,height=pixels.size
        scale=1
        while scale!=configured and width//(scale+1)>=320 and height//(scale+1)>=240:
            scale+=1
        gw,gh=math.ceil(width/scale),math.ceil(height/scale)
        viewport_w=min(gw-24,(gh-46)*4//3);viewport_h=viewport_w*3//4
        picture=state["picture"];factor=min(viewport_w/picture["width"],viewport_h/picture["height"])
        pw,ph=int(picture["width"]*factor),int(picture["height"]*factor)
        left=((gw-viewport_w)//2+(viewport_w-pw)//2)*scale
        top=((gh-viewport_h-18)//2+(viewport_h-ph)//2)*scale
        draw_w,draw_h=pw*scale,ph*scale
        result={"original":capture,"nativePictureBox":[left,top,draw_w,draw_h],"readOnlyOriginalPng":True}
        for side,ratio in (("left",232/1552),("right",1255/1552)):
            x=left+round(draw_w*ratio)
            rows=[]
            for y in range(top,top+draw_h):
                if any(min(pixels.getpixel((column,y)))>140 for column in range(max(left,x-1),min(left+draw_w,x+2))):
                    rows.append(y)
            groups=[]
            for y in rows:
                if not groups or y>groups[-1][-1]+1:
                    groups.append([y])
                else:
                    groups[-1].append(y)
            candidates=[g for g in groups if 6/240*draw_h<=len(g)<=22/240*draw_h]
            if not candidates:
                raise ValueError("Native PNG has no verified Pong "+side+" paddle segment: "+capture["path"])
            selected=max(candidates,key=len)
            result[side]={"firstY":selected[0],"lastY":selected[-1],"center":((selected[0]+selected[-1])/2-top)/draw_h,"sampleX":x}
        return result


def run(driver, platform, resume=False):
    if platform not in ("n64", "arcade"):
        raise ValueError("Shared QA supports the two actual shared hardware platforms")
    output = driver.channel / ("shared-" + platform + ("-resumed-result.json" if resume else "-result.json"))
    if output.exists():
        raise ValueError("Preserve this run's original shared result; use a fresh run")
    evidence = {"runId": driver.marker["runId"], "productSha256": driver.build["product"]["sha256"],
                "platform": platform, "scenarioComplete": False, "visualAcceptance": False,
                "physicalOSInput": False, "screenshots": []}
    host = lambda action, body=None: driver.command("host", action, body)
    guest = lambda action, body=None: driver.command("guest", action, body)
    position = lambda role: driver.command("server", "shared_position", {"role": role, "platform": platform})
    block = {"x": 8 if platform == "n64" else 14, "z": 20}
    try:
        prepared = driver.prepare()
        server = driver.command("server", "observe")
        if not server["shared"]["prepared"]:
            driver.command("server", "shared_prepare")
        observer_before = guest("observe")
        if observer_before["room"] or observer_before["workers"] or observer_before["workerRunning"]:
            raise ValueError("The second real client must begin without an active native session")
        if resume:
            from drive import read_json,sha
            prior=driver.channel/("shared-"+platform+"-result.json")
            original=read_json(prior);card=original.get("genuineCartridge",{})
            device=next((d for d in server["shared"]["devices"] if d.get("position")==((block["x"]<<38)|(20<<12)|90)),{})
            if platform!="n64" or original.get("runId")!=driver.marker["runId"] or original.get("scenarioComplete") or not original.get("failure","").startswith("P2 receives actual pixels and PCM:") or server["rooms"] or server["pendingUploads"] or device.get("card",{}).get("instance")!=card.get("instance") or device.get("card",{}).get("hash")!=card.get("hash") or not device.get("powered") or device.get("ports")!=2:
                raise ValueError("Resume requires this same run's normally closed initial N64 fixture failure and the untouched physical source")
            evidence["priorFailure"]={"path":str(prior),"sha256":sha(prior),"fixtureAudioExpected":False}
            evidence["sourceResumeWithoutAnotherBurn"]=True
        else:
            prior_cards = {card["instance"] for card in host("observe").get("cards", [])}
            driver.command("server", "position", {"player": prepared["clients"]["host"]["playerName"], "x": 8.5, "z": 11.5})
            host("slot", {"slot": 8});host("use_block", {"x": 8})
            driver.wait_for("host", lambda v: v["screen"].endswith("WriterScreen"), "actual writer for shared diagnostic")
            host("writer_drop", {"rom": platform});host("writer_confirm");host("button", {"text": "上传并刻录"})
            burned = driver.wait_for("host", lambda v: len(v.get("cards", [])) == len(prior_cards) + 1, "actual shared-media burn")
            card = next(c for c in burned["cards"] if c["instance"] not in prior_cards)
        if card["platform"] != platform or platform == "n64" and card["hash"] != driver.build["n64DiagnosticRom"]["sha256"]:
            raise ValueError("Actual shared cartridge differs from the original diagnostic")
        evidence["genuineCartridge"] = card
        host("close_screen");position("host");position("guest")
        if not resume:
            host("select_card", {"instance": card["instance"]})
            driver.wait_for("host", lambda v: any(c["instance"] == card["instance"] and c["slot"] <= 8 for c in v["cards"]), "normal inventory selection of genuine cartridge")
            host("use_block", block)
            host("slot", {"slot": 1});host("use_block", block);host("use_block", block)
        host("slot", {"slot": 8});host("use_block", block)
        driver.wait_for("host", lambda v: v["screen"].endswith("DeviceScreen"), "shared physical console panel")
        if not resume:
            host("button", {"text": "开机"})
        host("button", {"text": "连接屏幕"});host("button", {"text": "P1"})
        initial=driver.wait_for("host", lambda v: seat(v, True) and v["workerRunning"] and v["frames"] >= 30, "one actual shared core host",timeout=120)
        guest("slot", {"slot": 8});guest("use_block", block)
        driver.wait_for("guest", lambda v: v["screen"].endswith("DeviceScreen"), "second real controller panel")
        guest("button", {"text": "P2"})
        next_epoch=driver.wait_for("host",lambda v:v["room"]==initial["room"] and v["epoch"]>initial["epoch"],"real checkpoint commits and controller epoch advances",timeout=120)["epoch"]
        driver.wait_for("host", lambda v: v["epoch"]==next_epoch and seat(v, True) and v["workerRunning"] and v["frames"] >= 30, "host restores and resumes the new controller epoch",timeout=120)
        remote = driver.wait_for("guest", lambda v: v["epoch"]==next_epoch and seat(v, False) and v["shared"].get("receivedVideo", 0) >= 4 and (platform=="n64" or v["shared"].get("receivedAudio", 0) >= 4), "P2 receives actual source pixels and available PCM",timeout=90)
        if remote["downloadsGranted"] != observer_before["downloadsGranted"] or remote["workers"] or remote["workerRunning"] or remote["cachedRomFiles"] != observer_before["cachedRomFiles"]:
            raise ValueError("Remote controller downloaded media or opened a second native worker")
        guest("probe", {"hash": card["hash"], "room": remote["room"], "epoch": remote["epoch"]})
        denied = driver.wait_for("guest", lambda v: v["probe"] == "denied", "actual P2 room authorization still rejects ROM read")
        if denied["downloadsGranted"] != observer_before["downloadsGranted"]:
            raise ValueError("Explicit remote-seat ROM probe was granted")
        states = driver.command("server", "observe")["states"]
        state = next(row for row in states if row["card"] == card["instance"])
        guest("state_probe", {"generation": state["generation"]})
        driver.wait_for("guest", lambda v: v["stateProbe"] == "denied", "actual P2 generation read rejected")
        evidence["remoteSeatNoRomOrWorker"] = {"downloadsGrantedBefore": observer_before["downloadsGranted"], "downloadsGrantedAfter": denied["downloadsGranted"], "romProbe": "denied", "stateProbe": "denied", "workers": remote["workers"]}
        evidence["relayBeforeInput"] = matched_batches(driver, "host", "guest",platform!="n64")
        if platform=="n64":
            evidence["silentOriginalN64Diagnostic"]={"sourceSentAudio":host("observe")["shared"]["sentAudio"],"guestReceivedAudio":remote["shared"]["receivedAudio"],"audioAcceptance":False}
        baseline = host("observe")
        if platform == "n64":
            driver.wait_for("host", lambda v: v["picture"].get("lower", [0, 0, 0])[2] > 180, "native N64 P2 area is blue before its input")
            guest("input_source", {"buttons": 1})
            changed = driver.wait_for("host", lambda v: v["picture"].get("lower", [0, 0, 0])[0] > 180 and v["picture"]["lower"][1] > 180 and v["picture"]["lower"][2] < 40, "real P2 input changes host N64 lower half to yellow")
            driver.wait_for("guest", lambda v: v["picture"].get("lower", [0, 0, 0])[0] > 180 and v["picture"]["lower"][1] > 180 and v["picture"]["lower"][2] < 40, "guest receives the actual yellow P2 area")
        else:
            # The default Pong machine needs a real coin before there are any
            # paddles. Before that x470/x1237 are score glyphs, not controls.
            frame=host("observe")["frames"];host("input_source",{"buttons":4})
            driver.wait_for("host",lambda v:v["frames"]>=frame+6,"P1 Select reaches actual Pong coin input")
            host("input_source",{"buttons":0});frame=host("observe")["frames"]
            driver.wait_for("host",lambda v:v["frames"]>=frame+123,"Pong match starts after the coin")
            before_paddles={role:paddle_png(driver,role,"shared-arcade-before-"+role,driver.command(role,"observe")) for role in ("host","guest")}
            frame=host("observe")["frames"];guest("input_source", {"buttons": 0, "leftX": 24000})
            driver.wait_for("host",lambda v:v["frames"]>=frame+45,"P2 positive axis reaches actual Pong paddle")
            low={role:paddle_png(driver,role,"shared-arcade-p2-up-"+role,driver.command(role,"observe")) for role in ("host","guest")}
            frame=host("observe")["frames"];guest("input_source", {"buttons": 0, "leftX": -24000})
            driver.wait_for("host",lambda v:v["frames"]>=frame+45,"P2 negative axis reaches actual Pong paddle")
            high={role:paddle_png(driver,role,"shared-arcade-p2-down-"+role,driver.command(role,"observe")) for role in ("host","guest")}
            for role in ("host","guest"):
                if low[role]["right"]["center"]>=.3 or high[role]["right"]["center"]<=.65 or high[role]["right"]["center"]-low[role]["right"]["center"]<=60/240 or abs(low[role]["left"]["center"]-before_paddles[role]["left"]["center"])>3/240 or abs(high[role]["left"]["center"]-before_paddles[role]["left"]["center"])>3/240:
                    raise ValueError("Native "+role+" Pong images do not prove isolated P2 right-paddle movement")
            changed=host("observe")
            evidence["pongPaddle"] = {"before": before_paddles, "up": low,"down":high,"officialRawSampleColumns":[232,1255]}
        evidence["p2Input"] = {"beforeNativeCrc": baseline["crc"], "afterNativeCrc": changed["crc"], "actualHostPicture": changed["picture"]}
        evidence["relayAfterInput"] = matched_batches(driver, "host", "guest",platform!="n64")
        for role in ("host", "guest"):
            evidence["screenshots"].append(driver.capture(role, "shared-" + platform + "-" + role))
        if platform=="n64" and driver.command("server","observe")["browser"].get("faceCreated"):
            display=driver.command("server","observe")["browser"]
            target={"position":display["position"],"side":display["side"]}
            driver.command("server","position",{"player":prepared["clients"]["host"]["playerName"],"x":12,"z":14.5})
            host("browser_select",target)
            driver.wait_for("server",lambda v:v["browser"]["bindings"]==1,"real N64 source binds existing WebDisplays face")
            driver.wait_for("host",lambda v:v["browser"]["readyBrowsers"]==1 and v["browser"]["receivedFrames"]>0,"N64 native video reaches actual browser")
            evidence["n64BrowserGpu"]=driver.gpu_sample("host",target)
            driver.command("server","browser_camera",{"role":"host"});host("review_view",{"held":False});driver.settle("host")
            evidence["screenshots"].append(driver.capture("host","shared-n64-browser-host"))
            driver.command("server","position",{"player":prepared["clients"]["host"]["playerName"],"x":12,"z":14.5})
            host("browser_select",target)
            driver.wait_for("server",lambda v:v["browser"]["bindings"]==0,"normal N64 source browser unbind")
            position("host")
        for role in ("host","guest"):
            other="guest" if role=="host" else "host"
            # The other player's physical position remains inside this source's
            # eight-block control radius, outside the camera view.
            driver.command("server","shared_position",{"role":other,"platform":"arcade" if platform=="n64" else "n64"})
            driver.command("server","shared_camera",{"role":role,"platform":platform})
            driver.command(role,"review_view",{"held":False});driver.settle(role)
            evidence["screenshots"].append(driver.capture(role,"shared-"+platform+"-world-"+role))
        position("host");position("guest")
        guest("input_source", {"buttons": 0})
        before = host("observe");host("leave")
        closed = driver.wait_for("host", lambda v: not v["workers"] and not v["room"], "shared host normal save and native exit",timeout=180)
        driver.wait_for("guest", lambda v: not v["room"] and not v["workers"], "old remote control epoch ends")
        if closed["normalWorkerExits"] <= before["normalWorkerExits"] or closed["forcedWorkerExits"] or closed["failedWorkerExits"]:
            raise ValueError("The shared source did not close normally")
        saved_hash = closed["savedStateHash"]
        guest("slot", {"slot": 8});guest("use_block", block)
        driver.wait_for("guest", lambda v: v["screen"].endswith("DeviceScreen"), "remote player opens physical source after host leave")
        guest("button", {"text": "P1"})
        successor = driver.wait_for("guest", lambda v: seat(v, True) and v["workerRunning"] and v["frames"] >= 30 and v["restoredStates"] > 0, "previous remote seat becomes the one new native host",timeout=120)
        if successor["restoredStateHash"] != saved_hash or successor["downloadsGranted"] <= observer_before["downloadsGranted"]:
            raise ValueError("New host did not restore the actual final checkpoint and newly authorized media")
        old = host("observe");host("slot", {"slot": 8});host("use_block", block)
        driver.wait_for("host", lambda v: v["screen"].endswith("DeviceScreen"), "old host returns through normal panel")
        host("button", {"text": "P2"})
        swapped = driver.wait_for("host", lambda v: seat(v, False) and v["shared"].get("receivedVideo", 0) > 0 and not v["workers"], "old host is now only the remote P2 seat",timeout=150)
        if swapped["downloadsGranted"] != old["downloadsGranted"] or swapped["workerRunning"]:
            raise ValueError("Old host unexpectedly executed another shared worker")
        evidence["roleHandover"] = {"finalSavedHash": saved_hash, "newHostRestoredHash": successor["restoredStateHash"], "newHostWorkers": successor["workers"], "oldHostWorkers": swapped["workers"], "mameExactPixelContinuityClaimed": False}
        evidence["reversedRelay"] = matched_batches(driver, "guest", "host",platform!="n64")
        host("leave")
        driver.wait_for("host", lambda v: not v["room"] and not v["workers"], "remote old host leaves normally")
        driver.wait_for("guest", lambda v: seat(v, True) and v["workerRunning"], "successor survives new epoch after remote leave",timeout=120)
        guest("leave")
        final = driver.wait_for("guest", lambda v: not v["room"] and not v["workers"], "successor native closes normally")
        if final["forcedWorkerExits"] or final["failedWorkerExits"]:
            raise ValueError("Successor has a forced or failed worker close")
        evidence["scenarioComplete"] = True
    except Exception as error:
        evidence["failure"] = str(error)
        raise
    finally:
        cleanup = []
        for role in ("host", "guest"):
            try:
                value = driver.command(role, "observe")
                if value["room"]:
                    driver.command(role, "input_source", {"buttons": -1});driver.command(role, "leave")
                    driver.wait_for(role, lambda v: not v["room"] and not v["workers"], "normal shared failure cleanup")
            except Exception as error:
                cleanup.append(str(error))
        if cleanup:
            evidence["cleanupErrors"] = cleanup;evidence["scenarioComplete"] = False
        output.write_text(json.dumps(evidence, ensure_ascii=False, indent=2), encoding="utf-8")
    return evidence


def handover(driver):
    """Continue only the normally closed, already proven N64 source's role handover."""
    from drive import read_json,sha
    output=driver.channel/"shared-n64-handover-result.json"
    if output.exists():
        raise ValueError("Preserve existing handover evidence")
    previous_path=driver.channel/"shared-n64-resumed-result.json"
    previous=read_json(previous_path)
    evidence={"runId":driver.marker["runId"],"productSha256":driver.build["product"]["sha256"],"platform":"n64","scenarioComplete":False,"physicalOSInput":False,"visualAcceptance":False,"screenshots":[],"previous":{"path":str(previous_path),"sha256":sha(previous_path)}}
    host=lambda action,body=None:driver.command("host",action,body)
    guest=lambda action,body=None:driver.command("guest",action,body)
    block={"x":8,"z":20}
    try:
        a,b=host("observe"),guest("observe");server=driver.command("server","observe");card=previous.get("genuineCartridge",{})
        device=next((d for d in server["shared"]["devices"] if d.get("position")==((8<<38)|(20<<12)|90)),{})
        if previous.get("runId")!=driver.marker["runId"] or previous.get("scenarioComplete") or not previous.get("p2Input") or not previous.get("relayAfterInput") or not previous.get("remoteSeatNoRomOrWorker") or a["room"] or b["room"] or a["workers"] or b["workers"] or server["rooms"] or server["pendingUploads"] or device.get("card",{}).get("instance")!=card.get("instance") or not a["savedStateHash"] or a["forcedWorkerExits"] or a["failedWorkerExits"]:
            raise ValueError("Handover continuation requires exact proven source, persisted checkpoint and normal cleanup")
        saved=a["savedStateHash"]
        evidence["genuineCartridge"]=card;evidence["finalPriorHostCheckpoint"]=saved
        for role in ("host","guest"):
            driver.command("server","shared_position",{"role":role,"platform":"n64"})
        guest("slot",{"slot":8});guest("use_block",block)
        driver.wait_for("guest",lambda v:v["screen"].endswith("DeviceScreen"),"actual successor opens N64 source")
        guest("button",{"text":"P1"})
        successor=driver.wait_for("guest",lambda v:seat(v,True) and v["workerRunning"] and v["frames"]>=30 and v["restoredStates"]>0,"previous remote seat restores as new N64 host",timeout=180)
        if successor["restoredStateHash"]!=saved or successor["downloadsGranted"]<=b["downloadsGranted"]:
            raise ValueError("Successor failed actual final checkpoint/media authorization")
        old=host("observe");host("slot",{"slot":8});host("use_block",block)
        driver.wait_for("host",lambda v:v["screen"].endswith("DeviceScreen"),"old host opens actual N64 source")
        host("button",{"text":"P2"})
        next_epoch=driver.wait_for("guest",lambda v:v["epoch"]>successor["epoch"] and v["room"]==successor["room"],"successor checkpoint advances role epoch",timeout=180)["epoch"]
        driver.wait_for("guest",lambda v:v["epoch"]==next_epoch and seat(v,True) and v["workerRunning"] and v["frames"]>=30,"successor resumes after old host P2 joins",timeout=180)
        remote=driver.wait_for("host",lambda v:seat(v,False) and v["frames"]>0 and v["shared"].get("receivedVideo",0)>0 and not v["workers"],"old host is now only pixel receiver and P2 controller",timeout=120)
        if remote["downloadsGranted"]!=old["downloadsGranted"] or remote["workerRunning"]:
            raise ValueError("Old host executed or downloaded again after becoming a shared remote seat")
        evidence["handover"]={"newHostRestoredHash":successor["restoredStateHash"],"newHostWorkers":successor["workers"],"oldHostWorkers":remote["workers"],"oldHostDownloadIncrease":remote["downloadsGranted"]-old["downloadsGranted"],"epoch":next_epoch}
        evidence["relay"]=matched_batches(driver,"guest","host",False)
        for role in ("host","guest"):
            evidence["screenshots"].append(driver.capture(role,"shared-n64-handover-"+role))
        # The remote leaves first, then wait for the host's next real epoch before
        # asking it to save and leave. Each wire observation remains <=60 seconds.
        old_epoch=guest("observe")["epoch"];host("leave")
        driver.wait_for("host",lambda v:not v["room"] and not v["workers"],"old host remote seat normally leaves",timeout=180)
        driver.wait_for("guest",lambda v:v["epoch"]>old_epoch and seat(v,True) and v["workerRunning"] and v["frames"]>=30,"new host resumes after remote departure",timeout=180)
        before=guest("observe");guest("leave")
        final=driver.wait_for("guest",lambda v:not v["room"] and not v["workers"],"successor final native normal close",timeout=180)
        if final["normalWorkerExits"]<=before["normalWorkerExits"] or final["forcedWorkerExits"] or final["failedWorkerExits"]:
            raise ValueError("Successor did not record a normal final worker exit")
        evidence["scenarioComplete"]=True
    except Exception as error:
        evidence["failure"]=str(error)
        raise
    finally:
        errors=[]
        for role in ("host","guest"):
            try:
                current=driver.command(role,"observe")
                if current["room"]:
                    driver.command(role,"leave");driver.wait_for(role,lambda v:not v["room"] and not v["workers"],"normal N64 handover cleanup",timeout=180)
            except Exception as error:
                errors.append(str(error))
        if errors:
            evidence["cleanupErrors"]=errors;evidence["scenarioComplete"]=False
        evidence["qaObservationTimeouts"]=driver.observation_timeouts
        output.write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding="utf-8")
    return evidence
