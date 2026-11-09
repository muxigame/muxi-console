"""Native writer slots, real crafting preservation and current UI/hand props. Never launches Minecraft."""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
from drive import Driver,read_json,sha
from polish_review import record,check
from cartridge_review import Front,cleanup,at,cursor,raw,inserted,same_card


def inventory_slot(slot):
    if 0<=slot<=8:return slot+36
    if 9<=slot<=35:return slot
    if slot==40:return 45
    raise ValueError("Player inventory slot out of bounds")


def picture_view(view):
    # Retain real identities and full-stack hashes without repeating every options entry
    # and every full inventory SNBT twice in each of 31 screenshot records.
    source=view["upgrade"];summary={k:source[k]for k in("menu","containerId","hoveredSlot","mouseGuiX","mouseGuiY")if k in source}
    for key in("inventory","slots"):
        summary[key]=[{**{k:stack[k]for k in("slot","index","item","count","model","instance","machine")if k in stack},"nbtSha256":hashlib.sha256(stack.get("nbt","").encode("utf-8")).hexdigest(),**({"insertedInstance":stack["inserted"].get("instance")}if stack.get("inserted",{}).get("instance")else{})}for stack in source[key]if not stack.get("empty")]
    summary["cursor"]=source["cursor"]
    if source.get("hoveredStack"):summary["hoveredStack"]=source["hoveredStack"]
    return summary


class Upgrade(Front):
    def view(self):return self.see()["upgrade"]
    def click_menu(self,slot,button=0,kind="PICKUP"):
        self.do("upgrade_click",dict(slot=slot,button=button,type=kind));return self.settle(2)
    def player_menu_slot(self,slot):
        if self.view()["menu"].endswith("WriterMenu"):
            if 0<=slot<=8:return slot+28
            if 9<=slot<=35:return slot-8
            raise ValueError("Writer has no player offhand slot")
        return inventory_slot(slot)
    def player(self,slot):return self.view()["inventory"][slot]
    def picture(self,name,session=None):
        name=name.lower().replace("_","-")
        self.settle(4);before=self.see();row=self.d.capture(self.role,name);after=self.see();row.update(role=self.role,screenBefore=before["screen"],screenAfter=after["screen"],upgradeBefore=picture_view(before),upgradeAfter=picture_view(after),presentationBefore=before["polish"].get("presentation"),presentationAfter=after["polish"].get("presentation"));self.out["screenshots"].append(row)
        if session:check(self.out,all(v["ready"]and v["workerRunning"]and all(v[k]==session[k]for k in("room","epoch","workers"))for v in(before,after)),"same actual session/worker surrounds "+name)
    def craft(self,source,dye,target,accept=True):
        self.inv();before=self.player(source)
        self.click_menu(inventory_slot(source));self.click_menu(1)
        self.click_menu(inventory_slot(dye));self.click_menu(2,1);self.click_menu(inventory_slot(dye))
        value=self.wait(lambda v:v["upgrade"]["slots"][0].get("model")==target if accept else v["upgrade"]["slots"][0]["empty"],"actual special-recipe result or compatibility rejection")["upgrade"]
        if accept:
            preview=value["slots"][0];check(self.out,preview["withoutModel"]==before["withoutModel"],"real crafting preview preserves every source component except model",preview)
            self.click_menu(0);self.click_menu(inventory_slot(source));after=self.player(source)
            check(self.out,after["model"]==target and after["withoutModel"]==before["withoutModel"] and self.view()["slots"][1]["empty"],"normal result pickup consumes one original device and retains all components",after)
        else:
            check(self.out,value["slots"][1]["nbt"]==before["nbt"],"incompatible recipe leaves the complete original device untouched",value["slots"][1])
            self.click_menu(1);self.click_menu(inventory_slot(source));self.click_menu(2);self.click_menu(inventory_slot(dye))
        self.close();self.settle(3);return self.player(source)
    def writer(self):
        self.d.command("server","position",dict(player=self.d.marker["clientNames"][self.role],x=8.5,z=11.5));self.do("slot",dict(slot=8));self.do("use_block",dict(x=8))
        return self.wait(lambda v:v["screen"].endswith("WriterScreen") and v["upgrade"]["menu"].endswith("WriterMenu"),"actual network writer menu")
    def burn(self,rom,pin,destination):
        expected_pin=self.d.build[pin] if isinstance(pin,str) else pin
        self.writer();self.click_menu(self.player_menu_slot(0),0,"QUICK_MOVE")
        check(self.out,self.view()["slots"][0]["item"]=="muxi_console:blank_cartridge" and self.view()["slots"][0]["count"]==1,"writer takes exactly one real blank cartridge")
        self.do("writer_drop",dict(rom=rom));self.do("writer_confirm");self.picture("upgrade-writer-ready-"+rom);self.do("button",dict(text="上传并刻录"))
        burned=self.wait(lambda v:v["upgrade"]["slots"][0].get("instance") and v["upgrade"]["slots"][0].get("hash")==expected_pin["sha256"],"writer output is the genuinely issued original "+rom)["upgrade"]["slots"][0]
        self.picture("upgrade-writer-issued-"+rom);self.click_menu(0);self.click_menu(self.player_menu_slot(destination));self.close();self.settle(3)
        self.d.command("server","upgrade_card_sentinel",dict(instance=burned["instance"]));card=self.wait(lambda v:any(i.get("instance")==burned["instance"] and "qa_upgrade_card" in i.get("data","") for i in v["upgrade"]["inventory"]),"issued card sentinel synchronizes")["upgrade"]["inventory"][destination]
        check(self.out,card["instance"]==burned["instance"] and card["hash"]==burned["hash"] and card["count"]==1,"sentinel decorates an issued card without changing its identity",card);return card
    def run_card(self,slot,label):
        self.do("slot",dict(slot=slot));started=self.wait(lambda v:v["ready"]and v["workerRunning"]and v["frames"]>=60,"actual original ROM starts "+label)
        self.do("cartridge_use",dict(hand="MAIN_HAND"));self.wait(lambda v:v["screen"].endswith("GameScreen"),"normal fullscreen entry")
        self.do("upgrade_callback_source");self.do("upgrade_key",dict(key=74,pressed=True));self.settle(8);self.do("upgrade_key",dict(key=74,pressed=False));self.settle(4);self.picture("upgrade-game-"+label,started)
        result=self.leave(label);self.do("slot",dict(slot=8));self.close();return started,result


