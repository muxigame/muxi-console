"""Supplement the current candidate's media fronts using the owned vanilla camera adapter."""
from __future__ import annotations
import argparse
import json
import re
from pathlib import Path
from drive import Driver, read_json, sha
from quick_review import server_command


def run(d,group="media"):
    if group not in ("media","accessories"):raise ValueError("Known ordinary prop groups only")
    output=d.channel/("pixel-media-front-result.json" if group=="media" else "pixel-accessories-front-result.json")
    if output.exists():raise ValueError("Preserve the first media-front result and original frame")
    source=d.channel/"pixel-model-review-result.json";original=read_json(source)
    if not original.get("scenarioComplete") or original["runId"]!=d.marker["runId"] or original["productSha256"]!=d.build["product"]["sha256"]:raise ValueError("The same candidate's completed model fixture is required")
    out={"runId":d.marker["runId"],"productSha256":d.build["product"]["sha256"],"sourceResult":{"path":str(source),"sha256":sha(source)},"scenarioComplete":False,"physicalOSInput":False,"visualAcceptance":False,"screenshots":[],"mediaCount":21 if group=="media" else 0,"group":group,"largeScreenScenarioSkipped":True}
    try:
        current=d.command("host","observe")
        if current["room"] or current["workers"]:raise ValueError("Media-front static camera cannot interrupt a native game")
        if current.get("cards") or (d.channel/"pixel-live-review-result.json").exists():raise ValueError("Photograph media fronts before the live fixture disables gallery flight or issues real cards")
        name=current["playerName"]
        if not re.fullmatch(r"[A-Za-z0-9_]{1,16}",name):raise ValueError("Simple real runner player name required")
        d.command("host","slot",{"slot":8});d.command("host","review_view",{"held":False})
        # The fixture's fixed item displays face south. Stand on that side instead of treating their backs as labels.
        pose="46.5 96.38 39.5 180 40"if group=="media"else"46.5 92.8 31.8 180 32"
        out["cameraCommand"]=server_command(d,f"tp {name} {pose}")
        state=d.settle("host",16)
        row=d.capture("host","pixel-all-21-media-front"if group=="media"else"pixel-accessories-front");row.update(group="media-front"if group=="media"else"accessories-front",camera={key:state[key]for key in("x","y","z","yaw","pitch")});out["screenshots"].append(row)
        out["scenarioComplete"]=True
    except Exception as error:out["failure"]=str(error);raise
    finally:output.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding="utf-8")
    return out


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("--run",type=Path,required=True);p.add_argument("--build",type=Path,required=True);p.add_argument("--group",choices=("media","accessories"),default="media");a=p.parse_args();result=run(Driver(a.run,a.build),a.group);print(json.dumps({"scenarioComplete":result["scenarioComplete"],"screenshots":len(result["screenshots"])}))
