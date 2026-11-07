"""Link verified original Minecraft screenshots into one review gallery. Never edits or copies images."""
from __future__ import annotations

import argparse
import hashlib
import html
import json
import os
from pathlib import Path
import re
from urllib.parse import quote
from PIL import Image

REPO=Path(__file__).resolve().parents[1]
WORKSPACE=REPO.parent


def sha(path):
    digest=hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda:stream.read(1024*1024),b""):
            digest.update(block)
    return digest.hexdigest()


def run_root(path):
    supplied=path.absolute();real=supplied.resolve(strict=True)
    if supplied!=real:
        raise ValueError("Canonical numbered run paths required")
    parts=real.relative_to(WORKSPACE/"test-environment").parts
    if len(parts)!=3 or not re.fullmatch(r"client-[1-9][0-9]*",parts[0]) or parts[1]!="runs":
        raise ValueError("Originals must belong to numbered unified runs")
    owner=json.loads((real/"local-mc-owner.json").read_text(encoding="utf-8"))
    if owner["instanceRoot"]!=str(real) or owner.get("productionMutation") or not owner.get("offlineLoopback") or set(owner.get("roles",[]))!={"server","host","guest"} or not re.fullmatch(r"[0-9a-f]{32}",owner.get("runId","")):
        raise ValueError("Foreign/nonlocal screenshot run")
    products=[row for row in owner.get("inputArtifacts",[]) if re.fullmatch(r"muxi-console-(?!qa-).+\.jar",row.get("file",""))]
    if {row.get("scope") for row in products}!={"client","server"} or len(products)!=2 or len({row.get("sha256") for row in products})!=1:
        raise ValueError("The owner must pin the same product in both sides")
    owner["galleryProductSha256"]=products[0]["sha256"]
    return real,owner


def captures(value):
    if isinstance(value,dict):
        if {"path","sha256","nativeMinecraftFramebuffer"}<=value.keys():
            yield value
        else:
            for child in value.values():
                yield from captures(child)
    elif isinstance(value,list):
        for child in value:
            yield from captures(child)


def build(world_path,current_path,output,visual_path=None):
    world,world_owner=run_root(world_path);current,current_owner=run_root(current_path)
    output=output.absolute()
    if output.resolve()!=output or REPO/"build" not in output.parents or output.suffix!=".html":
        raise ValueError("Gallery is a local build artifact only")
    inputs=[(world,world_owner,"model-review-result.json","world")]
    for name,category in (("held-review-result.json","held"),("hardware-scenario-result.json","live"),("browser-scenario-result.json","browser")):
        inputs.append((current,current_owner,name,category))
    for path in sorted((current/"console-qa").glob("shared-*-result.json")):
        inputs.append((current,current_owner,path.name,"shared"))
    visual_paths=() if visual_path is None else (visual_path,) if isinstance(visual_path,(str,os.PathLike)) else tuple(visual_path)
    added_runs={world,current}
    for supplied_visual in visual_paths:
        visual,visual_owner=run_root(Path(supplied_visual))
        if visual in added_runs:
            raise ValueError("Each additional visual pass must have its own new run")
        added_runs.add(visual)
        visual_results=[name for name in ("quick-review-result.json","held-live-review-result.json") if (visual/"console-qa"/name).is_file()]
        if not visual_results:
            raise ValueError("A requested visual run needs quick-review-result.json or held-live-review-result.json: "+str(visual))
        for name in visual_results:
            inputs.append((visual,visual_owner,name,"visual"))
        for name in ("leave-during-join-result.json","nes-regression-result.json"):
            if (visual/"console-qa"/name).is_file():
                inputs.append((visual,visual_owner,name,"regression"))
    rows=[];seen=set();statuses=[]
    for root,owner,name,category in inputs:
        path=root/"console-qa"/name
        if not path.is_file() or path.resolve(strict=True)!=path:
            raise ValueError("Missing requested review result: "+str(path))
        result=json.loads(path.read_text(encoding="utf-8"))
        if result["runId"]!=owner["runId"] or result["productSha256"]!=owner["galleryProductSha256"]:
            raise ValueError("Screenshot result differs from its actual run/product owner")
        notes=[]
        if result.get("silentOriginalN64Diagnostic",{}).get("audioAcceptance") is False:
            notes.append("原创N64诊断无AI音源；本结果不验音频")
        if result.get("finalEscSaveVerified") is False:
            notes.append("本次收尾为正常设备断电，未验证最终Esc保存；不能替代同步中退出回归")
        if result.get("handover",{}).get("mameExactPixelContinuityClaimed") is False:
            notes.append("不声称MAME跨进程画面严格续接")
        statuses.append({"run":root.name,"runId":owner["runId"],"name":name,"url":quote(os.path.relpath(path,output.parent).replace(os.sep,"/"),safe="/:."),"sha":sha(path),"complete":result.get("scenarioComplete",False),"failure":result.get("failure",""),"notes":notes})
        for capture in captures(result):
            image=Path(capture["path"])
            if image in seen:
                continue
            if category=="world" and (capture.get("held") or "first-person" in image.name):
                continue
            actual=image.resolve(strict=True)
            relative=actual.relative_to(root).parts
            if image!=actual or len(relative)!=3 or relative[0] not in ("host","guest") or relative[1]!="screenshots" or image.suffix!=".png" or sha(image)!=capture["sha256"] or capture.get("nativeMinecraftFramebuffer") is not True:
                raise ValueError("Missing, modified, linked or nonoriginal Minecraft screenshot")
            with Image.open(image) as opened:
                width,height=opened.size
                opened.verify()
            if width<640 or height<360:
                raise ValueError("Insufficient native screenshot resolution")
            seen.add(image)
            url=quote(os.path.relpath(image,output.parent).replace(os.sep,"/"),safe="/:.")
            rows.append({"name":image.name,"category":category,"url":url,"role":relative[0],"run":root.name,"runId":owner["runId"],"product":result["productSha256"],"sha":capture["sha256"],"size":f"{width} × {height}"})
    if sum(row["category"]=="world" for row in rows)!=23 or sum(row["category"]=="held" for row in rows)!=20:
        raise ValueError("Gallery requires all22 world originals plus overview and all20 current main/offhand originals")
    esc=html.escape
    cards=[]
    for row in rows:
        cards.append(f'''<article data-group="{row['category']}"><a href="{esc(row['url'])}" target="_blank"><img src="{esc(row['url'])}" loading="lazy" alt="{esc(row['name'])}"></a><div class="copy"><b>{esc(row['name'])}</b><p>{esc(row['role'])} · {row['size']} · {esc(row['run'])}</p><details><summary>原件身份与哈希</summary><p>runId {row['runId']}</p><p>产品 SHA256 {row['product']}</p><p>原图 SHA256 {row['sha']}</p></details></div></article>''')
    status_html="".join(f"<li><a href=\"{esc(status['url'])}\" target=\"_blank\">{esc(status['run'])} / {esc(status['name'])}</a>：{'记录流程完成' if status['complete'] else '未完成/原失败保留'}{(' — '+esc(status['failure'][:180])) if status['failure'] else ''}{''.join('<p>'+esc(note)+'</p>' for note in status['notes'])}<details><summary>结果原件</summary><p>runId {status['runId']}</p><p>JSON SHA256 {status['sha']}</p></details></li>" for status in statuses)
    document=f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>木犀游戏机 · Minecraft 原图审核</title><style>