def finish_upgrade(f,cards,slots,core,expected_gb_hash):
    d,out=f.d,f.out
    f.inv();f.eject(2,cards["gba"],6);f.install(cards["gb"],2);f.close();f.craft(2,slots["lime"],4);restored,_=f.run_card(2,"gb-after-two-games")
    check(out,restored["restoredStateHash"]==expected_gb_hash,"switching back from a second game restores the actual earlier original GB checkpoint",dict(expected=expected_gb_hash,actual=restored["restoredStateHash"]))
    for core_key,dye_key,model in (("ndsCore","black",23),("n3dsCore","cyan",24),("wiiCore","lightBlue",25)):
        f.craft(slots[core_key],slots[dye_key],model)
    f.inv();f.picture("upgrade-crafted-new-hardware");f.close()
    finish_presentation(f,cards,slots,core)


def finish_presentation(f,cards,slots,core,nds_already_held=False):
    d,out=f.d,f.out
    for name in ("ndsCore","n3dsCore"):
        if not (nds_already_held and name=="ndsCore"):
            f.inv();f.click_menu(inventory_slot(slots[name]),8,"SWAP");f.close()
        f.do("slot",dict(slot=8));f.settle(14);f.picture("upgrade-crafted-held-"+name.lower())
        f.inv();f.click_menu(inventory_slot(slots[name]),8,"SWAP");f.close()
    f.do("upgrade_controls");f.do("upgrade_button",dict(text="游戏机键位绑定"));f.wait(lambda v:v["screen"].endswith("ConsoleBindingsScreen"),"independent native-style binding entry");f.do("upgrade_button",dict(text="掌机"));f.do("upgrade_platform",dict(platform="gb"));original_key=f.view()["keys"]["gb"]["8"]
    check(out,original_key=="key.keyboard.j","fresh profile has the expected original GB A binding before reversible UI edit")
    f.do("upgrade_button",dict(text=f.view()["keys"]["gb"]["label.8"]));f.do("upgrade_key",dict(key=89,pressed=True));f.do("upgrade_key",dict(key=89,pressed=False));f.picture("upgrade-bindings-platform-groups");f.close();f.wait(lambda v:v["screen"].endswith("ControlsScreen"),"native settings parent restored");f.close();f.do("upgrade_reload_options")
    check(out,f.view()["keys"]["gb"]["8"]=="key.keyboard.y","new binding page persists the actual same options.txt key through normal reload")
    f.do("upgrade_controls");f.do("upgrade_button",dict(text="游戏机键位绑定"));f.do("upgrade_button",dict(text=f.view()["keys"]["gb"]["label.8"]));f.do("upgrade_key",dict(key=74,pressed=True));f.do("upgrade_key",dict(key=74,pressed=False));check(out,f.view()["keys"]["gb"]["8"]==original_key,"test restores the original GB A binding")
    f.do("upgrade_platform",dict(platform="nds"));f.do("upgrade_button",dict(text="模拟动作"));f.picture("upgrade-input-simulated-actions")
    f.do("upgrade_button",dict(text="家用机"));f.do("upgrade_platform",dict(platform="wii"));original_device=f.view()["keys"]["wii"]["device"];f.do("upgrade_device",dict(device=513));f.picture("upgrade-input-wii-device");f.close();f.close();f.do("upgrade_reload_options");check(out,f.view()["keys"]["wii"]["device"]==513,"real Wii device selector persists through the existing profile reload")
    f.do("upgrade_controls");f.do("upgrade_button",dict(text="游戏机键位绑定"));f.do("upgrade_device",dict(device=original_device));f.close();f.close()
    # Only identity-free appearance props are supplied here; real cards and machines stay in their slots.
    for kind in ("writer","dock","case","controller","blank","card_nes","card_gba","card_wii"):
        d.command("server","upgrade_prop",dict(kind=kind));f.do("slot",dict(slot=slots["prop"]));f.settle(14);f.picture("upgrade-prop-right-"+kind)
        f.inv();f.click_menu(inventory_slot(slots["prop"]),40,"SWAP");f.close();f.do("slot",dict(slot=8));f.settle(14);f.picture("upgrade-prop-left-"+kind)
        f.inv();f.click_menu(inventory_slot(slots["prop"]),40,"SWAP");f.close()
    out["worldEvidence"]=d.command("server","upgrade_observe");keys={(s["machine"],s["content"])for s in out["worldEvidence"]["saved"]}
    check(out,all((core["machine"],card["hash"])in keys for card in cards.values()),"both original games retain committed machine-scoped save namespaces")
    final=f.view();physical=[]
    for item in final["inventory"]:
        if item.get("item")=="muxi_console:cartridge" and item.get("instance"):physical.append(item)
        if item.get("inserted",{}).get("instance"):physical.append(item["inserted"])
    check(out,final["cursor"]["empty"]and f.player(2)["machine"]==core["machine"]and all(sum(i.get("instance")==card["instance"]and i.get("nbt")==card["nbt"]and i.get("count")==1 for i in physical)==1 for card in cards.values()),"each normally issued card survives exactly once with complete original components and the original machine UUID")
    out["finalInventory"]=final["inventory"]
    out["scenarioComplete"]=True


