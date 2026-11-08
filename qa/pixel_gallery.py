"""Build a current-candidate-only gallery of untouched native Minecraft model screenshots."""
from __future__ import annotations
import argparse
import html
import json
import os
from pathlib import Path
from urllib.parse import quote
import zipfile
from PIL import Image
from gallery import REPO, run_root, sha


def build(run,report,output,require_live=False):
    root,owner=run_root(run);report=report.resolve(strict=True)
    if REPO/"build/qa" not in report.parents:raise ValueError("Use this repository's QA report")
    inputs=json.loads(report.read_text(encoding="utf-8"));product=Path(inputs["product"]["path"])
    if inputs["product"]["sha256"]!=owner["galleryProductSha256"] or sha(product)!=owner["galleryProductSha256"]:raise ValueError("Gallery candidate differs from the actual run")
    with zipfile.ZipFile(product)as jar:catalog=json.loads(jar.read("assets/muxi_console/model_catalog.json"))
    entries={e["cmd"]:e for e in catalog["entries"]+catalog["additional_styles"]}
    paths=[root/"console-qa/pixel-model-review-result.json"]
    live=root/"console-qa/pixel-live-review-result.json"
    if live.exists():paths.append(live)
    elif require_live:raise ValueError("The current candidate's live alignment scope is required")
    media_front=root/"console-qa/pixel-media-front-result.json"
    if media_front.exists():paths.append(media_front)
    elif require_live:raise ValueError("The media fronts must be photographed instead of inferred from station backs")
    accessory_front=root/"console-qa/pixel-accessories-front-result.json"
    if accessory_front.exists():paths.append(accessory_front)
    elif require_live:raise ValueError("The controller's front must be photographed instead of inferred from its back")
    output=output.absolute()
    if output.resolve()!=output or REPO/"build" not in output.parents or output.suffix!=".html":raise ValueError("Gallery output belongs under build/")
    rows=[];results=[]
    for path in paths:
        result=json.loads(path.read_text(encoding="utf-8"))
        if result["runId"]!=owner["runId"] or result["productSha256"]!=owner["galleryProductSha256"] or not result.get("scenarioComplete"):raise ValueError("Incomplete or mixed-candidate native result")
        reference=result.get("modelResult",result.get("sourceResult"))
        if reference and (Path(reference["path"]).resolve()!=paths[0] or sha(paths[0])!=reference["sha256"]):raise ValueError("The linked original model result was changed or came from another run")
        results.append((path,result))
        for c in result["screenshots"]:
            image=Path(c["path"]).absolute()
            if image.resolve(strict=True)!=image or image.parent!=root/"host/screenshots" or not c.get("nativeMinecraftFramebuffer") or sha(image)!=c["sha256"]:raise ValueError("Original native PNG path or hash changed")
            with Image.open(image)as original:
                if original.format!="PNG" or original.width<640 or original.height<360 or (original.width,original.height)!=(c["width"],c["height"]):raise ValueError("Native resolution differs")
                original.verify()
            row=dict(c);row.update(runId=owner["runId"],productSha256=owner["galleryProductSha256"])
            if path==live:row["category"]="live"
            elif c.get("group"):row["category"]="props"
            elif c.get("held"):row["category"]="held"
            elif c.get("cmd",0)>0:row["category"]="world"
            else:row["category"]="overview"
            rows.append(row)
    world={r["cmd"]for r in rows if r["category"]=="world"};held={(r["cmd"],r["hand"])for r in rows if r["category"]=="held"}
    portable={cmd for cmd,e in entries.items() if e.get("form_factor")in("handheld","viewer")}
    if world!=set(range(1,23)) or held!={(cmd,hand)for cmd in portable for hand in("main","off")}:raise ValueError("Every current body and both portable hands must have its own native original")
    if len({r["path"]for r in rows})!=len(rows):raise ValueError("A native original cannot count for multiple model views")
    if require_live and len([r for r in rows if r["category"]=="live"])!=6:raise ValueError("GB/GBA main/off and two actual dock views are required")
    output.parent.mkdir(parents=True,exist_ok=True)
    esc=html.escape
    def url(path):return quote(os.path.relpath(path,output.parent).replace(os.sep,"/"),safe="/:.")
    def caption(row):
        if row["category"]in("world","held"):
            entry=entries[row["cmd"]];name=entry.get("zh_name",entry["platform"].upper());return name+(" · "+("主手"if row["hand"]=="main"else"副手") if row["category"]=="held"else" · 放置机体与媒体")
        return {"pixel-all-21-media.png":"21类媒体整体与背面","pixel-all-21-media-front.png":"21类媒体正面：标签、沟槽与读取开口","pixel-accessories.png":"配件台背面与刻录机、底座","pixel-accessories-front.png":"配件台正面：控制器按键与空白媒体","pixel-live-gb-main.png":"GB原创诊断 · 新GB机体主手","pixel-live-gb-off.png":"同一GB卡带 · 副手","pixel-live-gba-main.png":"GBA原创ARM诊断 · 新GBA机体主手","pixel-live-gba-off.png":"同一GBA卡带 · 副手","pixel-live-dock-rotation-1.png":"同一GBA装入底座 · 活动画面与低位旋转","pixel-live-dock-rotation-2.png":"同一运行核心 · 后续旋转角度"}.get(Path(row["path"]).name,"本轮机体总览")
    cards=[]
    order={"world":0,"held":1,"props":2,"live":3,"overview":4}
    for row in sorted(rows,key=lambda r:(order[r["category"]],r.get("cmd",0),r.get("hand",""),r["path"])):
        title=caption(row);image=url(row["path"]);meta=esc(Path(row["path"]).name)+"<br>"+row["sha256"]
        cards.append(f'<figure data-group="{row["category"]}"><a href="{image}"><img loading="lazy" src="{image}" alt="{esc(title)}"></a><figcaption><b>{esc(title)}</b><span>{row["width"]} × {row["height"]} · Minecraft原始帧</span><details><summary>来源与哈希</summary><code>{meta}</code></details></figcaption></figure>')
    links=''.join(f'<li><a href="{url(path)}">{path.name}</a> · SHA256 <code>{sha(path)}</code></li>'for path,_ in results)
    observation=root/"console-qa/pixel-review-observation.json"
    if observation.exists():
        note=json.loads(observation.read_text(encoding="utf-8"))
        if note.get("runId")!=owner["runId"] or note.get("productSha256")!=owner["galleryProductSha256"]:raise ValueError("Observation note differs from the actual candidate")
        retained=esc(note.get("observed",{}).get("lastError",""))
        links+=f'<li><a href="{url(observation)}">历史观察说明</a> · SHA256 <code>{sha(observation)}</code><br>保留原值：“{retained}”。原操作未记录，不能据此认定零历史错误。</li>'
    audio_log=root/"host/logs/latest.log"
    if audio_log.exists() and "Failed to open OpenAL device" in audio_log.read_text(encoding="utf-8",errors="replace"):
        links+=f'<li><a href="{url(audio_log)}">本轮原始客户端日志</a>记录MC启动时“Failed to open OpenAL device”，声音系统因此关闭。未证明它与保留的通用提示同因；本轮不计音频输出或听感验收。</li>'
    closure=root/"run-result.json";closed=False
    if closure.exists():
        c=json.loads(closure.read_text(encoding="utf-8"));closed=c.get("runId")==owner["runId"] and c.get("exitCodes")=={"server":0,"host":0,"guest":0} and c.get("nativeChildAudit",{}).get("normalChildrenExit",False)
    close_text="三Minecraft进程已正常退出；原生子进程审计无残留。"if closed else"运行关闭状态以同run的run-result.json为准。"
    metadata=json.dumps(rows,ensure_ascii=False).replace("<","\\u003c")
    output.write_text(f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>木犀 · 年代硬件像素模型原图</title><style>body{{margin:0;background:#eff1ed;color:#18383b;font:16px/1.65 system-ui,sans-serif}}main{{max-width:1320px;margin:auto;padding:36px 24px}}h1{{font-size:32px;line-height:1.25}}p{{max-width:1000px}}nav{{display:flex;flex-wrap:wrap;gap:10px;margin:22px 0}}button{{border:1px solid #b9ccc5;border-radius:24px;background:#fff;color:#18484b;padding:8px 16px;cursor:pointer}}button[aria-pressed=true]{{background:#174b50;color:white}}.grid{{display:grid;grid-template-columns:repeat(auto-fit,minmax(380px,1fr));gap:22px}}figure{{margin:0;background:white;border:1px solid #c9d3cc;border-radius:12px;overflow:hidden}}img{{display:block;width:100%;height:auto}}figcaption{{padding:14px 17px}}figcaption span{{display:block;color:#60716c;font-size:13px}}code{{font-size:11px;overflow-wrap:anywhere}}details{{margin-top:10px}}a{{color:#176b74}}li{{margin:7px 0}}@media(max-width:460px){{main{{padding:20px 12px}}.grid{{grid-template-columns:1fr}}}}</style><main><h1>年代硬件 · 像素模型原图</h1><p>22款机体、9款掌机与VB的主副手，以及21类媒体和配套设备。所有照片仅来自本轮同一候选的真实Minecraft客户端；没有使用旧几何截图、软件预览、裁图或合成。点击可看原始分辨率。</p><p>按实体轮廓、D-pad与A/B的真实左右布局、屏幕开口和卡带形态逐款审核。原图完整不等于人工外观通过；GB/GBA动态部分只使用本仓自写诊断，不代表商业游戏库兼容。大屏本轮保持原样，未重做其放拆、浏览器或21核心矩阵。</p><nav><button aria-pressed="true" data-filter="all">全部</button><button data-filter="world">22款机体</button><button data-filter="held">主手与副手</button><button data-filter="props">21类媒体与配件</button><button data-filter="live">真实运行与底座</button><button data-filter="overview">总览</button></nav><div class="grid">{''.join(cards)}</div><h2>本轮来源</h2><p>runId：<code>{owner['runId']}</code><br>候选SHA256：<code>{owner['galleryProductSha256']}</code><br>{close_text}</p><ul>{links}</ul><p>实体键鼠、手柄热插拔与整合包全量共存未由这些前端回调和模型图验证。</p><script type="application/json" id="originals">{metadata}</script><script>for(const button of document.querySelectorAll('button[data-filter]'))button.onclick=()=>{{for(const b of document.querySelectorAll('button[data-filter]'))b.setAttribute('aria-pressed',String(b===button));for(const f of document.querySelectorAll('figure[data-group]'))f.hidden=button.dataset.filter!=='all'&&f.dataset.group!==button.dataset.filter;}};</script></main></html>''',encoding="utf-8")
    print(json.dumps({"gallery":str(output),"originals":len(rows),"worldBodies":len(world),"heldViews":len(held),"currentCandidateOnly":True,"normalMinecraftClosure":closed}))


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("--run",type=Path,required=True);p.add_argument("--build",type=Path,required=True);p.add_argument("--output",type=Path,default=REPO/"build/pixel-realistic-review/index.html");p.add_argument("--require-live",action="store_true");a=p.parse_args();build(a.run,a.build,a.output,a.require_live)
