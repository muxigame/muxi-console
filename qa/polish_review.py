"""Current-candidate native hardware, two fullscreen modes, vanilla controls and real 2x2 monitor tests."""
from __future__ import annotations
import argparse
import json
import math
import time
from pathlib import Path
from drive import Driver,read_json,sha


def record(d,name):
    path=d.channel/(name+"-result.json")
    if path.exists():raise ValueError("Preserve the original native result; use a new explicit scope")
    return path,{"runId":d.marker["runId"],"productSha256":d.build["product"]["sha256"],"scenarioComplete":False,"physicalOSInput":False,"visualAcceptance":False,"checks":[],"screenshots":[]}


def check(out,value,label,evidence=None):
    out["checks"].append({"label":label,"passed":bool(value),"evidence":evidence})
    if not value:raise AssertionError(label)


def reference(d,out,name):
    p=d.channel/(name+"-result.json");v=read_json(p)
    if v["runId"]!=d.marker["runId"]or v["productSha256"]!=d.build["product"]["sha256"]or not v["scenarioComplete"]:raise ValueError("Current exact-candidate previous stage is required")
    out[name]={"path":str(p),"sha256":sha(p)};return v


def models(d,pause=False):
    path,out=record(d,"polish-model");plan=d.build["modelReviewPlan"]
    if sha(Path(plan["path"]))!=plan["sha256"]:raise ValueError("Pinned model plan changed")
    stations=read_json(plan["path"])["stations"];out["planSha256"]=plan["sha256"]
    def capture(s,held=False):
        d.command("server","model_camera",{"cmd":s["cmd"],"held":held,"hand":"main"});d.command("host","slot",{"slot":7 if held else 8});d.command("host","review_view",{"held":held})
        if held:d.wait_for("host",lambda v:v["heldModel"]==s["cmd"]and not v["polish"]["hint"].get("visible",False),"new empty shell with completed transient hint")
        d.settle("host",12);row=d.capture("host","polish-"+("held-"if held else"body-")+str(s["cmd"]).zfill(2)+"-"+s["platform"]);row.update(cmd=s["cmd"],platform=s["platform"],held=held)
        if held:row["render"]=d.command("host","observe")["heldReview"]["render"]["main"]
        out["screenshots"].append(row);return row
    try:
        previous=reference(d,out,"polish-screen");out["fixture"]=previous["modelFixture"];d.command("server","dock_mode",{"mode":"creative"});d.command("host","hand_arm",{"arm":"RIGHT"})
        nes=next(s for s in stations if s["cmd"]==1);gb=next(s for s in stations if s["cmd"]==3)
        first=[capture(nes),capture(gb,True)]
        if pause:print(json.dumps({"firstNewBodies":first},ensure_ascii=False),flush=True);input("Review the two untouched current-candidate originals before continuing: ")
        for s in stations:
            if s["cmd"]!=1:capture(s)
        for s in stations:
            if s["form_factor"]in("handheld","viewer")and s["cmd"]!=3:capture(s,True)
        check(out,len(out["screenshots"])==32 and len({r["cmd"]for r in out["screenshots"]if not r["held"]})==22,"all twenty-two new bodies and ten current portable silhouettes captured")
        d.command("server","model_camera",{"cmd":3,"held":True,"hand":"main"});out["playFixtureSlots"]={"gb":7,"gba":2,"gbaCustomTag":"original-gba-shell"}
        out["scenarioComplete"]=True
    except Exception as e:out["failure"]=str(e);raise
    finally:out["observationTimeouts"]=d.observation_timeouts;path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding="utf-8")
    return out