def run(d):
    path,out=record(d,"upgrade");f=Upgrade(d,out)
    try:
        d.prepare();out["fixture"]=d.command("server","upgrade_prepare");slots=out["fixture"]["fixtures"]
        f.do("dock_view",dict(hideGui=False));f.settle(10)
        # Closing a normal menu returns its real blank instead of silently consuming it.
        f.writer();f.click_menu(f.player_menu_slot(0),0,"QUICK_MOVE");f.close();v=f.settle(4)
        check(out,sum(i.get("count",0)for i in v["upgrade"]["inventory"]if i.get("item")=="muxi_console:blank_cartridge")==4,"normal writer close returns its one actual input blank intact")
        cards={"gb":f.burn("gb","gbDiagnosticRom",7),"gba":f.burn("gba","gbaDiagnosticRom",6)};out["cards"]=cards
        f.craft(2,slots["white"],3);f.inv();f.install(cards["gb"],2);f.do("upgrade_hover",dict(slot=inventory_slot(2)));hover=f.wait(lambda v:v["upgrade"].get("hoveredSlot")==inventory_slot(2)and v["upgrade"].get("hoveredStack",{}).get("inserted",{}).get("instance")==cards["gb"]["instance"],"actual rendered vanilla hover targets the loaded handheld slot")
        check(out,hover["upgrade"]["hoveredStack"]["nbt"]==f.player(2)["nbt"],"real hovered slot is the complete loaded handheld rather than adjacent dye",hover["upgrade"]["hoveredStack"]);f.picture("upgrade-loaded-tooltip");f.close()
        _,saved_gb=f.run_card(2,"gb-before-reshape");core=f.player(2);check(out,bool(core.get("machine")),"normal gameplay allocated the original physical machine identity",core)
        f.craft(2,slots["purple"],5);first,restored=f.run_card(2,"gb-after-reshape")
        check(out,first["restoredStateHash"]==saved_gb["savedStateHash"] and f.player(2)["machine"]==core["machine"],"compatible reshape restores the same real checkpoint under the same machine UUID")
        f.inv();f.eject(2,cards["gb"],7);f.install(cards["gba"],2);f.close();f.run_card(2,"gba-second-game")
        before=f.player(2);f.craft(2,slots["white"],3,accept=False);check(out,f.player(2)["nbt"]==before["nbt"],"GBA card prevents incompatible GB reshape without loss")
        finish_upgrade(f,cards,slots,core,restored["savedStateHash"])
    except Exception as error:out["scenarioComplete"]=False;out["failure"]=str(error);raise
    finally:cleanup(d,out);out["observationTimeouts"]=d.observation_timeouts;path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding="utf-8")
    return out


