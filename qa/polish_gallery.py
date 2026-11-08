"""Validate and link only this candidate's original hardware/fullscreen/monitor Minecraft frames."""
from __future__ import annotations
import argparse
import html
import json
import os
from pathlib import Path
from urllib.parse import quote
from PIL import Image
from gallery import REPO,run_root,sha


def build(run,report,output):
    root,owner=run_root(run);report=report.resolve(strict=True);inputs=json.loads(report.read_text(encoding="utf-8"));digest=owner["galleryProductSha256"]
    if REPO/"build/qa"not in report.parents or inputs["product"]["sha256"]!=digest or sha(Path(inputs["product"]["path"]))!=digest:raise ValueError("Frozen current candidate/QA must match the actual run")
    paths=[root/"console-qa"/(name+"-result.json")for name in("polish-model","polish-screen","polish-play","polish-monitor-clean","polish-play-continuation","polish-dock-visual-resumed","polish-dock-visual")]
    results=[];rows=[]
    for index,path in enumerate(paths):
        result=json.loads(path.read_text(encoding="utf-8"))
        eof_scope=index==2 and result.get("scenarioComplete")is False and result.get("failure")=="EOF when reading a line"and result.get("finalWorkers")=={"host":[],"guest":[]}and len(result["checks"])==7 and len(result["screenshots"])==5
        adapter_scope=index==6 and result.get("scenarioComplete")is False and"getAsBoolean()"in result.get("failure","")and result.get("finalWorkers")=={"host":[],"guest":[]}and len(result["checks"])==2 and not result["screenshots"]
        if result.get("runId")!=owner["runId"]or result.get("productSha256")!=digest or not(result.get("scenarioComplete")or eof_scope or adapter_scope)or result.get("cleanupFailure")or not all(c["passed"]for c in result["checks"]):raise ValueError("Incomplete/mixed-candidate native stage")
        if index==4 and(result.get("polish-play")!={"path":str(paths[2]),"sha256":sha(paths[2])}or len(result["screenshots"])!=13 or result.get("finalWorkers")!={"host":[],"guest":[]}):raise ValueError("Strict same-run EOF continuation is required")
        if index==5 and(result.get("polish-play-continuation")!={"path":str(paths[4]),"sha256":sha(paths[4])}or result.get("polish-dock-visual")!={"path":str(paths[6]),"sha256":sha(paths[6])}or len(result["screenshots"])!=2 or result.get("finalWorkers")!={"host":[],"guest":[]}):raise ValueError("Same-card normally saved dock framing supplement required")
        for previous in paths:
            if previous==path:continue
            key=previous.name.removesuffix("-result.json")
            if key in result and result[key]!={"path":str(previous),"sha256":sha(previous)}:raise ValueError("The immutable prior-stage source changed")
        results.append((path,result))
        for record in result["screenshots"]:
            p=Path(record["path"]).absolute()
            if p.resolve(strict=True)!=p or p.parent!=root/"host/screenshots"or not record.get("nativeMinecraftFramebuffer")or sha(p)!=record["sha256"]:raise ValueError("Native original path/hash differs")
            with Image.open(p)as im:
                if im.format!="PNG"or im.size!=(record["width"],record["height"])or im.width<640 or im.height<360:raise ValueError("Native original dimensions differ")
                im.verify()
            row=dict(record);row["resultOrigin"]=path.name;row["category"]=("portable"if row.get("held")else"hardware")if index==0 else"monitor"if index in(1,3)else"controls"if"vanilla"in p.stem else"hint"if"hint"in p.stem else"dock"if"dock"in p.stem else"fullscreen";rows.append(row)
    if len({r["path"]for r in rows})!=len(rows):raise ValueError("An original may not count as several independent captures")
    expected_screen={"polish-monitor-"+s+".png"for s in("south","north","east","west","legacy-migrated","single-upgraded","front","side","back","front-clean")}
    expected_play={"polish-hint-visible.png","polish-hint-expired.png","polish-vanilla-controls.png","polish-vanilla-keybinds.png","polish-dock-monitor-active.png","polish-dock-rotated.png","polish-dock-clean-a.png","polish-dock-clean-b.png"}|{"polish-"+platform+"-"+suffix+".png"for platform in("gb","gba")for suffix in("model-idle","buttons-a-pressed","buttons-pressed","buttons-released","pure-screen","esc-held")}
    if {Path(r["path"]).name for r in rows if r["category"]=="monitor"}!=expected_screen or{Path(r["path"]).name.removeprefix("resumed-")for r in rows if r["category"]not in("hardware","portable","monitor")}!=expected_play or len(rows)!=62:raise ValueError("Every current monitor, hint, native controls, fullscreen/input and dock original is required")
    bodies={r["cmd"]for r in rows if r["category"]=="hardware"};portable={r["cmd"]for r in rows if r["category"]=="portable"}
    if bodies!=set(range(1,23))or portable!={3,4,5,9,14,15,16,17,18,19}:raise ValueError("Every refined hardware body needs a current-candidate original")
    output=output.absolute()
    if output.resolve()!=output or REPO/"build"not in output.parents or output.suffix!=".html":raise ValueError("Gallery belongs under this repository's build directory")
    output.parent.mkdir(parents=True,exist_ok=True)
    def url(p):return quote(os.path.relpath(p,output.parent).replace(os.sep,"/"),safe="/:.")
    esc=html.escape
    titles={"polish-hint-visible":"拿起掌机 · 物品栏上方短提示","polish-hint-expired":"同一持有状态 · 提示自然消失","polish-vanilla-controls":"Minecraft原生控制页 · 键盘与手柄","polish-vanilla-keybinds":"Minecraft原生按键绑定 · 实际保存/重读","polish-monitor-south":"南向2×2显示器 · 生存放置","polish-monitor-north":"北向2×2显示器 · 生存放置","polish-monitor-east":"东向2×2显示器 · 生存放置","polish-monitor-west":"西向2×2显示器 · 生存放置","polish-monitor-legacy-migrated":"旧3×2六片实际加载迁移到2×2","polish-monitor-single-upgraded":"受阻旧单屏清障后实际扩展到2×2","polish-monitor-front":"电脑显示器 · 正面（保留原生选中轮廓）","polish-monitor-front-clean":"同一显示器正面 · 普通F1隐藏HUD/选择轮廓的补图","polish-monitor-side":"电脑显示器 · 薄屏及底座侧面","polish-monitor-back":"电脑显示器 · 背面","polish-dock-monitor-active":"同一卡带 · 底座与电脑显示器实时运行","polish-dock-rotated":"同一底座会话 · 后续低位旋转角度"}
    captions={"model-idle":"带完整机身的全屏模式","buttons-a-pressed":"A实际按下 · 整颗按钮蓝色","buttons-pressed":"方向/动作/功能键组合实际按下","buttons-released":"松开后实际恢复机身材料","pure-screen":"纯屏幕模式 · 原比例最大化与细框","esc-held":"Esc回普通持有 · 同一局继续"}
    titles.update({"polish-dock-monitor-active":"监视器活动流 · 原底座取景被屏遮挡","polish-dock-rotated":"原取景不足 · 仅可见近屏活动流","polish-dock-clean-a":"同一卡带底座补图 · 完整低位旋转掌机","polish-dock-clean-b":"同一底座会话 · 后续真实旋转时刻"})
    def title(row):
        stem=Path(row["path"]).stem.removeprefix("resumed-")
        if stem in titles:return titles[stem]
        if row["category"]in("hardware","portable"):return row["platform"].upper()+" · "+("真实世界机体/媒体"if row["category"]=="hardware"else"实际双手托持")
        for suffix,text in captions.items():
            if stem.endswith(suffix):return row.get("platform","").upper()+" · "+text
        return stem
    figures=[]
    for row in rows:
        caption=title(row);src=url(row["path"]);details=json.dumps({k:row[k]for k in("resultOrigin","presentation","feedbackAudit","input","render")if k in row},ensure_ascii=False)
        figures.append(f'<figure data-group="{row["category"]}"><a href="{src}"><img src="{src}" loading="lazy" alt="{esc(caption)}"></a><figcaption><b>{esc(caption)}</b><span>{row["width"]} × {row["height"]} · Minecraft原始帧</span><details><summary>实际渲染与原图来源</summary><pre>{esc(details)}</pre><code>{esc(Path(row["path"]).name)}<br>{row["sha256"]}</code></details></figcaption></figure>')
    closure=root/"run-result.json";closed=False
    if closure.exists():
        j=json.loads(closure.read_text(encoding="utf-8"));closed=j.get("runId")==owner["runId"]and j.get("exitCodes")=={"server":0,"host":0,"guest":0}and j.get("nativeChildAudit",{}).get("normalChildrenExit",False)
    links=''.join(f'<li><a href="{url(p)}">{p.name}</a> · SHA256 <code>{sha(p)}</code> · {len(r["checks"])}项实际检查 · '+('原始中止 false：'+esc(r.get('failure',''))+'；已过检查保留，正常清理'if not r['scenarioComplete']else'此范围完成 true')+'</li>'for p,r in results)
    metadata=json.dumps(rows,ensure_ascii=False).replace("<","\\u003c")
    output.write_text(f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>木犀游戏机 · 全屏、整颗按键与电脑显示器</title><style>body{{margin:0;background:#edf1ee;color:#193a40;font:16px/1.65 system-ui,sans-serif}}main{{max-width:1320px;margin:auto;padding:32px 24px}}h1{{font-size:32px;line-height:1.3}}p{{max-width:1060px}}nav{{display:flex;flex-wrap:wrap;gap:10px;margin:24px 0}}button{{border:1px solid #bbccc6;background:white;color:#244d53;border-radius:24px;padding:9px 16px;cursor:pointer}}button[aria-pressed=true]{{background:#174f5b;color:white}}.grid{{display:grid;grid-template-columns:repeat(auto-fit,minmax(380px,1fr));gap:22px}}figure{{margin:0;border:1px solid #c8d3cd;border-radius:12px;overflow:hidden;background:white}}img{{display:block;width:100%;height:auto}}figcaption{{padding:14px 17px}}span{{display:block;font-size:13px;color:#62756e}}code,pre{{font-size:11px;overflow-wrap:anywhere;white-space:pre-wrap}}details{{margin-top:10px}}a{{color:#156f83}}li{{margin:8px 0}}@media(max-width:460px){{main{{padding:20px 12px}}.grid{{grid-template-columns:1fr}}}}</style><main><h1>整颗按键反馈 · 两种全屏 · 电脑显示器</h1><p>62张原图来自本轮同一候选、同一真实Minecraft运行。22机体、10款便携形态、短提示、原生控制页、带机身全屏与纯屏、按钮实际按下/松开、2×2显示器及旧屏迁移分别留有原始来源。图片未经裁切、编辑、合成或旧图替代，点击可看原始分辨率。</p><p>右键切换机身/纯屏，Esc回普通持有并保留同一局；独立安全离开才提交最终状态。F/F8可以由原生Controls绑定为游戏动作，不再是预留视角/退出快捷键。提示只在选择掌机后短暂出现。游戏画面为本仓自写GB/GBA诊断，不代表商业游戏兼容。真实baked quad审核记录实际提交的蓝色顶点、白纹理及六向面数，未把正面截图冒称所有背面均肉眼可见。</p><p>普通前端回调和隐藏窗口授权输入源不是实体OS键鼠或真实手柄验收；图片采集完整仍须人工核模型、取景和视觉效果。新QA队列对写回执失败只重写原ack，不重放命令；历史失败证据保留。</p><p>来源按范围联合：原play因暂停输入EOF中止，保留false的7条已过检查及5图；续办38条检查/13图另存。原两张底座照片仅能证明屏幕活动流，已明确标出取景被遮挡；清晰背侧低位展示另补2图，不声称拍到dock液晶正面或大角差。首次补图的QA参数失败false/2条已过检查/0图也保留，新补验5条检查/2图独立引用它。联合完成不改写成原轮零失败。</p><nav><button data-filter="all" aria-pressed="true">全部</button><button data-filter="hardware">22机体</button><button data-filter="portable">双手托持</button><button data-filter="fullscreen">全屏与按键</button><button data-filter="hint">短提示</button><button data-filter="controls">原生控制</button><button data-filter="monitor">电脑显示器</button><button data-filter="dock">底座运行</button></nav><div class="grid">{''.join(figures)}</div><h2>原始来源</h2><p>runId：<code>{owner['runId']}</code><br>候选SHA256：<code>{digest}</code><br>{'三Minecraft进程正常退出，原生子进程审计无残留。'if closed else'关闭状态以同run的run-result.json为准。'}</p><ul>{links}</ul><script type="application/json" id="originals">{metadata}</script><script>for(const button of document.querySelectorAll('button[data-filter]'))button.onclick=()=>{{for(const b of document.querySelectorAll('button[data-filter]'))b.setAttribute('aria-pressed',String(b===button));for(const f of document.querySelectorAll('figure[data-group]'))f.hidden=button.dataset.filter!=='all'&&f.dataset.group!==button.dataset.filter;}};</script></main></html>''',encoding="utf-8")
    print(json.dumps({"gallery":str(output),"originals":len(rows),"bodies":len(bodies),"portable":len(portable),"normalMinecraftClosure":closed}))


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("--run",required=True,type=Path);p.add_argument("--build",required=True,type=Path);p.add_argument("--output",type=Path,default=REPO/"build/monitor-polish-review/index.html");a=p.parse_args();build(a.run,a.build,a.output)