def screens(d,pause=False):
    path,out=record(d,"polish-screen");host=lambda a,b=None:d.command("host",a,b);server=lambda a,b=None:d.command("server",a,b)
    def camera(x,z,yaw=180,pitch=25,y=90):server("dock_camera",dict(x=x,y=y,z=z,yaw=yaw,pitch=pitch));d.settle("host",5)
    def choose_screen():
        v=host("observe");slot=next(x["slot"]for x in v["inventory"]if x["item"]=="muxi_console:screen"and x["slot"]<=8);host("slot",{"slot":slot})
    def own(v,origin="16, 90, 18"):return [c for c in v["dock"]["cells"]if c["origin"]==origin]
    def capture(name):d.settle("host",8);out["screenshots"].append(d.capture("host",name))
    try:
        d.prepare();out["modelFixture"]=server("model_prepare");out["fixture"]=server("pixel_live_prepare");host("dock_view",{"hideGui":False});server("dock_mode",{"mode":"survival"});d.wait_for("host",lambda v:v["gameMode"]=="survival","real survival monitor placement")
        baseline=server("observe")["dock"]["screenInventory"];camera(16.5,21);choose_screen();server("dock_obstacle",{"present":True});host("dock_place",dict(x=16,y=89,z=18));d.settle("host",8)
        blocked=server("observe");check(out,not own(blocked)and blocked["dock"]["screenInventory"]==baseline and blocked["dock"]["screenDrops"]==0,"blocked 2x2 footprint consumes no item and leaves no partial tiles",blocked["dock"]);server("dock_obstacle",{"present":False})
        cases=[("south",180,16.5,21,(17,91,18)),("north",0,16.5,15,(15,91,18)),("east",90,19.5,18.5,(16,91,17)),("west",270,13.5,18.5,(16,91,19))]
        expected={"south":[(16+i%2,90+i//2,18)for i in range(4)],"north":[(16-i%2,90+i//2,18)for i in range(4)],"east":[(16,90+i//2,18-i%2)for i in range(4)],"west":[(16,90+i//2,18+i%2)for i in range(4)]}
        out["orientations"]=[]
        photo_cameras={"south":(17,23,180),"north":(16,14,0),"east":(21,18,90),"west":(12,19,270)}
        for facing,yaw,x,z,broken in cases:
            camera(x,z,yaw);choose_screen();before=server("observe")["dock"]["screenInventory"];host("dock_place",dict(x=16,y=89,z=18));placed=d.wait_for("server",lambda v:len(own(v))==4,"four actual "+facing+" monitor cells");rows=sorted(own(placed),key=lambda r:r["part"])
            check(out,[(r["x"],r["y"],r["z"])for r in rows]==expected[facing]and[r["part"]for r in rows]==[7,8,9,10]and all(r["facing"]==facing for r in rows),"2x2x1 block footprint and part order in "+facing,rows)
            check(out,len({r["identity"]for r in rows})==1 and all(0<r["sizeX"]<=1 and 0<r["sizeY"]<=1 and 0<r["sizeZ"]<=1 for r in rows)and placed["dock"]["screenInventory"]==before-1,"one monitor identity, bounded collision and one consumed item in "+facing)
            px,pz,pyaw=photo_cameras[facing];camera(px,pz,pyaw,8);capture("polish-monitor-"+facing)
            if facing=="south"and pause:
                print(json.dumps({"firstNewMonitor":out["screenshots"][-1]},ensure_ascii=False),flush=True);input("Review the untouched computer-monitor original before the remaining screen checks: ")
            camera(x,z,yaw);host("slot",{"slot":6});host("dock_break",dict(zip(("x","y","z"),broken)));removed=d.wait_for("server",lambda v:not own(v),"ordinary non-anchor break removes all four "+facing+" tiles")
            check(out,removed["dock"]["screenInventory"]+removed["dock"]["screenDrops"]==before,"non-anchor break yields exactly one monitor in "+facing)
            camera(broken[0]+.5,broken[2]+.5,yaw);d.wait_for("server",lambda v:v["dock"]["screenInventory"]==before and v["dock"]["screenDrops"]==0,"normal single monitor pickup");out["orientations"].append({"facing":facing,"parts":rows})
        camera(16.5,21);legacy=server("polish_legacy_prepare",{"facing":"SOUTH"});out["legacyFixture"]=legacy
        migrated=d.wait_for("server",lambda v:sum(not c["air"]for c in v["polish"]["legacyCells"])==4 and[c.get("part")for c in v["polish"]["legacyCells"]if not c["air"]]==[7,8,9,10],"real scheduled legacy six-to-four migration")
        p=migrated["polish"];check(out,p["sentinelPreserved"]and all(c["air"]for c in p["legacyCells"]if c["oldIndex"]in(2,5))and all(c["identity"]==legacy["legacyIdentity"]and c["origin"]=="16, 90, 18"for c in p["legacyCells"]if not c["air"])and p["legacyCells"][0]["source"]=="10, 90, 8"and migrated["dock"]["screenDrops"]==0,"legacy layout preserves identity/source/anchor/neighbour and removes only old excess without drops",p)
        camera(17,23,180,8);capture("polish-monitor-legacy-migrated");camera(16.5,21);host("slot",{"slot":6});host("dock_break",dict(x=17,y=91,z=18));d.wait_for("server",lambda v:not own(v),"normal migrated monitor break");camera(17.5,18.5);d.wait_for("server",lambda v:v["dock"]["screenDrops"]==0,"normal legacy monitor item pickup")
        single=server("polish_single_prepare");out["singleFixture"]=single;d.settle("host",110);retained=server("observe")["polish"]
        check(out,retained["singleObstaclePresent"]and retained["singleCells"][0].get("part")==0 and retained["singleCells"][0]["identity"]==single["singleIdentity"],"blocked legacy single monitor remains intact through scheduled retry",retained)
        server("polish_single_clear_obstacle");upgraded=d.wait_for("server",lambda v:all(c.get("part")==7+c["index"]for c in v["polish"]["singleCells"]),"unblocked old single monitor actually expands to four new cells",timeout=60)["polish"]
        check(out,all(c["identity"]==single["singleIdentity"]and c["origin"]=="7, 90, 18"for c in upgraded["singleCells"])and upgraded["singleCells"][0]["source"]=="10, 90, 8","old single upgrade preserves original identity and link",upgraded)
        camera(8,23,180,8);capture("polish-monitor-single-upgraded");camera(7.5,21);host("slot",{"slot":6});host("dock_break",dict(x=8,y=91,z=18));d.wait_for("server",lambda v:not any(c["origin"]=="7, 90, 18"for c in v["dock"]["cells"]),"normal migrated single monitor removal");camera(8.5,18.5);d.wait_for("server",lambda v:v["dock"]["screenDrops"]==0,"normal migrated single pickup")
        camera(16.5,13.5);choose_screen();host("dock_place",dict(x=16,y=89,z=10));d.wait_for("server",lambda v:len(own(v,"16, 90, 10"))==4,"final actual computer monitor")
        camera(17,15,180,8);capture("polish-monitor-front");camera(22,10.5,90,8);capture("polish-monitor-side");camera(17,6,0,8);capture("polish-monitor-back")
        camera(12.5,9.5);host("slot",{"slot":5});host("dock_place",dict(x=12,y=89,z=6));d.wait_for("server",lambda v:"device"in v["dock"],"ordinary dock placement")
        host("slot",{"slot":8});host("polish_empty_slot8");d.wait_for("host",lambda v:not any(i["slot"]==8 for i in v["inventory"]),"normal quick-move keeps a truly empty writer hand")
        out["emptyPlayHardware"]=server("hardware_prepare")
        out["scenarioComplete"]=True
    except Exception as e:out["failure"]=str(e);raise
    finally:out["observationTimeouts"]=d.observation_timeouts;path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding="utf-8")
    return out


def play(d,pause=False,resume=False):
    path,out=record(d,"polish-play-continuation"if resume else"polish-play");reference(d,out,"polish-model");reference(d,out,"polish-screen");host=lambda a,b=None:d.command("host",a,b);server=lambda a,b=None:d.command("server",a,b)
    original=None
    if resume:
        source=d.channel/"polish-play-result.json";original=read_json(source)
        if original["runId"]!=d.marker["runId"]or original["productSha256"]!=d.build["product"]["sha256"]or original["scenarioComplete"]or original.get("failure")!="EOF when reading a line"or original.get("cleanupFailure")or original.get("finalWorkers")!={"host":[],"guest":[]}or len(original["checks"])!=7 or not all(c["passed"]for c in original["checks"])or len(original["screenshots"])!=5:raise ValueError("Only the preserved first-MODEL EOF scope may be continued")
        out["polish-play"]={"path":str(source),"sha256":sha(source)};out["scope"]="Original seven passed checks and five originals remain in the false EOF result; continue the same saved GB, then GBA and dock without replaying burn/settings."
    def mask(v):return v["heldReview"]["input"]["input"]["buttons"]
    def key(code,pressed=True):return host("polish_key",{"key":code,"pressed":pressed})
    def click(button=1):host("polish_mouse",{"button":button});host("polish_mouse",{"button":button,"pressed":False})
    def camera(x=10.5,z=11.5,yaw=180,pitch=12):server("dock_camera",dict(x=x,y=90,z=z,yaw=yaw,pitch=pitch));d.settle("host",8)
    def present(v):return v["polish"].get("presentation",{})
    def snapshot(mode):return d.wait_for("host",lambda v:present(v).get("mode")==mode and present(v).get("sequence",0)>0,"actual rendered "+mode+" fullscreen")
    def capture(name,platform,mode=None):
        d.settle("host",6);v=host("observe")
        if mode:check(out,present(v)["mode"]==mode and present(v)["widgetCount"]==0 and not v["polish"]["visibleTopLevelButtons"],"uncluttered actual "+name,present(v))
        row=d.capture("host",("resumed-"if resume else"")+name);row.update(platform=platform,presentation=present(v),input=v["heldReview"]["input"],feedbackAudit=v["polish"]["feedbackAudit"],nativeFrame=v["frames"],nativeCrc=v["crc"]);out["screenshots"].append(row);return v
    def normal_world():
        for _ in range(8):
            v=host("observe")
            if not v["screen"]:return v
            host("close_screen")
        raise ValueError("Native options did not return to the world normally")
    def fullscreen(slot,platform):
        if host("observe")["screen"]:raise ValueError("Right click starts in actual world view")
        host("pixel_handheld_use",{"slot":slot,"load":False});v=snapshot("model");host("polish_callbacks",{"platform":platform});return v
    def bind(bit,code):
        body={"platform":"gb","bit":bit};host("polish_native_scroll",body);d.settle("host",6);host("polish_native_select",body);key(code);key(code,False)
    def native_options():
        host("polish_pause");host("polish_button_translation",{"key":"menu.options"});host("polish_button_translation",{"key":"options.controls"});d.wait_for("host",lambda v:v["screen"]=="net.minecraft.client.gui.screens.options.controls.ControlsScreen","vanilla Controls page")
    def pure_geometry(v):
        p=present(v);x,y,w,h=p["videoRect"];sw,sh=p["surfaceWidth"],p["surfaceHeight"];ratio=v["width"]/v["height"]
        return abs(w/h-ratio)<1/max(h,1) and max(w/sw,h/sh)>.94 and 0<=x<sw and 0<=y<sh and x+w<=sw+1 and y+h<=sh+1
    try:
        out["cards"]=dict(original["cards"])if resume else{};server("dock_mode",{"mode":"survival"})
        # The hint is sampled from its real GUI draw after an ordinary hotbar selection, then allowed to expire.
        if not resume:
            host("slot",{"slot":8});host("slot",{"slot":7});host("dock_view",{"hideGui":False})
            hinted=d.wait_for("host",lambda v:v["polish"]["hint"].get("visible")is True,"actual transient handheld selection hint")
            hint=hinted["polish"]["hint"];check(out,hint["text"]=="右键切换全屏模式，Esc退出全屏"and hint["bounds"][1]+hint["bounds"][3]<hint["surfaceHeight"]-22,"exact short hint is above the hotbar",hint);capture("polish-hint-visible","gb")
            d.settle("host",75);expired=d.wait_for("host",lambda v:v["polish"]["hint"].get("ticks")==0 and not v["polish"]["hint"].get("visible"),"hint expires rather than becoming a permanent footer");capture("polish-hint-expired","gb")
        for platform,slot,cmd in(("gb",7,3),("gba",2,5)):
            if resume and platform=="gb":
                card=out["cards"]["gb"];before=host("observe");state=next(s for s in server("observe")["dock"]["states"]if s["card"]==card["instance"])
                check(out,not before["room"]and not before["workers"]and state["hash"]==before["savedStateHash"],"EOF cleanup checkpoint belongs to the original GB and has no live worker",state);out["sourceCheckpoint"]=state
                host("slot",{"slot":8});d.settle("host",3);host("slot",{"slot":slot})
                running=d.wait_for("host",lambda v:v["ready"]and v["room"]=="handheld/"+card["instance"]and v["restoredStateHash"]==state["hash"],"same original GB actually restores its cleanup checkpoint")
                check(out,running["workerRunning"]and not running["screen"]and mask(running)==0,"same-card native restored GB runs neutrally while held",{"card":card,"restoredStateHash":running["restoredStateHash"]})
            else:
                host("slot",{"slot":8});host("polish_empty_slot8");camera(8.5,11.5,180,18);host("use_block",{"x":8});d.wait_for("host",lambda v:v["screen"].endswith("WriterScreen"),"normal writer")
                host("writer_drop",{"rom":platform});host("writer_confirm");host("button",{"text":"上传并刻录"});v=d.wait_for("host",lambda v:any(c["platform"]==platform for c in v["cards"]),"actual rights-confirmed original "+platform+" burn")
                card=next(c for c in v["cards"]if c["platform"]==platform);out["cards"][platform]=card;check(out,card["hash"]==d.build["gbDiagnosticRom"if platform=="gb"else"gbaDiagnosticRom"]["sha256"],"first-party diagnostic identity for "+platform,card)
                host("close_screen");host("pixel_handheld_use",{"slot":slot,"load":True});running=d.wait_for("host",lambda v:v["ready"]and v["workerRunning"]and v["room"]=="handheld/"+card["instance"]and v["frames"]>=(420 if platform=="gb"else 30),"real loaded "+platform+" naturally runs while held")
                check(out,not running["screen"]and mask(running)==0,"ordinary holding remains input-neutral without fullscreen")
            camera();same={k:running[k]for k in("room","epoch","workers")}
            if platform=="gb"and not resume:
                native_options();capture("polish-vanilla-controls","gb");host("polish_button_translation",{"key":"controls.keybinds"});d.wait_for("host",lambda v:v["screen"].endswith("KeyBindsScreen"),"actual vanilla key bindings")
                bind(8,70);bind(0,297);host("polish_save_options");host("polish_reload_options");profile=host("polish_native_profile",{"platform":"gb"})["polishCallback"]["bindings"];other=host("polish_native_profile",{"platform":"gba"})["polishCallback"]["bindings"]
                by={r["bit"]:r for r in profile["mappings"]};other_by={r["bit"]:r for r in other["mappings"]}
                check(out,by[8]["key"]==70 and by[0]["key"]==297 and by[8]["binding"]in by[8]["persisted"]and by[0]["binding"]in by[0]["persisted"]and other_by[8]["key"]==74,"native Controls persist F/F8 game bindings, reload options and isolate GBA",{"gb":profile,"gba":other})
                host("polish_native_scroll",{"platform":"gb","bit":8});d.settle("host",6);capture("polish-vanilla-keybinds","gb");normal_world()
            first=fullscreen(slot,platform);check(out,all(first[k]==same[k]for k in same),"right click opens body fullscreen in the same "+platform+" worker")
            check(out,present(first)["modelRendered"]and present(first)["renderedModel"]==cmd,"fullscreen actually draws the correct refined body",present(first))
            if not(resume and platform=="gb"):capture("polish-"+platform+"-model-idle",platform,"model")
            if platform=="gb"and pause:
                print(json.dumps({"firstModelFullscreen":out["screenshots"][-1]},ensure_ascii=False),flush=True);input("Review the untouched fullscreen body/viewport framing before input checks: ")
            a=70 if platform=="gb"else 74;before=host("observe");key(a)
            def complete_cap(v):
                audit=v["polish"].get("feedbackAudit")or{};return audit.get("buttons")==256 and audit.get("recoloredQuads",0)>=6 and audit.get("allVerticesBlue")and audit.get("allUseWhite")and all(audit.get("directions",{}).get(face,0)>=1 for face in("north","south","east","west","up","down"))
            actual=d.wait_for("host",lambda v:mask(v)==256 and complete_cap(v)and v["crc"]!=before["crc"],"real A input recolours all six actual baked cap directions and changes the diagnostic")
            check(out,complete_cap(actual),"all submitted A-cap vertices and six directions use the blue/white material",actual["polish"]["feedbackAudit"]);capture("polish-"+platform+"-buttons-a-pressed",platform,"model");key(a,False);d.wait_for("host",lambda v:mask(v)==0 and v["polish"]["feedbackAudit"].get("recoloredQuads")==0,"whole A cap restores after release")
            codes=(70,297,87,257,344)if platform=="gb"else(74,75,81,69,87,257,344);expected=285 if platform=="gb"else 3357
            before=host("observe")
            for code in codes:key(code)
            pressed=d.wait_for("host",lambda v:mask(v)==expected and present(v).get("buttons")==expected and present(v).get("highlightedSurfaces",0)>=6 and v["crc"]!=before["crc"],"real native bindings recolour complete actual cap quads and affect the diagnostic")
            check(out,all(pressed[k]==same[k]for k in same)and present(pressed)["mode"]=="model","F/F8 are normal game actions and never switch mode or leave",present(pressed));capture("polish-"+platform+"-buttons-pressed",platform,"model")
            for code in codes:key(code,False)
            d.wait_for("host",lambda v:mask(v)==0 and present(v).get("highlightedSurfaces")==0,"buttons release back to their actual original materials");capture("polish-"+platform+"-buttons-released",platform,"model")
            click();pure=snapshot("screen");check(out,all(pure[k]==same[k]for k in same)and pure_geometry(pure),"pure fullscreen maximises one dimension without stretching "+platform,present(pure));capture("polish-"+platform+"-pure-screen",platform,"screen")
            click();model=snapshot("model");check(out,all(model[k]==same[k]for k in same),"right click cycles back to body fullscreen in the same worker")
            a=70 if platform=="gb"else 74;key(a);d.wait_for("host",lambda v:mask(v)==256,"actual held action before Esc");key(256)
            escaped=d.wait_for("host",lambda v:not v["screen"]and mask(v)==0,"Esc returns to ordinary held view and releases keys")
            check(out,escaped["ready"]and all(escaped[k]==same[k]for k in same),"Esc never saves/leaves or restarts the current "+platform+" game");capture("polish-"+platform+"-esc-held",platform)
            moved_before=host("observe");host("hand_world_key",{"name":"forward","pressed":True});walked=d.settle("host",12);host("hand_world_key",{"name":"forward","pressed":False});distance=math.hypot(walked["x"]-moved_before["x"],walked["z"]-moved_before["z"]);check(out,distance>.1 and mask(walked)==0,"ordinary held walking affects only Minecraft",{"distance":distance});camera()
            fullscreen(slot,platform);before=host("observe");callbacks=[key(c)["polishCallback"]for c in(87,81,49)];callbacks.append(host("polish_mouse",{"button":0})["polishCallback"]);blocked=d.settle("host",10);host("polish_release_keys")
            check(out,all(c["consumed"]for c in callbacks)and math.hypot(blocked["x"]-before["x"],blocked["z"]-before["z"])<.02 and before["inventory"]==blocked["inventory"]and before["heldReview"]["selected"]==blocked["heldReview"]["selected"],"fullscreen consumes world movement/drop/hotbar/attack callbacks")
            key(256);d.wait_for("host",lambda v:not v["screen"]and mask(v)==0,"return to world without leaving after isolation")
            if platform=="gb":
                native_options();host("polish_button_translation",{"key":"controls.keybinds"})
                for bit in(8,0):host("polish_native_scroll",{"platform":"gb","bit":bit});d.settle("host",4);host("polish_native_reset",{"platform":"gb","bit":bit})
                host("polish_save_options");normal_world()
            before=host("observe");check(out,before["ready"]and before["workerRunning"],"actual worker alive before normal explicit safe leave")
            host("leave");ended=d.wait_for("host",lambda v:not v["room"]and not v["workers"],"normal explicit safe leave commits checkpoint")
            state=next(s for s in server("observe")["dock"]["states"]if s["card"]==card["instance"]);check(out,state["hash"]==ended["savedStateHash"]and ended["normalWorkerExits"]==before["normalWorkerExits"]+1 and ended["forcedWorkerExits"]==ended["failedWorkerExits"]==0,"normal safe leave preserves "+platform+" checkpoint and closes naturally",state);out[platform+"Save"]=state
        gba=out["cards"]["gba"];camera(14.5,9.7,148,23);host("dock_use_block",dict(x=12,y=90,z=6,face="SOUTH"));installed=d.wait_for("server",lambda v:v["dock"]["dockedHandheld"].get("instance")==gba["instance"],"same complete refined GBA installs normally")["dock"]
        check(out,installed["nbtRoundTrip"]and installed["dockedHandheld"]["model"]==5 and installed["dockedHandheld"]["qaPreserve"]=="original-gba-shell","complete GBA shell/card metadata survives dock installation")
        host("slot",{"slot":8});host("polish_empty_slot8");host("dock_use_block",dict(x=12,y=90,z=6,face="SOUTH"));d.wait_for("host",lambda v:v["screen"].endswith("DeviceScreen"),"normal dock panel")
        host("button",{"text":"连接附近屏幕"});host("button",{"text":"启动 / 停止"});host("button",{"text":"P1 加入"});dock=d.wait_for("host",lambda v:v["ready"]and v["workerRunning"]and v["restoredStateHash"]==out["gbaSave"]["hash"],"same GBA restores through dock and actual computer monitor")
        key(256);d.wait_for("host",lambda v:not v["screen"]and mask(v)==0,"Esc releases dock fullscreen but keeps the session")
        host("dock_view",{"hideGui":True});camera(15,13.7,186,8)
        deadline=time.monotonic()+45
        while time.monotonic()<deadline:
            clock=server("pixel_world")["gameTime"];angle=(clock%7200)*.5%360
            if 170<=angle<=235:break
            d.settle("host",8)
        out["dockCaptureAngle"]=angle;capture("polish-dock-monitor-active","gba")
        camera(14.5,9.7,148,23);d.settle("host",24);capture("polish-dock-rotated","gba");before=host("observe");check(out,before["ready"]and before["workers"]==dock["workers"],"same actual dock worker before final normal leave")
        host("leave");ended=d.wait_for("host",lambda v:not v["room"]and not v["workers"],"normal dock final checkpoint")
        final=next(s for s in server("observe")["dock"]["states"]if s["card"]==gba["instance"]);check(out,final["hash"]==ended["savedStateHash"]and ended["normalWorkerExits"]==before["normalWorkerExits"]+1 and ended["forcedWorkerExits"]==ended["failedWorkerExits"]==0,"real final dock state matches client and closes naturally",final);out["dockSave"]=final
        host("slot",{"slot":8});host("dock_sneak_block",dict(x=12,y=90,z=6,face="SOUTH"));returned=d.wait_for("server",lambda v:v["dock"]["dockedHandheld"]["count"]==0 and sum(c["instance"]==gba["instance"]for c in v["dock"]["instances"])==1,"normal unique complete GBA retrieval")["dock"];out["returned"]=returned["instances"];out["scenarioComplete"]=True
    except Exception as e:out["failure"]=str(e);raise
    finally:
        try:
            host("polish_release_keys");host("hand_arm",{"arm":"RIGHT"})
            if host("observe")["room"]:host("leave");d.wait_for("host",lambda v:not v["room"]and not v["workers"],"normal scoped cleanup without Esc save")
            final_clients={role:d.command(role,"observe")for role in("host","guest")};out["finalWorkers"]={role:v["workers"]for role,v in final_clients.items()}
            out["finalCounters"]={role:{k:v.get(k)for k in("normalWorkerExits","forcedWorkerExits","failedWorkerExits","savedStateHash","qaChannel")}for role,v in final_clients.items()}
        except Exception as e:out["cleanupFailure"]=str(e);out["scenarioComplete"]=False
        out["observationTimeouts"]=d.observation_timeouts;path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding="utf-8")
    return out


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("--run",required=True,type=Path);p.add_argument("--build",required=True,type=Path);p.add_argument("--phase",choices=("models","screens","play","all"),default="models");p.add_argument("--pause-after-first",action="store_true");p.add_argument("--continue-play",action="store_true");a=p.parse_args()
    if a.continue_play and(a.phase!="play"or a.pause_after_first):p.error("Continuation is play-only and never pauses")
    d=Driver(a.run,a.build)
    for phase,fn in(("screens",lambda:screens(d,a.pause_after_first)),("models",lambda:models(d,a.pause_after_first)),("play",lambda:play(d,a.pause_after_first,a.continue_play))):
        if a.phase in(phase,"all"):
            result=fn();print(json.dumps({"phase":phase,"complete":result["scenarioComplete"],"checks":len(result["checks"]),"originals":len(result["screenshots"])}),flush=True)
