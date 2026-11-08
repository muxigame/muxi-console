"""Link untouched originals from this exact held-device refinement candidate and run."""
from __future__ import annotations
import argparse
import html
import json
import os
from pathlib import Path
from urllib.parse import quote
from PIL import Image
from gallery import REPO,run_root,sha


def build(run,report,output,require_live=False):
    root,owner=run_root(run);report=report.resolve(strict=True)
    if REPO/"build/qa"not in report.parents:raise ValueError("Use this repository's frozen QA report")
    inputs=json.loads(report.read_text(encoding="utf-8"));digest=owner["galleryProductSha256"]
    if inputs["product"]["sha256"]!=digest or sha(Path(inputs["product"]["path"]))!=digest:raise ValueError("Product differs from the actual owned run")
    paths=[root/"console-qa/handheld-model-refinement-result.json"]
    live=root/"console-qa/handheld-refinement-result.json"
    if live.exists():paths.append(live)
    elif require_live:raise ValueError("Real focused input and same-card dock result is required")
    continuation=root/"console-qa/handheld-refinement-continuation-result.json";recovered=False;continuation_result=None
    if continuation.exists():
        continuation_result=json.loads(continuation.read_text(encoding="utf-8"));base=json.loads(live.read_text(encoding="utf-8"))
        recovered=(base.get("scenarioComplete")is False and base.get("failure")=="QA command timed out: host/hand_key"and not base.get("cleanupFailure")and base.get("finalWorkers")=={"host":[],"guest":[]}and len(base.get("checks",[]))==77 and all(c["passed"]for c in base["checks"])and len(base["screenshots"])==20 and continuation_result.get("scenarioComplete")is True and continuation_result.get("originalScenarioComplete")is False and continuation_result.get("sourceResult")=={"path":str(live),"sha256":sha(live)}and continuation_result.get("gbaCard")==base["cards"]["gba"]and continuation_result.get("cleanupCheckpoint",{}).get("card")==base["cards"]["gba"]["instance"]and len(continuation_result.get("checks",[]))==16 and all(c["passed"]for c in continuation_result["checks"])and len(continuation_result["screenshots"])==2 and continuation_result.get("finalWorkers")=={"host":[],"guest":[]}and not continuation_result.get("cleanupFailure"))
        if not recovered:raise ValueError("Continuation must strictly close the retained exact original stage and same GBA identity")
        paths.append(continuation)
    rows=[];results=[]
    for path in paths:
        result=json.loads(path.read_text(encoding="utf-8"))
        if result.get("runId")!=owner["runId"]or result.get("productSha256")!=digest or(not result.get("scenarioComplete")and not(path==live and recovered)):raise ValueError("Incomplete or mixed-candidate original result")
        if path in(live,continuation)and result.get("modelResult")!={"path":str(paths[0]),"sha256":sha(paths[0])}:raise ValueError("Original model result reference changed")
        results.append((path,result))
        for record in result["screenshots"]:
            image=Path(record["path"]).absolute()
            if image.resolve(strict=True)!=image or image.parent!=root/"host/screenshots"or not record.get("nativeMinecraftFramebuffer")or sha(image)!=record["sha256"]:raise ValueError("Native original path/hash differs")
            with Image.open(image)as im:
                if im.format!="PNG"or im.size!=(record["width"],record["height"])or im.width<640 or im.height<360:raise ValueError("Native original resolution differs")
                im.verify()
            row=dict(record);name=image.stem;row["resultOrigin"]=path.name;row["category"]="model"if path==paths[0]else"dock"if"dock"in name else"input"if"focus"in name or"settings"in name else"pose";rows.append(row)
    if len({r["path"]for r in rows})!=len(rows):raise ValueError("One original must not count as several views")
    if require_live and len(rows)!=32:raise ValueError("Current refinement scope requires ten model originals and twenty-two actual live originals")
    model={r.get("cmd")for r in rows if r["category"]=="model"}
    if model!={3,4,5,9,14,15,16,17,18,19}:raise ValueError("All nine refined handhelds and VB need current originals")
    output=output.absolute()
    if output.resolve()!=output or REPO/"build"not in output.parents or output.suffix!=".html":raise ValueError("Gallery belongs under this repository's build directory")
    output.parent.mkdir(parents=True,exist_ok=True)
    def url(p):return quote(os.path.relpath(p,output.parent).replace(os.sep,"/"),safe="/:.")
    names={"twohands-idle":"双手托持 · 待机","main-right":"主手右手 · 副手持物","off-left":"副手左手 · 主手持物","main-left":"左惯用手 · 主手左手","off-right":"左惯用手 · 副手右手","twohands-leftdominant":"左惯用手 · 双手托持","focus-idle":"专注操作 · 无按键","focus-a-pressed":"A按下 · 天蓝反馈","focus-a-released":"A松开 · 反馈复原","focus-up-b":"方向↑与B同时按下","focus-functions":"真实功能胶囊键 / 肩键按下","key-settings":"真实键位设置 · 独立平台持久化"}
    def title(row):
        stem=Path(row["path"]).stem
        if row["category"]=="model":return row["platform"].upper()+" · 新薄机体与精细按钮 / 双手托持"
        if row["category"]=="dock":return "同一GBA与卡带 · 低位旋转 / 实时屏幕 · "+stem.rsplit("-",1)[-1]
        tail=stem[len("hand-"+row["platform"]+"-"):];return row["platform"].upper()+" · "+names.get(tail,tail)
    esc=html.escape;cards=[]
    for row in rows:
        caption=title(row);src=url(row["path"]);r=row.get("render",{})
        meta=json.dumps(r,ensure_ascii=False)if r else"对应普通UI或实际底座会话，见原始JSON"
        cards.append(f'<figure data-group="{row["category"]}"><a href="{src}"><img src="{src}" loading="lazy" alt="{esc(caption)}"></a><figcaption><b>{esc(caption)}</b><span>{row["width"]} × {row["height"]} · Minecraft原始帧</span><details><summary>实际绘制与原图来源</summary><pre>{esc(meta)}</pre><code>{esc(row["resultOrigin"])}<br>{esc(Path(row["path"]).name)}<br>{row["sha256"]}</code></details></figcaption></figure>')
    closure=root/"run-result.json";closed=False
    if closure.exists():
        c=json.loads(closure.read_text(encoding="utf-8"));closed=c.get("runId")==owner["runId"]and c.get("exitCodes")=={"server":0,"host":0,"guest":0}and c.get("nativeChildAudit",{}).get("normalChildrenExit",False)
    links=''.join(f'<li><a href="{url(p)}">{p.name}</a> · <code>{sha(p)}</code></li>'for p,_ in results)
    note_path=root/"console-qa/handheld-reply-observation.json"
    if note_path.exists():
        note=json.loads(note_path.read_text(encoding="utf-8"))
        if note.get("runId")!=owner["runId"]or note.get("productSha256")!=digest or note.get("continuation")!={"path":str(continuation),"sha256":sha(continuation)}:raise ValueError("Reply observation must refer to the exact retained continuation")
        links+=f'<li><a href="{url(note_path)}">原回执故障与共享读限制</a> · <code>{sha(note_path)}</code>；本次续办无观察超时，独立Windows替换压力测试仍有AccessDenied，未宣称彻底消除所有竞争。</li>'
    recovery_text=""
    if recovered:
        observation=continuation_result["originalReplyFailure"]
        recovery_text=f'<p><b>原轮失败与独立续办：</b>原live结果仍为false：GBA功能键松开时，QA回执原子替换遇NTFS访问冲突；其77项实际断言及20张原图保留。<a href="{url(observation["path"])}">原始boot.log第{observation["line"]}行</a>记录该失败。没有重启Minecraft或改写原结果；独立续办从原GBA卡带的真实清理存档恢复，以16项实际检查补齐GBA模式切换、操作隔离、F8/设置释放、正常保存及同卡底座/取回，并新增2张底座原图。完整范围由两份结果联合证明，不宣称原轮零失败。</p>'
    metadata=json.dumps(rows,ensure_ascii=False).replace("<","\\u003c")
    output.write_text(f'''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>木犀掌机 · 手持与按键反馈实图</title><style>body{{margin:0;background:#edf1ee;color:#193a40;font:16px/1.65 system-ui,sans-serif}}main{{max-width:1320px;margin:auto;padding:32px 24px}}h1{{font-size:32px;line-height:1.3}}p{{max-width:1040px}}nav{{display:flex;flex-wrap:wrap;gap:10px;margin:24px 0}}button{{border:1px solid #bbccc6;background:white;color:#244d53;border-radius:24px;padding:9px 16px;cursor:pointer}}button[aria-pressed=true]{{background:#174f5b;color:white}}.grid{{display:grid;grid-template-columns:repeat(auto-fit,minmax(380px,1fr));gap:22px}}figure{{margin:0;border:1px solid #c8d3cd;border-radius:12px;overflow:hidden;background:white}}img{{display:block;width:100%;height:auto}}figcaption{{padding:14px 17px}}span{{display:block;font-size:13px;color:#62756e}}code,pre{{font-size:11px;overflow-wrap:anywhere;white-space:pre-wrap}}details{{margin-top:10px}}a{{color:#156f83}}li{{margin:8px 0}}@media(max-width:460px){{main{{padding:20px 12px}}.grid{{grid-template-columns:1fr}}}}</style><main><h1>掌机 · 手臂、薄机身与按键反馈</h1><p>九款掌机与VB均使用本轮新候选实拍。双手托持、主副手与左惯用手镜像，以及GB/GBA的真实绑定与按键反馈分别保留未经编辑的Minecraft原图；点击可看原始分辨率。画面使用本仓原创诊断，不是第三方游戏ROM。</p><p>专注模式的键盘回调经实际平台配置映射、服务端控制席及运行核心；高光只读取同一份实际输入。正常世界行走不向游戏发送按键，F8释放、Esc保存，设置界面会清空按住状态。原生回调和隐藏窗口授权前端不是实体键鼠或实体手柄验收；自动采集完整也不能代替逐图人工外观审核。大屏本轮保持原样。</p>{recovery_text}<nav><button data-filter="all" aria-pressed="true">全部</button><button data-filter="model">九掌机与VB</button><button data-filter="pose">主副手与双手</button><button data-filter="input">键位与按下反馈</button><button data-filter="dock">同卡底座</button></nav><div class="grid">{''.join(cards)}</div><h2>原始来源</h2><p>runId：<code>{owner['runId']}</code><br>候选SHA256：<code>{digest}</code><br>{'三Minecraft进程正常退出，原生子进程审计无残留。'if closed else'关闭状态以同run的run-result.json为准。'}</p><ul>{links}</ul><script type="application/json" id="originals">{metadata}</script><script>for(const button of document.querySelectorAll('button[data-filter]'))button.onclick=()=>{{for(const b of document.querySelectorAll('button[data-filter]'))b.setAttribute('aria-pressed',String(b===button));for(const f of document.querySelectorAll('figure[data-group]'))f.hidden=button.dataset.filter!=='all'&&f.dataset.group!==button.dataset.filter;}};</script></main></html>''',encoding="utf-8")
    print(json.dumps({"gallery":str(output),"originals":len(rows),"currentCandidateOnly":True,"normalMinecraftClosure":closed}))


if __name__=="__main__":
    p=argparse.ArgumentParser(description=__doc__);p.add_argument("--run",type=Path,required=True);p.add_argument("--build",type=Path,required=True);p.add_argument("--output",type=Path,default=REPO/"build/held-feel-review/index.html");p.add_argument("--require-live",action="store_true");a=p.parse_args();build(a.run,a.build,a.output,a.require_live)
