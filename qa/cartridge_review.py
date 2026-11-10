"""Hash-bound ordinary inventory, transfer and inserted-media tests; never starts or stops Minecraft."""
from __future__ import annotations
import argparse
import json
import time
from pathlib import Path
from drive import Driver,read_json,sha
from polish_review import record,check,reference


def items(v):return v["cartridge"]["items"]
def at(v,slot):return next((i for i in items(v)if i["slot"]==slot),{"empty":True,"slot":slot})
def cursor(v):return v["cartridge"]["cursor"]
def raw(v,identity):return next(i for i in items(v)if i.get("instance")==identity and i["item"]=="muxi_console:cartridge")
def inserted(v,slot):return at(v,slot).get("inserted",{"empty":True})
def same_card(item,original):return not item.get("empty",False)and item.get("instance")==original["instance"]and item.get("nbt")==original["nbt"]and item.get("count")==1


class Front:
    def __init__(self,d,out,role="host"):self.d=d;self.out=out;self.role=role
    def do(self,action,body=None):return self.d.command(self.role,action,body)
    def see(self):return self.do("observe")
    def wait(self,predicate,label,timeout=60):return self.d.wait_for(self.role,predicate,label,timeout)
    def settle(self,ticks=4):return self.d.settle(self.role,ticks)
    def camera(self,x=10.5,z=11.5,yaw=180,pitch=12):self.d.command("server","dock_camera",dict(role=self.role,x=x,y=90,z=z,yaw=yaw,pitch=pitch));self.settle(4)
    def inv(self):
        v=self.see()
        if v["screen"].endswith("InventoryScreen"):return v
        if v["screen"]:raise ValueError("Only normal world may open inventory")
        self.do("cartridge_inventory");return self.wait(lambda v:v["screen"].endswith("InventoryScreen"),"actual vanilla inventory")
    def click(self,slot,button=0):return self.do("cartridge_click",dict(slot=slot,button=button))
    def close(self):self.do("close_screen")
    def pic(self,name,session=None):
        before=self.see();row=self.d.capture(self.role,name);after=self.see()
        lifecycle=lambda v:{k:v[k]for k in("room","epoch","ready","workerRunning","workers","frames","crc")}
        row.update(role=self.role,before=before["cartridge"],after=after["cartridge"],screen=before["screen"],presentation=before["polish"].get("presentation"),lifecycleBefore=lifecycle(before),lifecycleAfter=lifecycle(after),scope="same-live-worker"if session else"inventory-or-transfer-state")
        self.out["screenshots"].append(row)
        if session:
            check(self.out,all(v["ready"]and v["workerRunning"]and v["frames"]>0 and v["cartridge"]["progress"]["stage"]=="complete"and v["cartridge"]["progress"].get("card")==session["cartridge"]["progress"].get("card")and all(v[k]==session[k]for k in("room","epoch","workers"))for v in(before,after)),"same real worker/card stays active on both sides of "+name,{"before":row["lifecycleBefore"],"after":row["lifecycleAfter"]})
        return row
    def put_cursor(self,slot):
        self.click(slot);return self.wait(lambda v:cursor(v)["empty"],"normal cursor placement")
    def take(self,slot,expected):
        self.click(slot);return self.wait(lambda v:same_card(cursor(v),expected),"actual complete cartridge on cursor")
    def install(self,card,slot):
        source=raw(self.see(),card["instance"])["slot"];self.take(source,card);self.click(slot,1)
        v=self.wait(lambda v:cursor(v)["empty"]and same_card(inserted(v,slot),card),"normal right-click complete-card insertion")
        check(self.out,at(v,slot)["storedSlots"]==1 and at(v,source)["empty"],"one physical card moves into one real container slot",{"slot":slot,"handheld":at(v,slot)});return v
    def eject(self,slot,card,destination):
        self.click(slot,1);self.wait(lambda v:same_card(cursor(v),card)and inserted(v,slot)["empty"],"normal empty-cursor card extraction");return self.put_cursor(destination)
    def leave(self,label):
        before=self.see();check(self.out,before["ready"]and before["workerRunning"],label+" has a real live worker before save")
        self.do("leave");after=self.wait(lambda v:not v["room"]and not v["workers"],label+" normal leave and native close")
        check(self.out,after["normalWorkerExits"]==before["normalWorkerExits"]+1 and after["forcedWorkerExits"]==after["failedWorkerExits"]==0,label+" closes naturally without forced/failed worker");return after


def cleanup(d,out):
    try:
        for role in("host","guest"):
            d.command(role,"cartridge_cancel_pending");d.command(role,"cartridge_shift",{"pressed":False});d.command(role,"polish_release_keys")
            if d.command(role,"observe")["room"]:d.command(role,"leave");d.wait_for(role,lambda v:not v["room"]and not v["workers"],"normal scoped cartridge cleanup")
        out["finalClients"]={r:{k:v[k]for k in("workers","normalWorkerExits","forcedWorkerExits","failedWorkerExits","savedStateHash","downloadsGranted")}for r in("host","guest")for v in[d.command(r,"observe")]}
    except Exception as error:out["cleanupFailure"]=str(error);out["scenarioComplete"]=False


