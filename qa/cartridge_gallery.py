"""Verify immutable cartridge results and link original frames; no MC commands or image writes."""
from __future__ import annotations

import argparse
import html
import json
import os
from pathlib import Path
from urllib.parse import quote

from PIL import Image
from gallery import REPO, run_root, sha


# Exact preserved scopes: the original two false results are never promoted to true.
PINS = {
    "cartridge-inventory": "e9323121930be66d8648de0c59846376d5728427d53c25ba386faf64e4fbb693",
    "cartridge-inventory-continuation": "7a43f107f935e739939ccaa09d0a0e1ba904dc6cd362c8f3e49c66c76498bd58",
    "cartridge-cold": "8496ae51b2d8bb058d49f51d39ebc56055e2d9135d93f3263a04510747461402",
    "cartridge-pickup": "6bcf052590fd9293d315e6e47854911bc5dc34a98f1376a24cf9a2e73ef5c564",
    "cartridge-cold-continuation": "de791a6a4fa48175790321ebf5af36b15a8b281817e54989b893ba37d2c82278",
    "cartridge-hardware": "0a2848405b42b37d5d12fd905a7d5b9b250742c19d486ee1e04e6bb9dbf892ed",
}
DEPENDENCIES = {
    "cartridge-inventory": (),
    "cartridge-inventory-continuation": ("cartridge-inventory",),
    "cartridge-cold": ("cartridge-inventory", "cartridge-inventory-continuation"),
    "cartridge-pickup": ("cartridge-cold",),
    "cartridge-cold-continuation": ("cartridge-inventory", "cartridge-inventory-continuation", "cartridge-cold", "cartridge-pickup"),
    "cartridge-hardware": ("cartridge-inventory", "cartridge-inventory-continuation", "cartridge-cold", "cartridge-pickup", "cartridge-cold-continuation"),
}
# filename stem, source, actor, actual card, gallery category, caption
PHOTOS = (
    ("inventory-inserted-gb-in-gba", "cartridge-inventory", "host", "gb", "inventory", "原生背包 · 完整 GB 卡装入 GBA 外壳"),
    ("inventory-resumed-after-safe-extraction", "cartridge-inventory-continuation", "host", "gb", "inventory", "独立续办 · 同一原卡保存完成后安全取出"),
    ("loading-world-lcd", "cartridge-cold-continuation", "guest", "large_gba", "loading", "实际冷下载 · 手持 LCD 进度"),
    ("loading-model", "cartridge-cold-continuation", "guest", "large_gba", "loading", "同一冷下载 · 带机身全屏进度"),
    ("loading-screen", "cartridge-cold-continuation", "guest", "large_gba", "loading", "同一冷下载 · 纯屏幕进度"),
    ("gameplay-after-loading", "cartridge-cold-continuation", "guest", "large_gba", "handheld", "首个真实画面后 · 加载层消失"),
    ("cached-held-with-card", "cartridge-cold-continuation", "guest", "large_gba", "handheld", "同一卡带 · 缓存复用和真实状态恢复"),
    ("inserted-model", "cartridge-hardware", "guest", "large_gba", "handheld", "完整机身全屏 · 实际插入的卡带"),
    ("inserted-held", "cartridge-hardware", "guest", "large_gba", "handheld", "世界手持 · 实际插入的卡带"),
    ("inserted-inventory-icon", "cartridge-hardware", "guest", "large_gba", "inventory", "原生物品栏 · 插卡掌机图标"),
    ("dock-angle-a", "cartridge-hardware", "guest", "large_gba", "dock", "同一卡带底座 · 自然旋转时刻 A"),
    ("dock-angle-b", "cartridge-hardware", "guest", "large_gba", "dock", "同一卡带底座 · 自然旋转时刻 B"),
    ("dock-safe-leave-panel", "cartridge-hardware", "guest", "large_gba", "dock", "真实设备管理页 · 保存并离开游戏"),
    ("desktop-nes-inserted", "cartridge-hardware", "host", "nes", "desktop", "NES · 正常刻录原卡和实际插槽"),
    ("desktop-pce-inserted", "cartridge-hardware", "host", "pce", "desktop", "PCE · 正常刻录 HuCard 和实际插槽"),
)


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read(path):
    require(path.resolve(strict=True) == path, "Canonical source path required: " + str(path))
    return json.loads(path.read_text(encoding="utf-8"))


def whole_card(item, card):
    return item.get("item") == "muxi_console:cartridge" and all(item.get(k) == card[k] for k in ("instance", "hash", "nbt", "count"))


