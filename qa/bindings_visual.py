"""Capture the three corrected settings pages in a new exact candidate without rebinding any key."""
import argparse,json,zipfile
from pathlib import Path
from drive import Driver,sha
from polish_review import record,check
from upgrade_review import Upgrade

OLD_PRODUCT="701c5ea2ae005abf7ba4a1b5258a3445d6d03682cd5cd8551f8abf71687d0b0a"


def run(d):
    if d.build["product"]["sha256"]==OLD_PRODUCT:raise ValueError("The rejected08 render implementation is not the new visual candidate")
    path,out=record(d,"bindings-visual");out.update(driverSha256=sha(Path(__file__)),scope="Only three native UI page renders and unchanged actual key/device values. No cards, ROM, saves, world fixtures or synthetic inputs.");f=Upgrade(d,out)
    try:
        v=f.see();check(out,not v["room"]and not v["workers"]and v["upgrade"]["cursor"]["empty"]and not v["screen"],"adopt idle actual player with no menu or worker")
        baseline=v["upgrade"]["keys"];f.do("dock_view",dict(hideGui=False));f.settle(10)
        f.do("upgrade_controls");f.do("upgrade_button",dict(text="游戏机键位绑定"))
        f.do("upgrade_button",dict(text="掌机"));f.do("upgrade_platform",dict(platform="gb"));f.picture("bindings-clear-platform-groups")
        f.do("upgrade_platform",dict(platform="nds"));f.do("upgrade_button",dict(text="模拟动作"));f.picture("bindings-clear-simulated-actions")
        f.do("upgrade_button",dict(text="家用机"));f.do("upgrade_platform",dict(platform="wii"));f.picture("bindings-clear-wii-device")
        f.close();f.close();after=f.see();check(out,after["upgrade"]["keys"]==baseline and not after["room"]and not after["workers"],"all registered key mappings and actual controller device choices remain unchanged")
        out["scenarioComplete"]=True
    except Exception as failure:out["failure"]=str(failure);out["scenarioComplete"]=False;raise
    finally:
        for _ in range(3):
            if not f.see()["screen"]:break
            f.close()
        out["observationTimeouts"]=d.observation_timeouts;path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding="utf-8")
    return out


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("--run",type=Path,required=True);p.add_argument("--build",type=Path,required=True);a=p.parse_args();v=run(Driver(a.run,a.build));print(json.dumps(dict(complete=v["scenarioComplete"],checks=len(v["checks"]),images=len(v["screenshots"]))))