def inventory(d):
    path,out=record(d,"cartridge-inventory");f=Front(d,out)
    try:
        d.prepare();d.command("server","dock_prepare");out["fixture"]=d.command("server","cartridge_prepare");f.do("dock_view",{"hideGui":False});out["cards"]={}
        choices=(("gb","gbDiagnosticRom"),("gba","gbaDiagnosticRom"),("large_gba","largeGbaDiagnosticRom"),("nes","diagnosticRom"),("pce","pceDiagnosticRom"),("legacy_gb","gbDiagnosticRom"))
        for choice,pin in choices:
            existing={i.get("instance")for i in items(f.see())}
            f.camera(8.5,11.5,180,18);f.do("slot",{"slot":8});f.do("use_block",{"x":8});f.wait(lambda v:v["screen"].endswith("WriterScreen"),"ordinary writer for "+choice)
            f.do("writer_drop",{"rom":"gb"if choice=="legacy_gb"else choice});f.do("writer_confirm");f.do("button",{"text":"上传并刻录"})
            v=f.wait(lambda v:any(i.get("hash")==d.build[pin]["sha256"]and i.get("instance")not in existing and i["item"]=="muxi_console:cartridge"for i in items(v)),"rights-confirmed actual "+choice+" burn")
            issued=next(i for i in items(v)if i.get("hash")==d.build[pin]["sha256"]and i.get("instance")not in existing and i["item"]=="muxi_console:cartridge");f.close()
            if choice!="legacy_gb":
                d.command("server","cartridge_sentinel",{"instance":issued["instance"]});v=f.wait(lambda v:"qa_card_sentinel"in raw(v,issued["instance"])["data"],"actual issued card custom-data sentinel synchronises")
            card=raw(v,issued["instance"]);out["cards"][choice]=card
            check(out,card["hash"]==d.build[pin]["sha256"]and card["count"]==1,"issued "+choice+" identity and complete-stack sentinel",card)
        check(out,f.see()["downloadsGranted"]==0,"upload and raw-card possession do not automatically download a ROM")
        gb,small=out["cards"]["gb"],out["cards"]["gba"];legacy=out["cards"]["legacy_gb"];d.command("server","cartridge_legacy",{"instance":legacy["instance"],"slot":2});f.inv();f.wait(lambda v:at(v,2).get("instance")==legacy["instance"]and at(v,2)["storedSlots"]==0,"same issued identity loaded through actual legacy flat fields")
        legacy_slot=legacy["slot"];f.eject(2,legacy,legacy_slot);f.click(2,1);v=f.settle();check(out,cursor(v)["empty"]and sum(i.get("instance")==legacy["instance"]for i in items(v))==1,"legacy flat extraction returns its original UUID once and cannot duplicate on repeat");f.install(legacy,2);f.eject(2,legacy,legacy_slot);f.install(gb,2)
        source=raw(f.see(),small["instance"])["slot"];f.take(source,small);f.click(2,1);v=f.settle()
        check(out,same_card(cursor(v),small)and same_card(inserted(v,2),gb)and at(v,2)["storedSlots"]==1,"occupied slot rejects a second complete card without loss");f.put_cursor(source)
        empty=next(s for s in range(9,36)if at(f.see(),s)["empty"]);f.eject(2,gb,empty)
        f.take(source,small);f.click(2,1);v=f.settle();check(out,same_card(cursor(v),small)and inserted(v,2)["empty"],"incompatible GBA card is rejected by the empty GB shell intact");f.put_cursor(source)
        f.click(3);stone=cursor(f.see())["nbt"];f.click(2,1);v=f.settle();check(out,cursor(v)["nbt"]==stone and inserted(v,2)["empty"],"ordinary non-card stack is rejected without splitting or loss");f.put_cursor(3)
        # Reverse direction: the cursor holds the actual empty handheld and the slot supplies the card.
        gb_slot=raw(f.see(),gb["instance"])["slot"];f.click(7);f.click(gb_slot,1);v=f.wait(lambda v:same_card(cursor(v).get("inserted",{}),gb),"reverse handheld-on-card right click")
        check(out,at(v,gb_slot)["empty"]and cursor(v)["storedSlots"]==1,"reverse insertion consumes exactly the explicit source card");f.put_cursor(7);f.pic("cartridge-inventory-inserted-gb-in-gba")
        f.click(7);destination=next(s for s in range(9,36)if at(f.see(),s)["empty"]);f.click(destination,1);v=f.wait(lambda v:same_card(at(v,destination),gb)and cursor(v).get("inserted",{}).get("empty"),"reverse extraction to the explicit empty inventory slot");check(out,cursor(v)["item"]=="muxi_console:handheld","reverse extraction preserves the whole shell on cursor");f.put_cursor(7)
        f.install(small,7);f.do("cartridge_swap",{"slot":40,"hotbar":7});f.wait(lambda v:same_card(inserted(v,40),small),"normal offhand swap of full shell");d.command("server","cartridge_fill");f.close();f.wait(lambda v:v["ready"]and v["workerRunning"],"ordinary offhand holding can automatically start the small GBA");f.leave("ordinary offhand play before full-inventory extraction");f.do("cartridge_shift",{"pressed":True});f.do("cartridge_use",{"hand":"OFF_HAND"});v=f.wait(lambda v:v["lastCode"]=="slot"and v["lastError"]=="物品栏已满，卡带仍保留在掌机中","actual server full-inventory refusal")
        check(out,same_card(inserted(v,40),small)and len([i for i in items(v)if i["slot"]<36])==36,"full survival inventory rejects shortcut ejection and preserves its complete card")
        check(out,not any(i.get("instance")==small["instance"]for i in d.command("server","observe")["dock"]["drops"]),"full-inventory rejection drops no copy into the world")
        f.inv();d.command("server","cartridge_clear_fill");f.do("cartridge_swap",{"slot":40,"hotbar":7});f.do("cartridge_shift",{"pressed":False});f.eject(7,small,source)
        f.take(source,small);f.put_cursor(40);f.close();f.do("cartridge_shift",{"pressed":True});f.do("slot",{"slot":7});f.do("cartridge_use",{"hand":"MAIN_HAND"});v=f.wait(lambda v:same_card(inserted(v,7),small)and at(v,40)["empty"],"explicit opposite-hand card shortcut insertion")
        check(out,not v["room"]and at(v,7)["storedSlots"]==1,"shift-held explicit two-hand insertion transfers only the offered complete card");f.inv();f.do("cartridge_shift",{"pressed":False});f.eject(7,small,source)
        f.install(gb,2);f.close();f.camera();f.do("slot",{"slot":2});running=f.wait(lambda v:v["ready"]and v["workerRunning"]and v["frames"]>=420,"real GB boot before running/saving menu guard")
        f.inv();destination=next(s for s in range(9,36)if at(f.see(),s)["empty"]);f.do("cartridge_busy_pair",{"slot":2,"destination":destination})
        attempted=f.wait(lambda v:v["cartridge"]["busyAttempt"].get("executed")is True and v["cartridge"]["busyAttempt"].get("serverBusyDenied")is True,"ordinary saving-stage extraction receives the actual server busy denial")
        f.settle(3);v=f.see();check(out,same_card(cursor(v).get("inserted",{}),gb)and at(v,destination)["empty"],"save-in-progress rejects cursor-held shell extraction intact",attempted["cartridge"]["busyAttempt"])
        ended=f.wait(lambda v:not v["room"]and not v["workers"],"stowing to cursor completes the normal checkpoint");check(out,ended["normalWorkerExits"]==running["normalWorkerExits"]+1 and ended["forcedWorkerExits"]==ended["failedWorkerExits"]==0,"actual cursor stow saves and closes naturally")
        f.click(destination,1);f.wait(lambda v:same_card(at(v,destination),gb)and cursor(v).get("inserted",{}).get("empty"),"the same extraction succeeds only after normal save completion");f.put_cursor(2);f.pic("cartridge-inventory-after-safe-extraction");f.close()
        out["worldEvidence"]=d.command("server","cartridge_observe");state=next(i for i in out["worldEvidence"]["disk"]if i["card"]==gb["instance"]);check(out,state["stateSha256"]==ended["savedStateHash"]==state["metadataHash"],"GB checkpoint disk bytes match the real client/vault identity",state);out["scenarioComplete"]=True
    except Exception as error:out["failure"]=str(error);raise
    finally:cleanup(d,out);out["observationTimeouts"]=d.observation_timeouts;path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding="utf-8")
    return out