def validate_sources(root, owner, inputs):
    results = {name: read(root / "console-qa" / (name + "-result.json")) for name in PINS}
    cards = results["cartridge-inventory"]["cards"]
    pins = {"gb": "gbDiagnosticRom", "legacy_gb": "gbDiagnosticRom", "gba": "gbaDiagnosticRom", "large_gba": "largeGbaDiagnosticRom", "nes": "diagnosticRom", "pce": "pceDiagnosticRom"}
    require(set(cards) == set(pins) and len({c["instance"] for c in cards.values()}) == 6, "Six original distinct card identities required")
    for key, card in cards.items():
        require(card["item"] == "muxi_console:cartridge" and card["count"] == 1 and card["hash"] == inputs[pins[key]]["sha256"], "Issued card differs from diagnostic pin: " + key)
    for name, result in results.items():
        path = root / "console-qa" / (name + "-result.json")
        require(sha(path) == PINS[name], "Immutable native JSON changed: " + name)
        require(result["runId"] == owner["runId"] and result["productSha256"] == owner["galleryProductSha256"], "Native scope differs from run/product: " + name)
        require(result["cards"] == cards and result.get("physicalOSInput") is False and not result.get("cleanupFailure"), "Mixed cards, physical-input claim or failed cleanup: " + name)
        final = result.get("finalClients", {})
        # The standalone pickup proof has no worker lifecycle; driven phases do.
        require(name == "cartridge-pickup" or set(final) == {"host", "guest"}, "Missing phase cleanup evidence")
        require(all(not c["workers"] and c["forcedWorkerExits"] == c["failedWorkerExits"] == 0 for c in final.values()), "Unsafe phase worker lifecycle: " + name)
        refs = {k for k, v in result.items() if isinstance(v, dict) and {"path", "sha256"} <= v.keys() and k.startswith("cartridge-")}
        require(refs == set(DEPENDENCIES[name]), "Missing or extra continuation reference: " + name)
        for dependency in DEPENDENCIES[name]:
            require(result[dependency] == {"path": str(root / "console-qa" / (dependency + "-result.json")), "sha256": PINS[dependency]}, "Continuation reference changed: " + name)
        if name == "cartridge-inventory":
            require(result["scenarioComplete"] is False and result["failure"] == "full survival inventory rejects shortcut ejection and preserves its complete card" and len(result["checks"]) == 17 and all(c["passed"] for c in result["checks"][:16]) and result["checks"][-1]["passed"] is False and len(result["screenshots"]) == 1, "Original inventory false must retain its exact limited scope")
        elif name == "cartridge-cold":
            require(result["scenarioComplete"] is False and result["failure"].startswith("real dropped card pickup by the second network player:") and len(result["checks"]) == 1 and result["checks"][0]["passed"] is True and not result["screenshots"], "Original cold false proves only the clean-cache baseline")
        else:
            require(result["scenarioComplete"] is True and result["checks"] and all(c["passed"] is True for c in result["checks"]), "Incomplete continuation/hardware scope: " + name)
    require(results["cartridge-inventory-continuation"]["scope"] == "Keep the original false and its first sixteen passes/one photo; finish full-inventory, explicit offhand shortcut and actual saving guard on the same physical cards.", "Inventory continuation scope changed")
    require(results["cartridge-cold-continuation"]["scope"] == "Original drop is not replayed: same actual world entity was picked up normally using its observed position; finish all cold-download requirements on that complete original card.", "Cold continuation scope changed")
    pickup = results["cartridge-pickup"]
    require(pickup["originalDrop"]["instance"] == cards["large_gba"]["instance"] and pickup["originalDrop"]["hash"] == cards["large_gba"]["hash"] and any(whole_card(i, cards["large_gba"]) for i in pickup["guestAfterPickup"]["items"]), "Pickup must preserve the originally dropped whole card")
    return results, cards