body{{margin:0;background:#10282d;color:#edf0df;font:16px/1.6 system-ui,sans-serif}}header,main{{max-width:1450px;margin:auto;padding:24px}}header{{padding-top:40px}}h1{{margin:0;font-size:32px}}.hint{{max-width:1000px;color:#b9cfca}}nav{{display:flex;flex-wrap:wrap;gap:8px;margin:24px 0}}button{{background:#26454a;color:#edf0df;border:1px solid #6c8a8b;border-radius:6px;padding:8px 16px;cursor:pointer}}button.active{{background:#d7ac66;color:#10282d}}.grid{{display:grid;grid-template-columns:repeat(auto-fit,minmax(min(360px,100%),1fr));gap:18px}}article{{border:1px solid #3e6064;background:#19363b;border-radius:8px;overflow:hidden}}img{{display:block;width:100%;height:auto}}.copy{{padding:14px}}p{{margin:6px 0}}details{{font-size:12px;color:#b9cfca;overflow-wrap:anywhere}}a{{color:#efc17a}}.count{{color:#efc17a}}[hidden]{{display:none!important}}
</style><header><p class="count">{len(rows)} 张经哈希核对的原始 Minecraft PNG</p><h1>木犀游戏机 · 原生审核</h1><p class="hint">直接浏览原文件；没有复制、裁切、合成或重绘照片。22款世界机型引用首轮原图；当前主副手和动态流程引用第二轮原图，旧裁切持物图已排除。后续轮次依次保留昼夜显示、掌机复拍与退出回归，以及各轮原失败。每张原图显示实际runId和产品哈希；块资源复用范围以根协调者的资源哈希核对为准。单张照片不证明全部平台、实体键鼠/手柄、音频设备或光影兼容。</p><details><summary>实际流程与未验范围</summary><ul>{status_html}</ul><p>静音原创N64诊断不验证音频；MAME仅在真实非空PCM批次匹配后记录音频传输，不代表实体音响已验。原失败保持，不以续办结果改写原轮。正常退出见各run的run-result.json。</p></details><nav><button class="active" data-filter="all">全部</button><button data-filter="world">22款世界机型</button><button data-filter="held">主手 / 副手</button><button data-filter="live">掌机动态</button><button data-filter="browser">实体浏览器</button><button data-filter="shared">共享运行</button><button data-filter="visual">昼夜 / 掌机复拍</button><button data-filter="regression">退出回归</button></nav></header><main class="grid">{''.join(cards)}</main><script>for(const b of document.querySelectorAll('button'))b.onclick=()=>{{for(const x of document.querySelectorAll('button'))x.classList.toggle('active',x===b);for(const c of document.querySelectorAll('article'))c.hidden=b.dataset.filter!=='all'&&c.dataset.group!==b.dataset.filter}};</script></html>'''
    output.parent.mkdir(parents=True,exist_ok=True)
    output.write_text(document,encoding="utf-8")
    return {"gallery":str(output),"originalImages":len(rows),"categories":{category:sum(row["category"]==category for row in rows) for category in dict.fromkeys(row["category"] for row in rows)},"sourceRunIds":list(dict.fromkeys(status["runId"] for status in statuses)),"resultFiles":len(statuses),"imagesCopied":0,"imageEdits":0,"oldCroppedHeldExcluded":True}


if __name__=="__main__":
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--world-run",type=Path,required=True)
    parser.add_argument("--current-run",type=Path,required=True)
    parser.add_argument("--visual-run",type=Path,action="append",help="Append a new run containing quick-review-result.json or held-live-review-result.json, plus any exit-regression results; repeat to preserve multiple runs")
    parser.add_argument("--output",type=Path,default=REPO/"build/model-review/index.html")
    args=parser.parse_args()
    print(json.dumps(build(args.world_run,args.current_run,args.output,args.visual_run),ensure_ascii=False,indent=2))