def inventory_sources(d,out):
    source=d.channel/"cartridge-inventory-result.json";base=read_json(source)
    if base.get("scenarioComplete"):return reference(d,out,"cartridge-inventory")
    continued=reference(d,out,"cartridge-inventory-continuation")
    if continued.get("cartridge-inventory")!={"path":str(source),"sha256":sha(source)}or continued["cards"]!=base["cards"]or base["runId"]!=d.marker["runId"]or base["productSha256"]!=d.build["product"]["sha256"]or base.get("failure")!="full survival inventory rejects shortcut ejection and preserves its complete card"or len(base["checks"])!=17 or not all(c["passed"]for c in base["checks"][:-1])or base["checks"][-1]["passed"]or len(base["screenshots"])!=1 or base.get("cleanupFailure"):raise ValueError("Strict original inventory failure and same-card continuation are required")
    out["cartridge-inventory"]={"path":str(source),"sha256":sha(source)};return continued


def continue_inventory(d,expected):
    path,out=record(d,"cartridge-inventory-continuation");source=d.channel/"cartridge-inventory-result.json";base=read_json(source)
    if sha(source)!=expected or expected!="e9323121930be66d8648de0c59846376d5728427d53c25ba386faf64e4fbb693"or base["runId"]!=d.marker["runId"]or base["productSha256"]!=d.build["product"]["sha256"]or base["scenarioComplete"]or base.get("failure")!="full survival inventory rejects shortcut ejection and preserves its complete card"or len(base["checks"])!=17 or not all(c["passed"]for c in base["checks"][:-1])or base["checks"][-1]["passed"]or len(base["screenshots"])!=1 or base.get("cleanupFailure")or any(v["workers"]for v in base["finalClients"].values()):raise ValueError("Only the exact normally cleaned full-inventory QA assumption failure may continue")
    out["cartridge-inventory"]={"path":str(source),"sha256":expected};out["cards"]=base["cards"];out["scope"]="Keep the original false and its first sixteen passes/one photo; finish full-inventory, explicit offhand shortcut and actual saving guard on the same physical cards."
    f=Front(d,out);gb,small=out["cards"]["gb"],out["cards"]["gba"];source_slot=small["slot"]
    try:
        v=f.see();world=d.command("server","cartridge_observe");state=next(i for i in world["disk"]if i["card"]==small["instance"])
        check(out,not v["room"]and not v["workers"]and cursor(v)["empty"]and same_card(inserted(v,40),small)and len([i for i in items(v)if i["slot"]<36])==36 and state["stateSha256"]==base["finalClients"]["host"]["savedStateHash"],"same original filled inventory, offhand shell and real cleanup checkpoint are intact",state);out["sourceCheckpoint"]=state
        f.do("cartridge_shift",{"pressed":True});f.do("cartridge_use",{"hand":"OFF_HAND"});v=f.wait(lambda v:v["lastCode"]=="slot"and v["lastError"]=="物品栏已满，卡带仍保留在掌机中","real server full-inventory rejection after Shift is set in world")
        check(out,same_card(inserted(v,40),small)and len([i for i in items(v)if i["slot"]<36])==36,"actual full-inventory refusal preserves the complete original card",{"code":v["lastCode"],"message":v["lastError"]});check(out,not any(i.get("instance")==small["instance"]for i in d.command("server","observe")["dock"]["drops"]),"full refusal creates no dropped duplicate")
        f.inv();d.command("server","cartridge_clear_fill");f.do("cartridge_swap",{"slot":40,"hotbar":7});f.do("cartridge_shift",{"pressed":False});f.eject(7,small,source_slot)
        f.take(source_slot,small);f.put_cursor(40);f.close();f.do("cartridge_shift",{"pressed":True});f.do("slot",{"slot":7});f.do("cartridge_use",{"hand":"MAIN_HAND"});v=f.wait(lambda v:same_card(inserted(v,7),small)and at(v,40)["empty"],"actual opposite-hand shortcut installs the explicit complete card")
        check(out,at(v,7)["storedSlots"]==1,"explicit offered offhand card transfers once");f.inv();f.do("cartridge_shift",{"pressed":False});f.eject(7,small,source_slot)
        f.install(gb,2);f.close();f.camera();f.do("slot",{"slot":2});running=f.wait(lambda v:v["ready"]and v["workerRunning"]and v["frames"]>=420,"real GB boots before cursor saving guard")
        f.inv();view=f.see();destination=next(s for s in range(9,36)if at(view,s)["empty"]);f.do("cartridge_busy_pair",{"slot":2,"destination":destination})
        attempted=f.wait(lambda v:v["cartridge"]["busyAttempt"].get("executed")is True and v["cartridge"]["busyAttempt"].get("serverBusyDenied")is True,"saving-stage callback receives the actual server busy rejection");v=f.settle(3)
        check(out,same_card(cursor(v).get("inserted",{}),gb)and at(v,destination)["empty"],"actual busy denial preserves cursor-held shell and original whole GB",attempted["cartridge"]["busyAttempt"])
        ended=f.wait(lambda v:not v["room"]and not v["workers"],"ordinary cursor stow checkpoint finishes");check(out,ended["normalWorkerExits"]==running["normalWorkerExits"]+1 and ended["forcedWorkerExits"]==ended["failedWorkerExits"]==0,"cursor stow closes its real worker naturally")
        f.click(destination,1);f.wait(lambda v:same_card(at(v,destination),gb)and cursor(v).get("inserted",{}).get("empty"),"same extraction succeeds after completed save");f.put_cursor(2);f.pic("cartridge-inventory-resumed-after-safe-extraction");f.close();out["worldEvidence"]=d.command("server","cartridge_observe");state=next(i for i in out["worldEvidence"]["disk"]if i["card"]==gb["instance"])
        check(out,state["stateSha256"]==state["metadataHash"]==ended["savedStateHash"],"actual GB checkpoint disk hash matches client and vault",state);out["scenarioComplete"]=True
    except Exception as error:out["failure"]=str(error);raise
    finally:cleanup(d,out);out["observationTimeouts"]=d.observation_timeouts;path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding="utf-8")
    return out