UPGRADE_PARTIAL_SHA="3f0ffe64234cb465e8a9e3d8e5b4746e28ff2e918631e5867ff8ae0b39ecfc8b"
UPGRADE_PRESENTATION_PARTIAL_SHA="2e87023fab3d63249775fd4fd5d8a7fc4588ee478e6de9b8d6fe5e9c1dca7f61"


def upgrade_sources(d,out):
    source=d.channel/"upgrade-result.json";base=read_json(source)
    if base["runId"]!=d.marker["runId"]or base["productSha256"]!=d.build["product"]["sha256"]:raise ValueError("Exact current candidate/run required")
    if base["scenarioComplete"]:
        out["upgrade"]={"path":str(source),"sha256":sha(source)};return base
    if sha(source)!=UPGRADE_PARTIAL_SHA or len(base["checks"])!=25 or not all(c["passed"]for c in base["checks"])or len(base["screenshots"])!=8 or not base.get("failure","").startswith("normal empty-cursor card extraction:")or base.get("cleanupFailure"):raise ValueError("Only the exact preserved native QA busy-extraction partial can be continued")
    continuation=d.channel/"upgrade-continuation-result.json";v=read_json(continuation)
    if v["runId"]!=base["runId"]or v["productSha256"]!=base["productSha256"]or v["source"]["sha256"]!=sha(source)or v["cards"]!=base["cards"]or v.get("cleanupFailure")or not all(c["passed"]for c in v["checks"]):raise ValueError("Exact same-card continuation source required")
    out["upgrade"]={"path":str(source),"sha256":sha(source),"scenarioComplete":False,"passed":25,"images":8}
    out["upgradeContinuation"]={"path":str(continuation),"sha256":sha(continuation),"scenarioComplete":v["scenarioComplete"],"passed":len(v["checks"]),"images":len(v["screenshots"])}
    if v["scenarioComplete"]and len(v["screenshots"])==23:return {**v,"sourceScenarioComplete":False}
    if sha(continuation)!=UPGRADE_PRESENTATION_PARTIAL_SHA or len(v["checks"])!=16 or len(v["screenshots"])!=2 or v.get("failure")!="java.lang.IllegalArgumentException: Simple screenshot name required":raise ValueError("Only exact retained screenshot-name failure may use final presentation evidence")
    final=d.channel/"upgrade-presentation-result.json";done=read_json(final)
    if done.get("cleanupFailure")or not all(c["passed"]for c in done["checks"])or done["runId"]!=base["runId"]or done["productSha256"]!=base["productSha256"]or done["source"]["sha256"]!=sha(continuation)or done["original"]["sha256"]!=sha(source)or done["cards"]!=base["cards"]:raise ValueError("Exact same-card presentation source required")
    out["upgradePresentation"]={"path":str(final),"sha256":sha(final),"scenarioComplete":done["scenarioComplete"],"passed":len(done["checks"]),"images":len(done["screenshots"])}
    if done["scenarioComplete"]and len(done["screenshots"])==21:return {**done,"sourceScenarioComplete":False}
    if sha(final)!="2e3d6bfd457c1543106c98b487ff1d988f8ef9c63493016b89b7678dec8d90bc"or len(done["checks"])!=6 or len(done["screenshots"])!=15 or done.get("failure")!="java.lang.IllegalArgumentException: Simple screenshot name required":raise ValueError("Only the retained card-style name rejection has this final completion scope")
    finish=d.channel/"upgrade-finish-result.json";f=read_json(finish)
    if not f["scenarioComplete"]or f.get("cleanupFailure")or not all(c["passed"]for c in f["checks"])or f["runId"]!=base["runId"]or f["productSha256"]!=base["productSha256"]or f["cards"]!=base["cards"]or [row["sha256"]for row in f["sources"]]!=[sha(source),sha(continuation),sha(final)]or len(f["screenshots"])!=8:raise ValueError("Exact final appearance/preservation completion required")
    out["upgradeFinish"]={"path":str(finish),"sha256":sha(finish),"passed":len(f["checks"]),"images":8};return {**f,"sourceScenarioComplete":False}