def validate_photo(row, stem, role, card, group):
    before, after = row["before"], row["after"]
    life_before, life_after = row["lifecycleBefore"], row["lifecycleAfter"]
    require(row["role"] == role and life_before["room"] == life_after["room"] and life_before["epoch"] == life_after["epoch"], "Photo changes actor/session: " + stem)
    if group == "loading":
        for state in (before, after):
            p = state["progress"]
            require(p["card"] == card["instance"] and p["stage"] == "downloading" and p["loading"] is True and 0 < p["bytes"] < p["total"] == card["bytes"] and p["percent"] == p["bytes"] * 100 // p["total"], "Photo is outside its actual transfer: " + stem)
        require(life_before["room"] == "handheld/" + card["instance"] and after["progress"]["bytes"] >= before["progress"]["bytes"], "Transfer identity/order differs")
        if stem == "loading-world-lcd":
            require(not row["screen"] and before["lcdLoading"]["card"] == card["instance"] and before["lcdLoading"]["stage"] == "downloading", "World loading photo lacks its LCD draw")
        else:
            require(row["presentation"]["mode"] == stem.removeprefix("loading-") and row["presentation"]["progress"]["loading"] is True, "Fullscreen loading mode differs")
    elif row["scope"] == "same-live-worker":
        require(life_before["workers"] and life_before["workers"] == life_after["workers"], "Gameplay photo changes worker: " + stem)
        for state, life in ((before, life_before), (after, life_after)):
            require(life["ready"] is True and life["workerRunning"] is True and life["frames"] > 0 and state["progress"]["card"] == card["instance"] and state["progress"]["stage"] == "complete" and state["progress"]["loading"] is False, "Gameplay photo lacks the same live complete card: " + stem)
    else:
        require(group == "inventory" and not life_before["room"] and not life_after["room"], "Unexpected non-live screenshot scope")
        for state in (before, after):
            contained = [i for i in state["items"] if whole_card(i, card) or whole_card(i.get("inserted", {}), card)]
            require(len(contained) == 1, "Inventory photo lacks exactly one complete original card")
    # Recent draw audit does not itself prove current session or loading state.
    if stem != "inventory-resumed-after-safe-extraction":
        require(before["insertedMediaAudit"]["card"] == card["instance"], "Media draw belongs to another card: " + stem)


def closure_state(root, owner):
    path = root / "run-result.json"
    if not path.exists():
        return {"closed": False, "reason": "运行尚未正常关闭；等待同 run 的退出回执。"}
    value = read(path)
    require(value["runId"] == owner["runId"] and value["instanceRoot"] == str(root), "Closure belongs to another run")
    audit = value.get("nativeChildAudit", {})
    closed = value.get("normalStopOnly") is True and value.get("exitCodes") == {"server": 0, "host": 0, "guest": 0} and value.get("normalJavaExit") is True and value.get("normalExit") is True and not value.get("normalStopBlocked") and audit.get("complete") is True and audit.get("normalChildrenExit") is True and all(audit.get(k) == [] for k in ("remaining", "unknown", "errors", "actions"))
    return {"path": str(path), "sha256": sha(path), "closed": closed, "result": value, "reason": "三 Minecraft 进程 exit 0；原生子进程审计完整且为空。" if closed else "退出回执尚未满足全部正常关闭条件，不能计入闭合。"}


def build(run, report, output):
    root, owner = run_root(run)
    report = report.absolute()
    inputs = read(report)
    require(REPO / "build/qa" in report.parents and inputs["product"]["sha256"] == owner["galleryProductSha256"] and sha(Path(inputs["product"]["path"])) == owner["galleryProductSha256"], "Exact current product and QA build required")
    require(set(inputs["artifacts"]) == {"server", "client"}, "Both QA artifact pins required")
    for role, pin in inputs["artifacts"].items():
        require(sha(Path(pin["path"])) == pin["sha256"] and any(i["scope"] == role and i["file"] == Path(pin["path"]).name and i["sha256"] == pin["sha256"] for i in owner["inputArtifacts"]), "QA artifact differs from actual run pin")
    results, cards = validate_sources(root, owner, inputs)
    by_name = {}
    for source, value in results.items():
        for record in value["screenshots"]:
            name = Path(record["path"]).name
            require(name not in by_name, "Repeated original cannot count as another photo")
            by_name[name] = (source, record)
    require(set(by_name) == {"cartridge-" + p[0] + ".png" for p in PHOTOS}, "All fifteen current originals required exactly once")
    rows = []
    for stem, source, role, card, group, caption in PHOTOS:
        actual_source, record = by_name["cartridge-" + stem + ".png"]
        p = Path(record["path"])
        require(actual_source == source and p.is_absolute() and p.resolve(strict=True) == p and p.parent == root / role / "screenshots" and record["nativeMinecraftFramebuffer"] is True and sha(p) == record["sha256"], "Original photo source/path/hash differs: " + stem)
        with Image.open(p) as image:
            require(image.format == "PNG" and image.size == (record["width"], record["height"]) and image.width >= 640 and image.height >= 360, "Original framebuffer dimensions differ")
            image.verify()
        with Image.open(p) as image:
            image.load()
        validate_photo(record, stem, role, cards[card], group)
        rows.append(dict(record, resultOrigin=source + "-result.json", cardName=card, group=group, caption=caption))
    require(len({r["sha256"] for r in rows}) == len(rows), "Identical PNG bytes cannot count as independent photos")
    closure = closure_state(root, owner)
    output = output.absolute()
    require(output.resolve() == output and REPO / "build" in output.parents and output.suffix == ".html", "Output must be HTML beneath this repository's build directory")
    output.parent.mkdir(parents=True, exist_ok=True)
    esc = html.escape

    def url(path):
        return quote(os.path.relpath(path, output.parent).replace(os.sep, "/"), safe="/:.")

    figures = []
    for row in rows:
        src = url(row["path"])
        percentage = ""
        if row["group"] == "loading":
            a, b = row["before"]["progress"], row["after"]["progress"]
            render = (row.get("presentation") or {}).get("progress", row["before"].get("lcdLoading", {}))
            percentage = f'<small>真实快照：取图前 {a["percent"]}%（{a["bytes"]:,} B），取图后 {b["percent"]}%（{b["bytes"]:,} B）；最近绘制采样 {render["percent"]}%。总计 {a["total"]:,} B。</small>'
        detail = {k: row[k] for k in ("resultOrigin", "role", "cardName", "scope", "lifecycleBefore", "lifecycleAfter", "before", "after", "presentation")}
        figures.append(f'<figure data-group="{row["group"]}"><a href="{src}"><img src="{src}" loading="lazy" alt="{esc(row["caption"])}"></a><figcaption><b>{esc(row["caption"])}</b><small>{row["role"]} · {row["width"]} × {row["height"]} · Minecraft 原始帧</small>{percentage}<details><summary>卡带、真实进度、会话和原始来源</summary><pre>{esc(json.dumps(detail, ensure_ascii=False, indent=2))}</pre><code>{row["sha256"]}</code></details></figcaption></figure>')
    notes = {
        "cartridge-inventory": "原范围 false 保留：前 16 项及 1 图有效；满背包步骤误要求没有会话，GUI 关闭后正常自动开局触发忙保护。续办在同卡正常保存后补完满包拒绝、另一手快插及保存忙保护。",
        "cartridge-cold": "原范围 false 保留：仅无缓存基线 1 项有效，无图；固定站位未抵达实际抛落位置，超时。之后正常拾取原实体，未重新发卡或重演抛卡。",
    }
    links = []
    for name, value in results.items():
        passed = sum(c["passed"] is True for c in value["checks"])
        note = notes.get(name, "本独立范围 true；卡带集和所需原始 JSON 的 SHA 引用均核对。")
        links.append(f'<li><a href="{url(root / "console-qa" / (name + "-result.json"))}">{name}-result.json</a> · {passed}/{len(value["checks"])} 项 / {len(value["screenshots"])} 图<br>{esc(note)}<br><code>{PINS[name]}</code></li>')
    final = results["cartridge-hardware"]["finalClients"]
    manifest = {"runId": owner["runId"], "productSha256": owner["galleryProductSha256"], "qaBuild": {"path": str(report), "sha256": sha(report)}, "sources": PINS, "originals": rows, "closure": closure, "physicalOSInput": False, "visualAcceptance": False}
    closure_link = f'<a href="{url(closure["path"])}">正常关闭原始回执</a> · <code>{closure["sha256"]}</code>' if "path" in closure else "退出回执尚未生成"
    page = '''<!doctype html><html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>木犀游戏机 · 整卡与真实加载图库</title><style>
body{margin:0;background:#edf1ee;color:#193a40;font:16px/1.65 system-ui,sans-serif}main{max-width:1320px;margin:auto;padding:32px 24px}h1{line-height:1.3}.status{padding:14px 18px;border-left:4px solid #d49f32;background:#fff8e8}nav{display:flex;flex-wrap:wrap;gap:10px;margin:24px 0}button{border:1px solid #bacdc8;border-radius:24px;background:white;padding:9px 16px;color:#244d53;cursor:pointer}button[aria-pressed=true]{background:#174f5b;color:white}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(380px,1fr));gap:22px}figure{margin:0;border:1px solid #c8d3cd;border-radius:12px;overflow:hidden;background:white}figure[hidden]{display:none}img{display:block;width:100%;height:auto}figcaption{padding:14px 17px}small{display:block;color:#62756e}pre,code{font-size:11px;white-space:pre-wrap;overflow-wrap:anywhere}a{color:#156f83}li{margin-bottom:16px}@media(max-width:460px){main{padding:20px 12px}.grid{grid-template-columns:1fr}}</style></head><body><main>
<h1>完整实体卡带 · 真实加载 · 插卡设备</h1>
<p>15 张原图直接链接同一候选、同一真实双客户端运行的 host / guest 文件；不复制、裁切、编辑或用旧图替代。六份原始 JSON 和每张原图均核对 SHA，PNG 完整解码及尺寸通过。卡带均由普通上传声明和刻录发行，六个原始 UUID 与完整卡数据在各阶段保持一致。</p>
<div class="status"><b>分段联合完成，保留两份原范围 false。</b><p>背包原记录仅前 16 项及 1 图有效，续办补完剩余 8 项及 1 图；冷加载原记录仅无缓存基线 1 项有效，实际原卡拾取 3 项与冷加载续办 22 项及 5 图完成其余范围；硬件范围 32 项、8 图完成。不是原记录零失败，也没有把 false 改写为 true。</p></div>
<p>8 MiB 卡由本仓自写 GBA 诊断保留原 64 KiB 字节、只补 FF 派生，仅作测试，不进入产品。三张加载图展示真实字节快照；取图前、绘制采样和取图后发生在不同时刻，百分比会正常推进。完成首个真实画面后加载层消失，缓存恢复不新增 ROM 分块。</p>
<p>原生背包操作、玩家转交、掌机、底座和桌机的完整卡数据及正常保存已由各范围记录核验。图标尺寸很小，不声称能看清卡标签。截图中的近期 LCD 审计可能保留上一阶段采样，不能当作当前仍在加载；当前状态以同卡 progress 和会话前后快照为准。普通 GUI / 网络回调不等于实体 OS 键鼠、实体手柄、音响听感或全部游戏兼容验收。图库不修改原结果的 visualAcceptance 字段，视觉结论由人工另行审核。</p>
'''
    page += f'<p>运行关闭：<b>closed={str(closure["closed"]).lower()}</b> · {esc(closure["reason"])}<br>{closure_link}<br>各范围累计 worker 正常退出：host {final["host"]["normalWorkerExits"]}、guest {final["guest"]["normalWorkerExits"]}；两端当前 workers 为空，forced / failed 为 0。</p>'
    filters = (("all", "全部 15 图"), ("loading", "真实加载"), ("inventory", "背包装取"), ("handheld", "掌机"), ("dock", "底座"), ("desktop", "桌机插槽"))
    page += '<nav aria-label="图库分类">' + ''.join(f'<button data-filter="{key}" aria-pressed="{str(key == "all").lower()}">{label}</button>' for key, label in filters) + '</nav><div class="grid">' + ''.join(figures) + '</div>'
    page += f'<h2>原始来源与联合范围</h2><p>runId：<code>{owner["runId"]}</code><br>产品 SHA：<code>{owner["galleryProductSha256"]}</code><br><a href="{url(report)}">精确 QA 构建</a> · <code>{sha(report)}</code></p><ul>{"".join(links)}</ul>'
    page += '<script type="application/json" id="gallery-manifest">' + json.dumps(manifest, ensure_ascii=False).replace("<", "\\u003c") + '</script><script>for(const b of document.querySelectorAll("button[data-filter]"))b.onclick=()=>{for(const x of document.querySelectorAll("button[data-filter]"))x.setAttribute("aria-pressed",String(x===b));for(const f of document.querySelectorAll("figure[data-group]"))f.hidden=b.dataset.filter!=="all"&&f.dataset.group!==b.dataset.filter;};</script></main></body></html>'
    require(all(sha(root / "console-qa" / (name + "-result.json")) == digest for name, digest in PINS.items()), "Native source changed during preparation")
    require(all(sha(Path(row["path"])) == row["sha256"] for row in rows), "Original image changed during preparation")
    output.write_text(page, encoding="utf-8")
    summary = {"gallery": str(output), "originals": len(rows), "roles": {role: sum(r["role"] == role for r in rows) for role in ("host", "guest")}, "uniquePngSha256": len({r["sha256"] for r in rows}), "sources": PINS, "normalMinecraftClosure": closure["closed"]}
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    return summary


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--run", required=True, type=Path)
    parser.add_argument("--build", required=True, type=Path)
    parser.add_argument("--output", type=Path, default=REPO / "build/cartridge-review/index.html")
    arguments = parser.parse_args()
    build(arguments.run, arguments.build, arguments.output)