def cold(d,resume=False):
    path,out=record(d,"cartridge-cold-continuation"if resume else"cartridge-cold");previous=inventory_sources(d,out);out["cards"]=previous["cards"];large=out["cards"]["large_gba"];host=Front(d,out,"host");guest=Front(d,out,"guest")
    if resume:
        source=d.channel/"cartridge-cold-result.json";failed=read_json(source);pickup=reference(d,out,"cartridge-pickup")
        if sha(source)!="8496ae51b2d8bb058d49f51d39ebc56055e2d9135d93f3263a04510747461402"or failed["scenarioComplete"]or not failed.get("failure","").startswith("real dropped card pickup by the second network player:")or len(failed["checks"])!=1 or not failed["checks"][0]["passed"]or failed["screenshots"]or failed["cards"]!=out["cards"]or pickup["cards"]!=out["cards"]or pickup["cartridge-cold"]!={"path":str(source),"sha256":sha(source)}or failed["runId"]!=d.marker["runId"]or failed["productSha256"]!=d.build["product"]["sha256"]or failed.get("cleanupFailure"):raise ValueError("Only the same-card failed pickup and actual normal pickup proof may continue")
        out["cartridge-cold"]={"path":str(source),"sha256":sha(source)};out["scope"]="Original drop is not replayed: same actual world entity was picked up normally using its observed position; finish all cold-download requirements on that complete original card."
    def transferring(v):
        p=v["cartridge"]["progress"];return p.get("card")==large["instance"]and p["stage"]=="downloading"and 0<p["bytes"]<p["total"]==8*1024*1024 and p["percent"]==p["bytes"]*100//p["total"]
    def loading_picture(name,mode=None):
        v=guest.wait(transferring,"real intermediate byte-count progress for "+name)
        if mode:
            p=v["polish"].get("presentation",{});check(out,p.get("mode")==mode and p.get("progress",{}).get("loading")is True,"actual fullscreen mode renders its real loading stage",p)
        else:
            audit=v["cartridge"].get("lcdLoading")or{};check(out,not v["screen"]and audit.get("card")==large["instance"]and audit.get("stage")=="downloading"and 0<audit.get("bytes",0)<audit.get("total",0),"actual world LCD draw records this intermediate card transfer",audit)
        row=guest.pic(name);check(out,transferring({"cartridge":row["before"]})and transferring({"cartridge":row["after"]})and row["lifecycleBefore"]["room"]==row["lifecycleAfter"]["room"]=="handheld/"+large["instance"]and row["lifecycleBefore"]["epoch"]==row["lifecycleAfter"]["epoch"],"capture remains within genuine nonzero/noncomplete ROM transfer in the same actual room",{"before":row["before"]["progress"],"after":row["after"]["progress"]})
    try:
        guest.do("dock_view",{"hideGui":False});baseline=guest.see();out["guestBaseline"]={k:baseline[k]for k in("downloadsGranted","downloadsDenied","workers","room")};check(out,not baseline["room"]and not baseline["workers"]and not guest.do("cartridge_cache",{"hash":large["hash"]})["cacheCheck"]["files"],"guest begins without a ROM cache or emulator worker")
        if not resume:
            host.camera(5.5,12.5,0,0);host.do("select_card",{"instance":large["instance"]});host.do("drop");host.camera(10.5,11.5);w=d.command("server","observe");drop=next(i for i in w["dock"]["drops"]if i.get("instance")==large["instance"]);out["actualDrop"]=drop;guest.camera(drop["x"],drop["z"],180,10)
        acquired=guest.wait(lambda v:any(i.get("instance")==large["instance"]and i["item"]=="muxi_console:cartridge"for i in items(v)),"real dropped card pickup by the second network player")
        check(out,same_card(raw(acquired,large["instance"]),large)and not any(i.get("instance")==large["instance"]for i in items(host.see())),"physical transfer preserves the exact whole named card and removes it from host")
        guest.do("select_card",{"instance":large["instance"]});held=guest.settle(30);check(out,not held["room"]and not held["workers"]and held["downloadsGranted"]==baseline["downloadsGranted"]and not guest.do("cartridge_cache",{"hash":large["hash"]})["cacheCheck"]["files"],"holding a raw transferred card alone never fetches its ROM")
        guest.inv();guest.install(large,7);guest.close();guest.camera();before=guest.see();check(out,before["downloadsGranted"]==baseline["downloadsGranted"]and not before["room"],"a stowed loaded handheld does not start an unauthorised preload")
        guest.do("slot",{"slot":7});loading_picture("cartridge-loading-world-lcd")
        guest.do("pixel_handheld_use",{"slot":7,"load":False});guest.wait(lambda v:v["polish"].get("presentation",{}).get("mode")=="model","normal right click enters MODEL while ROM is still downloading");loading_picture("cartridge-loading-model","model")
        guest.do("polish_mouse",{"button":1,"pressed":True});guest.do("polish_mouse",{"button":1,"pressed":False});guest.wait(lambda v:v["polish"].get("presentation",{}).get("mode")=="screen","ordinary right click selects SCREEN during the same download");loading_picture("cartridge-loading-screen","screen")
        running=guest.wait(lambda v:v["ready"]and v["workerRunning"]and v["frames"]>=30 and v["cartridge"]["progress"]["stage"]=="complete","first real GBA picture completes loading")
        check(out,running["room"]=="handheld/"+large["instance"]and not running["cartridge"]["progress"]["loading"]and running["downloadsGranted"]-baseline["downloadsGranted"]==(large["bytes"]+24575)//24576,"cold transfer uses exactly the real sequential 24KiB chunks and finishes on game picture")
        guest.pic("cartridge-gameplay-after-loading",running);guest.do("polish_callbacks",{"platform":"gba"});prior=guest.see();guest.do("polish_key",{"key":74,"pressed":True});pressed=guest.wait(lambda v:v["heldReview"]["input"]["input"]["buttons"]==256 and v["crc"]!=prior["crc"],"actual mapped A action reaches the running derived diagnostic");check(out,pressed["workers"]==running["workers"],"input affects the same actual native worker");guest.do("polish_key",{"key":74,"pressed":False});guest.wait(lambda v:v["heldReview"]["input"]["input"]["buttons"]==0,"normal action release")
        saved=guest.leave("first large-card run");out["firstSaveHash"]=saved["savedStateHash"];cache=guest.do("cartridge_cache",{"hash":large["hash"]})["cacheCheck"];check(out,len(cache["files"])==1 and cache["files"][0]["bytes"]==large["bytes"]and cache["files"][0]["sha256"]==large["hash"],"actual cached disk file matches the complete uploaded ROM",cache)
        guest.do("slot",{"slot":8});guest.settle(3);guest.do("slot",{"slot":7});restored=guest.wait(lambda v:v["ready"]and v["workerRunning"]and v["frames"]>=30 and v["restoredStateHash"]==saved["savedStateHash"],"same card restarts from the real saved state and verified cache")
        check(out,restored["downloadsGranted"]==saved["downloadsGranted"]and restored["room"]=="handheld/"+large["instance"],"verified cache restart requests zero extra ROM chunks",{"restoredStateHash":restored["restoredStateHash"],"downloadsGranted":restored["downloadsGranted"]});guest.pic("cartridge-cached-held-with-card",restored);final=guest.leave("cached large-card run")
        out["worldEvidence"]=d.command("server","cartridge_observe");disk=next(i for i in out["worldEvidence"]["disk"]if i["card"]==large["instance"])
        storage=Path(out["worldEvidence"]["worldStorage"]);check(out,storage.is_relative_to(d.root/"server")and storage.name=="muxi-console"and disk["contentSha256"]==large["hash"]and disk["stateSha256"]==disk["metadataHash"]==final["savedStateHash"],"authoritative world content and checkpoint disk bytes match their actual identities",disk);out["finalSaveHash"]=final["savedStateHash"];out["scenarioComplete"]=True
    except Exception as error:out["failure"]=str(error);raise
    finally:cleanup(d,out);out["observationTimeouts"]=d.observation_timeouts;path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding="utf-8")
    return out