def continue_upgrade(d,source_sha):
    source=d.channel/"upgrade-result.json";previous=read_json(source)
    if source_sha!=UPGRADE_PARTIAL_SHA or sha(source)!=source_sha or previous["scenarioComplete"]or len(previous["checks"])!=25 or not all(c["passed"]for c in previous["checks"])or len(previous["screenshots"])!=8 or previous.get("cleanupFailure")or not previous.get("failure","").startswith("normal empty-cursor card extraction:")or previous["runId"]!=d.marker["runId"]or previous["productSha256"]!=d.build["product"]["sha256"]:raise ValueError("Exact preserved source false/scope is required")
    path,out=record(d,"upgrade-continuation");out.update(source=dict(path=str(source),sha256=source_sha,passed=25,images=8,scenarioComplete=False),cards=previous["cards"],fixture=previous["fixture"],scope="Same actual candidate/run/cards, continue after QA extraction hit valid running-card protection. No burn/prepare/prior crafting replay.");f=Upgrade(d,out);cards=out["cards"];slots=out["fixture"]["fixtures"]
    try:
        v=f.see();guest=d.command("guest","observe");check(out,all(not row["room"]and not row["workers"]and cursor(row)["empty"]for row in(v,guest)),"adopt the normally cleaned original clients with no live worker/cursor")
        check(out,same_card(inserted(v,2),cards["gba"])and same_card(raw(v,cards["gb"]["instance"]),cards["gb"])and at(v,6)["empty"],"same original whole GBA stays inserted and GB stays in its actual raw slot",dict(handheld=at(v,2),rawGB=raw(v,cards["gb"]["instance"])))
        core=f.player(2);machine=core["machine"];saved=d.command("server","upgrade_observe")["saved"];out["sourceSaves"]=saved
        selected=[row for row in saved if row["machine"]==machine and row["content"]in{c["hash"]for c in cards.values()}]
        check(out,len(selected)==2 and all(row["hash"]==row["diskSha256"]and row["size"]==row["diskBytes"]for row in selected)and next(row for row in selected if row["content"]==cards["gba"]["hash"])["hash"]==previous["finalClients"]["host"]["savedStateHash"],"both original machine/game checkpoints remain real on disk and cleanup GBA hash matches source",selected)
        f.do("slot",dict(slot=8));f.close();f.settle(4)
        first_gb=next(row for row in selected if row["content"]==cards["gb"]["hash"])
        finish_upgrade(f,cards,slots,core,first_gb["hash"])
        # The completed remaining path records actual final keys; no new state is injected.
        check(out,any(row["machine"]==machine and row["content"]==first_gb["content"]for row in out["worldEvidence"]["saved"]),"continued GB restore/save remains under its pre-existing original game namespace")
    except Exception as error:out["scenarioComplete"]=False;out["failure"]=str(error);raise
    finally:cleanup(d,out);out["observationTimeouts"]=d.observation_timeouts;path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding="utf-8")
    return out


