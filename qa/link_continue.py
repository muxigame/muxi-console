"""Strict same-card continuation after the original diagnostic waited for release before redrawing."""
import argparse,json,zipfile
from pathlib import Path
from drive import Driver,read_json,sha
from polish_review import record,check
from cartridge_review import at,cursor,inserted,same_card,raw
from link_review import LinkFront,link,idle,audit,invite,live,end_link,exports,cleanup,cache,metric_delta,worker_closed

SOURCE="c99a21cc535e263ff38c680ced3d7a655782440a1d4b933fd5c8bf2667b398bc"
OBSERVATION="11ecd943e7b5bcacd28cc72cacb5e7fb6469fe0f1e0387da50241706eadc6614"


def run(d):
    source=d.channel/"link-result.json";base=read_json(source);observation=d.channel/"link-failure-observation.json";observed=read_json(observation)
    if sha(source)!=SOURCE or sha(observation)!=OBSERVATION or base["scenarioComplete"]or len(base["checks"])!=32 or not all(c["passed"]for c in base["checks"])or len(base["screenshots"])!=1 or base.get("cleanupFailure")or not base.get("failure","").startswith("own native CPU picture responds to real mapped A callback:")or base["runId"]!=d.marker["runId"]or base["productSha256"]!=d.build["product"]["sha256"]or observed["sourceResult"]["sha256"]!=SOURCE or observed["runId"]!=base["runId"]or observed["productSha256"]!=base["productSha256"]:raise ValueError("Exact preserved same-run diagnostic-release failure and physical export observation required")
    path,out=record(d,"link-continuation");out.update(source=dict(path=str(source),sha256=SOURCE,scenarioComplete=False,passed=32,images=1),observation=dict(path=str(observation),sha256=OBSERVATION),driverSha256=sha(Path(__file__)),linkHelperSha256=sha(Path(__file__).with_name("link_review.py")),qaBuildSha256=sha(d.build_path),cards=base["cards"],machines=base["machines"],scope="Continue only from actual final source group with host SRAM press count1/guest0. No burn/issuance, identity changes, consent replay or counter injection.");host=LinkFront(d,out,"host");guest=LinkFront(d,out,"guest");cards=out["cards"];machines=out["machines"]
    try:
        for f in(host,guest):
            v=f.see();check(out,idle(v)and cursor(v)["empty"]and same_card(inserted(v,2),cards[f.role])and at(v,2)["machine"]==machines[f.role],"same normally saved original "+f.role+" complete author card and machine remain physical",dict(machine=at(v,2),room=v["room"],workers=v["workers"]))
        worker=next(c["evidence"]for c in base["checks"]if c["label"]=="actual host LinkWorker PID generation exists");out["originalWorkerClosure"]=worker_closed(d,worker["pid"],worker["created"])
        with zipfile.ZipFile(d.build["product"]["path"])as jar:pin=json.loads(jar.read("META-INF/muxi/sameboy-link.json"))
        out["nativePin"]=pin;saved,evidence=exports(d,out,cards,machines,dict(host=1,guest=0),"actual preserved failed-observer final barrier",pin);group=evidence["group"]["hash"]
        check(out,group==observed["server"]["group"]["hash"],"actual source final group hash was not changed after normal cleanup",evidence["group"])
        before={f.role:f.see()for f in(host,guest)};invite(host,guest);guest.button("接受连线");states,worker2=live(host,guest,pin,"source group cold restore")
        check(out,link(states["host"])["lastStart"].get("groupState",{}).get("hash")==group,"fresh worker restores the actual original group with existing serial exchange and host count1",link(states["host"])["lastStart"])
        deltas={f.role:metric_delta(before[f.role],f.see())for f in(host,guest)};check(out,all(v["dataBytes"]==0 for v in deltas.values()),"both original verified caches supply restored link with zero new ROM body",deltas)
        guest_before=guest.see()["crc"];host.press();host.native_picture("link-resumed-host-own-machine");host.release();check(out,guest.see()["crc"]==guest_before,"host press-release changes only its own CPU picture")
        host_before=host.see()["crc"];guest.press();guest.native_picture("link-resumed-guest-own-machine");guest.release();check(out,host.see()["crc"]==host_before,"guest press-release changes only its own CPU picture")
        sound=guest.wait(lambda v:link(v)["receivedAudioBytes"]>0 and link(v)["receivedAudioNonzero"]>0 and link(v)["receivedAudioCrossings"]>0,"actual independent nonzero peer PCM")
        audio=link(sound);frequency=audio["receivedAudioCrossings"]*48000/(audio["receivedAudioBytes"]/4)
        check(out,not sound["workers"]and 850<frequency<1200,"peer's real approximately1024Hz PCM arrives without any local core",dict(bytes=audio["receivedAudioBytes"],nonzero=audio["receivedAudioNonzero"],crossings=audio["receivedAudioCrossings"],estimatedFrequency=frequency))
        end_link(host,guest,worker2,"source-restored link");saved,evidence=exports(d,out,cards,machines,dict(host=2,guest=1),"press-release final barrier",pin);second_group=evidence["group"]["hash"]
        host.do("probe",dict(hash=cards["guest"]["hash"]));denied=host.wait(lambda v:v["probe"]!="pending","post-end actual permission decision");check(out,denied["probe"]=="denied","ended link revokes partner ROM access even though its verified cache remains")
        before={f.role:f.see()for f in(host,guest)};invite(host,guest);guest.button("接受连线");cold,worker3=live(host,guest,pin,"new committed group cold restore")
        check(out,link(cold["host"])["lastStart"].get("groupState",{}).get("hash")==second_group and worker3["pid"]!=worker2["pid"],"another actual process restores the exact newer group hash")
        check(out,all(metric_delta(before[f.role],f.see())["dataBytes"]==0 for f in(host,guest)),"second cold restore also transfers zero ROM body")
        host.native_picture("link-resumed-cold-group-host");host.press();host.release();end_link(host,guest,worker3,"new cold-restored group");saved,evidence=exports(d,out,cards,machines,dict(host=3,guest=1),"second cold final barrier",pin)
        singles={}
        for f in(host,guest):
            f.do("cartridge_use",dict(hand="MAIN_HAND"));v=f.wait(lambda v:v["ready"]and v["workerRunning"]and v["room"].startswith("handheld/")and v["frames"]>=30,"same ordinary core restores "+f.role+" actual export")
            check(out,v["restoredStateHash"]==saved[f.role]["hash"],"ordinary "+f.role+" restores only its original physical machine/content checkpoint",dict(expected=saved[f.role]["hash"],actual=v["restoredStateHash"]));singles[f.role]=v
        host.picture("link-resumed-single-machine-host",singles["host"]);guest.picture("link-resumed-single-machine-guest",singles["guest"])
        out["preparingFromSingles"]=True;invite(host,guest);guest.button("接受连线");prepared,worker4=live(host,guest,pin,"normal independent single save/leave preparation")
        check(out,not link(prepared["host"])["lastStart"].get("groupState")and len(link(prepared["host"])["lastStart"]["states"])==2,"each normal single checkpoint prepares independently and invalidates the older group",link(prepared["host"])["lastStart"])
        end_link(host,guest,worker4,"prepared normal singles");exports(d,out,cards,machines,dict(host=3,guest=1),"normal-single prepared final barrier",pin)
        finals={f.role:f.see()for f in(host,guest)};regular=read_json(d.channel/"upgrade-result.json")["cards"]
        check(out,all(same_card(inserted(finals[r],2),cards[r])and at(finals[r],2)["machine"]==machines[r]and cursor(finals[r])["empty"]for r in cards)and all(same_card(raw(finals["host"],card["instance"]),card)for card in regular.values()),"all four actual whole cards and both original machine identities survive independently")
        out["scenarioComplete"]=True
    except Exception as failure:out["scenarioComplete"]=False;out["failure"]=str(failure);raise
    finally:cleanup(d,out);out["observationTimeouts"]=d.observation_timeouts;path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding="utf-8")
    return out


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("--run",type=Path,required=True);p.add_argument("--build",type=Path,required=True);a=p.parse_args();v=run(Driver(a.run,a.build));print(json.dumps(dict(complete=v["scenarioComplete"],checks=len(v["checks"]),images=len(v["screenshots"]))))