def cold_sources(d,out):
    source=d.channel/"cartridge-cold-result.json";base=read_json(source)
    if base["scenarioComplete"]:return reference(d,out,"cartridge-cold")
    resumed=reference(d,out,"cartridge-cold-continuation");pickup=reference(d,out,"cartridge-pickup")
    if resumed.get("cartridge-cold")!={"path":str(source),"sha256":sha(source)}or sha(source)!="8496ae51b2d8bb058d49f51d39ebc56055e2d9135d93f3263a04510747461402"or resumed["cards"]!=base["cards"]or pickup["cards"]!=base["cards"]or resumed["cartridge-pickup"]!={"path":str(d.channel/"cartridge-pickup-result.json"),"sha256":sha(d.channel/"cartridge-pickup-result.json")}:raise ValueError("Exact same-card cold transfer continuation is required")
    out["cartridge-cold"]={"path":str(source),"sha256":sha(source)};return resumed


def hardware(d):
    path,out=record(d,"cartridge-hardware");previous=cold_sources(d,out);inventory_sources(d,out);out["cards"]=previous["cards"];large=out["cards"]["large_gba"];host=Front(d,out,"host");guest=Front(d,out,"guest")
    def world():return d.command("server","cartridge_observe")
    def panel_save(f,label):
        before=f.see();check(out,before["ready"]and before["workerRunning"],label+" is active before the real management-panel save")
        f.do("button",{"text":"保存并离开游戏"});ended=f.wait(lambda v:not v["room"]and not v["workers"],label+" management-panel normal save")
        check(out,ended["normalWorkerExits"]==before["normalWorkerExits"]+1 and ended["forcedWorkerExits"]==ended["failedWorkerExits"]==0,label+" real panel closes the worker naturally");return ended
    def turn(low,high):
        deadline=time.monotonic()+45
        while time.monotonic()<deadline:
            clock=d.command("server","pixel_world")["gameTime"];angle=(clock%7200)*.5%360
            if low<=angle<=high:return {"gameTime":clock,"degrees":angle}
            guest.settle(4)
        raise TimeoutError("Actual natural dock rotation did not reach the requested view")
    try:
        guest.do("slot",{"slot":8});guest.settle(3);guest.do("slot",{"slot":7});running=guest.wait(lambda v:v["ready"]and v["workerRunning"]and v["restoredStateHash"]==previous["finalSaveHash"]and v["frames"]>=30,"same complete GBA restarts from the actual cache checkpoint")
        shell=at(running,7);check(out,same_card(shell["inserted"],large),"actual drawn handheld retains the complete transferred card before visual review")
        guest.do("pixel_handheld_use",{"slot":7,"load":False});guest.wait(lambda v:v["polish"].get("presentation",{}).get("modelRendered")is True and not v["cartridge"]["progress"]["loading"],"actual MODEL uses body plus inserted-card fit after load");guest.pic("cartridge-inserted-model",running);guest.close();guest.camera();guest.pic("cartridge-inserted-held",running);guest.inv();guest.pic("cartridge-inserted-inventory-icon",running);guest.close();saved=guest.leave("hardware-view handheld run");out["preDockSaveHash"]=saved["savedStateHash"]
        host.camera(12.5,9.5,180,20);host.do("slot",{"slot":5});host.do("dock_place",{"x":12,"y":89,"z":6});d.wait_for("server",lambda v:"device"in v["dock"],"ordinary survival placement of the empty dock")
        host.camera(16.5,9.5,180,18);host.do("slot",{"slot":4});host.do("dock_place",{"x":16,"y":89,"z":6});d.wait_for("server",lambda v:sum(c["origin"]=="16, 90, 6"for c in v["dock"]["cells"])==4,"ordinary actual 2x2 monitor placement")
        guest.camera(12.5,4.1,0,25);guest.do("slot",{"slot":7});guest.do("dock_use_block",{"x":12,"y":90,"z":6,"face":"NORTH"});d.wait_for("server",lambda v:v["dock"]["dockedHandheld"].get("instance")==large["instance"],"actual full handheld installed in the dock")
        w=world();media=next(i for i in w["items"]if i["location"]=="device/12, 90, 6");check(out,media["nbt"]==shell["nbt"]and media["insertedNbt"]==large["nbt"],"dock keeps the entire same shell and nested card NBT",media)
        guest.do("slot",{"slot":8});guest.do("dock_use_block",{"x":12,"y":90,"z":6,"face":"NORTH"});guest.wait(lambda v:v["screen"].endswith("DeviceScreen"),"ordinary dock management panel");guest.do("button",{"text":"连接屏幕"});guest.do("button",{"text":"开机"});guest.do("button",{"text":"P1"});docked=guest.wait(lambda v:v["ready"]and v["workerRunning"]and v["restoredStateHash"]==saved["savedStateHash"],"same inserted card restores through the real dock");guest.close();guest.do("dock_view",{"hideGui":True});guest.camera(12.5,4.1,0,25)
        out["dockAngleA"]=turn(0,45);guest.pic("cartridge-dock-angle-a",docked);out["dockAngleB"]=turn(155,205);guest.pic("cartridge-dock-angle-b",docked)
        # A real cartridge used on a monitor connects a source; the monitor never consumes it or runs a core.
        nes=out["cards"]["nes"];host.camera(16.5,9.5,180,18);host.do("select_card",{"instance":nes["instance"]});before=host.see();rooms=d.command("server","observe")["rooms"];host.do("dock_use_block",{"x":16,"y":90,"z":6,"face":"SOUTH"});host.settle(4);after=host.see();w=world();screen=next(i for i in w["monitors"]if i["blockPosition"]=="16, 90, 6")
        check(out,same_card(raw(after,nes["instance"]),nes)and screen["mediaEmpty"]and"card"not in screen and screen["source"]=="12, 90, 6"and not after["workers"]and after["downloadsGranted"]==before["downloadsGranted"]and[len(d.command("server","observe")["rooms"]),len(rooms)]==[1,1],"using a real card on the monitor only links the actual source and leaves card/core ownership unchanged",screen)
        check(out,guest.see()["cartridge"]["screenSource"]==next(i for i in w["devices"]if i["kind"]=="dock")["position"],"display stream belongs to the dock source rather than a monitor session")
        guest.do("dock_view",{"hideGui":False});guest.do("dock_use_block",{"x":12,"y":90,"z":6,"face":"NORTH"});guest.pic("cartridge-dock-safe-leave-panel",docked);ended=panel_save(guest,"actual dock");guest.close();guest.do("dock_sneak_block",{"x":12,"y":90,"z":6,"face":"NORTH"});returned=guest.wait(lambda v:any(i.get("instance")==large["instance"]and i["item"]=="muxi_console:handheld"for i in items(v)),"ordinary complete dock retrieval")
        shell_after=next(i for i in items(returned)if i.get("instance")==large["instance"]);check(out,shell_after["nbt"]==shell["nbt"]and same_card(shell_after["inserted"],large),"normal dock retrieval returns the exact whole shell/card once");w=world();check(out,sum(i.get("instance")==large["instance"]for i in w["items"])==1,"original large card has exactly one physical location after dock save")
        disk=next(i for i in w["disk"]if i["card"]==large["instance"]);check(out,disk["stateSha256"]==disk["metadataHash"]==ended["savedStateHash"],"real final dock state bytes match the native saved checkpoint",disk);out["dockFinalSave"]=disk
        out["desktopSaves"]={}
        for platform,x,model in(("nes",8,1),("pce",14,11)):
            card=out["cards"][platform];host.do("dock_view",{"hideGui":False});host.camera(x+.5,23.5,180,28);host.do("select_card",{"instance":card["instance"]});host.do("use_block",{"x":x,"y":90,"z":20});host.wait(lambda v:not any(i.get("instance")==card["instance"]for i in items(v)),"normal explicit "+platform+" desktop card insertion")
            placed=next(i for i in world()["items"]if i["location"]==f"device/{x}, 90, 20");check(out,placed["nbt"]==card["nbt"],"actual "+platform+" slot retains the complete source card NBT")
            host.do("slot",{"slot":1});host.do("use_block",{"x":x,"y":90,"z":20});host.do("slot",{"slot":8});host.do("use_block",{"x":x,"y":90,"z":20});host.wait(lambda v:v["screen"].endswith("DeviceScreen"),"real desktop panel");host.do("button",{"text":"开机"});host.do("button",{"text":"P1"});desk=host.wait(lambda v:v["ready"]and v["workerRunning"]and v["frames"]>=30,"actual "+platform+" core starts with its normal admitted card");host.close();host.do("dock_view",{"hideGui":True});host.camera(x+.5,23.5,180,28);host.pic("cartridge-desktop-"+platform+"-inserted",desk)
            host.do("dock_view",{"hideGui":False});host.do("use_block",{"x":x,"y":90,"z":20});host.do("upgrade_click",{"slot":0,"button":0,"type":"QUICK_MOVE"});host.settle(4);still=host.see();physical=next(i for i in world()["items"]if i["location"]==f"device/{x}, 90, 20")
            check(out,still["ready"]and still["workers"]==desk["workers"]and physical["nbt"]==card["nbt"]and not any(i.get("instance")==card["instance"]for i in items(still)),"running "+platform+" refuses unsafe eject without stopping or moving the card")
            ended=panel_save(host,platform+" desktop");host.do("upgrade_click",{"slot":0,"button":0,"type":"QUICK_MOVE"});retrieved=host.wait(lambda v:any(i.get("instance")==card["instance"]for i in items(v)),"desktop eject only after normal save")
            check(out,same_card(raw(retrieved,card["instance"]),card),"safe "+platform+" desktop retrieval preserves whole original card");host.close();disk=next(i for i in world()["disk"]if i["card"]==card["instance"]);check(out,disk["stateSha256"]==disk["metadataHash"]==ended["savedStateHash"],platform+" actual world state and client hash agree",disk);out["desktopSaves"][platform]=disk
        out["finalWorld"]=world();check(out,len({i["instance"]for i in out["finalWorld"]["items"]if"instance"in i})==6 and all(sum(i.get("instance")==card["instance"]for i in out["finalWorld"]["items"])==1 for card in out["cards"].values()),"all six actually issued card identities survive uniquely with no minted or duplicated replacement");out["scenarioComplete"]=True
    except Exception as error:out["failure"]=str(error);raise
    finally:cleanup(d,out);out["observationTimeouts"]=d.observation_timeouts;path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding="utf-8")
    return out


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("--run",type=Path,required=True);p.add_argument("--build",type=Path,required=True);p.add_argument("--phase",choices=("inventory","cold","hardware"),default="inventory");p.add_argument("--continue-inventory",action="store_true");p.add_argument("--continue-cold",action="store_true");p.add_argument("--source-sha256");a=p.parse_args()
    if a.continue_inventory and(a.phase!="inventory"or not a.source_sha256):p.error("Inventory continuation requires its exact preserved source SHA")
    if a.continue_cold and(a.phase!="cold"or a.continue_inventory):p.error("Cold continuation is a separate scope")
    d=Driver(a.run,a.build);v=continue_inventory(d,a.source_sha256)if a.continue_inventory else cold(d,True)if a.continue_cold else{"inventory":inventory,"cold":cold,"hardware":hardware}[a.phase](d);print(json.dumps({"complete":v["scenarioComplete"],"checks":len(v["checks"]),"originals":len(v["screenshots"])}))
