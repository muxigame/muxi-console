"""Link this dock review's exact original PNGs and scope results; never copies or changes an image."""
from __future__ import annotations
import argparse
import html
import json
import os
from pathlib import Path
from urllib.parse import quote
from PIL import Image
from gallery import REPO, run_root, sha


def build(run,report,output):
    root,owner=run_root(run);report=report.resolve(strict=True)
    if REPO/"build/qa" not in report.parents:raise ValueError("Use the independent QA build report")
    inputs=json.loads(report.read_text(encoding="utf-8"))
    if inputs["product"]["sha256"]!=owner["galleryProductSha256"] or sha(Path(inputs["product"]["path"]))!=owner["galleryProductSha256"]:raise ValueError("The current candidate differs from the actual run")
    output=output.absolute()
    if output.resolve()!=output or REPO/"build" not in output.parents or output.suffix!=".html":raise ValueError("Gallery output belongs in the repository build directory")
    sources=[]
    for name in ("dock-review-result.json","dock-visual-result.json","dock-visual-resumed-result.json"):
        path=root/"console-qa"/name;value=json.loads(path.read_text(encoding="utf-8"))
        if value["runId"]!=owner["runId"] or value["productSha256"]!=owner["galleryProductSha256"]:raise ValueError("Mixed native run or product result")
        sources.append((path,value))
    original=sources[0][1];supplement=sources[2][1]
    if not supplement.get("scenarioComplete") or not supplement.get("dockFinalLeaveVerified"):raise ValueError("Supplemental scope and real final dock save must finish first")
    if sha(sources[0][0])!=supplement["sourceResult"]["sha256"] or sha(sources[1][0])!=supplement["previousSupplement"]["sha256"]:raise ValueError("An original scope result was changed")
    accepted={"dock-01-held-world.png":"拿在手上直接玩：实际GB画面",
              "dock-02-held-controls.png":"F手持操作：保留机体和世界",
              "dock-03-fullscreen.png":"普通物品右键：同一局进入全屏",
              "dock-04-screen-front.png":"3×2薄大屏与掌机底座同局",
              "dock-visual-resumed-01-low-rotation-a.png":"低位旋转展示 · 第一时刻",
              "dock-visual-resumed-02-low-rotation-b.png":"同一底座与掌机 · 后续旋转角度",
              "dock-visual-resumed-03-screen-thin-side.png":"薄屏斜侧：六格完整机体与活动画面",
              "dock-visual-resumed-04-screen-back.png":"背板近照：下边框贴近画面底缘",
              "dock-visual-resumed-05-screen-front.png":"补验：合法控制距离内的活动大屏"}
    rows=[];history=[]
    for _,value in sources:
        for capture in value.get("screenshots",[]):
            path=Path(capture["path"]).absolute()
            if path.resolve(strict=True)!=path or path.parent!=root/"host/screenshots" or not capture.get("nativeMinecraftFramebuffer") or sha(path)!=capture["sha256"]:raise ValueError("Original PNG path or identity changed")
            with Image.open(path)as picture:
                if picture.format!="PNG" or picture.width<640 or picture.height<360 or (picture.width,picture.height)!=(capture["width"],capture["height"]):raise ValueError("Original PNG dimensions differ")
                picture.verify()
            row=dict(path=str(path),name=path.name,sha256=capture["sha256"],width=capture["width"],height=capture["height"],runId=owner["runId"],productSha256=owner["galleryProductSha256"])
            if path.name in accepted:row["caption"]=accepted[path.name];rows.append(row)
            else:history.append(row)
    if {row["name"]for row in rows}!=set(accepted):raise ValueError("The complete requested original review set is required")
    rows.sort(key=lambda row:list(accepted).index(row["name"]))
    output.parent.mkdir(parents=True,exist_ok=True)
    def url(path):return quote(os.path.relpath(path,output.parent).replace(os.sep,"/"),safe="/:.")
    esc=html.escape
    cards=[]
    for row in rows:
        u=url(row["path"])
        cards.append(f'<figure><a href="{u}"><img loading="lazy" src="{u}" alt="{esc(row["caption"])}"></a><figcaption><b>{esc(row["caption"])}</b><span>{row["width"]} × {row["height"]} · 原始Minecraft PNG</span><details><summary>来源与文件哈希</summary><code>{esc(row["name"])}<br>{row["sha256"]}</code></details></figcaption></figure>')
    status=[]
    for path,value in sources:
        status.append(f'<li><a href="{url(path)}">{esc(path.name)}</a> · 原始complete={str(value.get("scenarioComplete",False)).lower()} · checks={value.get("checks",0)}<br><small>SHA256 {sha(path)}</small></li>')
    old=''.join(f'<li><a href="{url(row["path"])}">{esc(row["name"])}</a> · {row["sha256"]}</li>' for row in history)
    closure=root/"run-result.json";closed=False
    if closure.exists():
        final=json.loads(closure.read_text(encoding="utf-8"));closed=final.get("runId")==owner["runId"] and final.get("exitCodes")=={"server":0,"host":0,"guest":0} and final.get("nativeChildAudit",{}).get("normalChildrenExit",False)
    close_text="三个Minecraft进程均正常退出，原生子进程审计无残留。" if closed else "本页已核原生worker自然结束；Minecraft进程收口以本run的run-result.json为准。"
    page=f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>木犀掌机 · 底座 · 薄大屏审核</title>