def continue_presentation(d,source_sha):
    source=d.channel/"upgrade-continuation-result.json";prior=read_json(source);original=d.channel/"upgrade-result.json";base=read_json(original)
    if source_sha!=UPGRADE_PRESENTATION_PARTIAL_SHA or sha(source)!=source_sha or sha(original)!=UPGRADE_PARTIAL_SHA or prior["scenarioComplete"]or prior.get("cleanupFailure")or prior.get("failure")!="java.lang.IllegalArgumentException: Simple screenshot name required"or len(prior["checks"])!=16 or not all(c["passed"]for c in prior["checks"])or len(prior["screenshots"])!=2 or prior["source"]["sha256"]!=sha(original)or prior["runId"]!=d.marker["runId"]or prior["productSha256"]!=d.build["product"]["sha256"]or prior["cards"]!=base["cards"]:raise ValueError("Exact two retained partials and current run/candidate required")
    path,out=record(d,"upgrade-presentation");out.update(source=dict(path=str(source),sha256=source_sha,passed=16,images=2,scenarioComplete=False),original=dict(path=str(original),sha256=sha(original),passed=25,images=8,scenarioComplete=False),cards=base["cards"],fixture=base["fixture"],scope="Only remaining real held/UI/prop presentation after screenshot-name rejection; no game/crafting/burn replay.");f=Upgrade(d,out);slots=out["fixture"]["fixtures"];cards=out["cards"]
    try:
        v=f.see();guest=d.command("guest","observe");check(out,all(not row["room"]and not row["workers"]and cursor(row)["empty"]for row in(v,guest)),"adopt actual normally cleaned native players without active transaction")
        check(out,at(v,8).get("model")==23 and at(v,slots["ndsCore"])["empty"]and at(v,slots["n3dsCore"]).get("model")==24 and at(v,slots["wiiCore"]).get("model")==25 and same_card(inserted(v,2),cards["gb"])and same_card(raw(v,cards["gba"]["instance"]),cards["gba"]),"adopt exact already-crafted real devices and original complete cards without repeating mutation")
        f.close();finish_presentation(f,cards,slots,f.player(2),True)
    except Exception as error:out["scenarioComplete"]=False;out["failure"]=str(error);raise
    finally:cleanup(d,out);out["observationTimeouts"]=d.observation_timeouts;path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding="utf-8")
    return out


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument("--run",type=Path,required=True);parser.add_argument("--build",type=Path,required=True);parser.add_argument("--continue-source-sha256");parser.add_argument("--continue-ui-source-sha256");args=parser.parse_args();d=Driver(args.run,args.build);result=continue_presentation(d,args.continue_ui_source_sha256)if args.continue_ui_source_sha256 else continue_upgrade(d,args.continue_source_sha256)if args.continue_source_sha256 else run(d);print(json.dumps({"complete":result["scenarioComplete"],"checks":len(result["checks"]),"images":len(result["screenshots"])}))