<style>body{{margin:0;background:#edf1ed;color:#17383c;font:16px/1.65 system-ui,sans-serif}}main{{max-width:1260px;margin:auto;padding:35px 24px}}h1{{font-size:32px;line-height:1.25}}p{{max-width:940px}}.grid{{display:grid;grid-template-columns:repeat(auto-fit,minmax(380px,1fr));gap:22px}}figure{{margin:0;background:white;border:1px solid #c3d2cb;border-radius:12px;overflow:hidden}}img{{display:block;width:100%;height:auto}}figcaption{{padding:14px 18px}}figcaption span{{display:block;color:#61716e;font-size:13px}}code,small{{font-size:11px;overflow-wrap:anywhere}}.note{{background:#fff4d8;border-left:4px solid #b3842a;padding:14px 20px}}a{{color:#176d77}}details{{margin-top:12px}}li{{margin-bottom:8px}}@media(max-width:460px){{main{{padding:20px 12px}}.grid{{grid-template-columns:1fr}}}}</style>
<main><h1>掌机 · 低位旋转底座 · 3×2薄大屏</h1><p>同一张实体GB卡带，从手持运行、右键全屏到安装底座并恢复大屏播放。以下直接链接真实Minecraft原图；没有复制、裁切、合成或修饰照片。原图文件完整与脚本完成不能代替人工外观审核。</p>
<p>已核四朝向六格放置、受阻不消耗、拆从片单件掉落，及同卡完整物品/保存身份守恒。补验中每次拍摄前确认同一底座worker仍在运行，最终正常离开产生新存档generation，客户端保存哈希与服务器一致。实体键鼠、手柄按键/热插拔不属于本次自动前端回调验收。</p>
<div class="grid">{''.join(cards)}</div><h2>原始结果与验证边界</h2><p class="note">主结果首次记录complete=true、48项，但原05/06底座出画、07侧面相机越过8格范围触发会话结束；它本身没有证明最终主动保存退出。首次补验又遇单次host观察超时，原false结果保留。当前补验独立闭合取景、活动会话和最终正常保存范围，历史结果未被改写。</p><ul>{''.join(status)}</ul>
<details><summary>历史取景失败原图（不作当前通过图）</summary><ul>{old}</ul></details>
<h2>固定来源</h2><p>runId：<code>{owner['runId']}</code><br>候选SHA256：<code>{owner['galleryProductSha256']}</code><br>{close_text}</p>
<script type="application/json" id="originals">{json.dumps(rows,ensure_ascii=False).replace('<','\\u003c')}</script></main></html>'''
    output.write_text(page,encoding="utf-8")
    print(json.dumps({"gallery":str(output),"currentOriginals":len(rows),"historicalOriginals":len(history),"results":len(sources),"normalMinecraftClosure":closed}))


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("--run",type=Path,required=True);p.add_argument("--build",type=Path,required=True);p.add_argument("--output",type=Path,default=REPO/"build/dock-review/index.html");a=p.parse_args();build(a.run,a.build,a.output)
