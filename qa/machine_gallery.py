"""Link eight current original frames after both runs close; preserve source failures and images."""
from __future__ import annotations
import argparse
import html
import json
import os
from pathlib import Path
from urllib.parse import quote
from PIL import Image
from gallery import REPO, run_root, sha

PREVIEW03 = "f9e34028b95bbf916a34c0f29115492c60ae501d6a2436abc8f9a0385c2475c4"
REJECTED_ICONS_SHA = "2e858af2bdc1faa7bb0b0557bdf15b1a18cfb139ab09334c63de2ff57443c45e"
SOURCE_PINS = {"case": "8660c33fb9a3342e508ab0674616373e2a083b65566ed119e2a4ad6c1f84380d", "machine": "31b47132f6b5cfec225012f2751f3c1f348a41f4957e2ffe93e34e5b8afc3e99", "machine-continuation": "462f821e8bfbacc276d0ee53d288bc0e2560bb4f0f819a7fb6259d1f3628d29b"}
TITLES = {"machine-fixed-original-world-lcd": "原机器 A · 世界手持 LCD 恢复红色进度", "machine-fixed-original-model": "原机器 A · 完整机身 MODEL 与真实 LCD", "case-thin-existing-inventory": "原卡册 · 背包图标", "case-thin-placed-folio": "薄卡册 · 世界摆放", "case-thin-native-container": "原卡册 · 原生 27 格界面", "case-thin-returned-inventory": "生存拆放后 · 完整原卡册返回", "icons-hardware": "28 项设备 · 原生 16px 图标放大审查", "icons-media": "23 项媒介 · 未发行的视觉预览"}

def need(ok, message):
    if not ok: raise ValueError(message)

def read(path):
    need(path.resolve(strict=True) == path, "Canonical original path required")
    return json.loads(path.read_text(encoding="utf-8"))

def ref(path): return {"path": str(path), "sha256": sha(path)}

def closed(root, owner):
    path = root / "run-result.json"; value = read(path); audit = value.get("nativeChildAudit", {})
    need(value["runId"] == owner["runId"] and value["instanceRoot"] == str(root) and value.get("normalStopOnly") is True and value.get("normalJavaExit") is True and value.get("normalExit") is True and value.get("exitCodes") == {"server": 0, "host": 0, "guest": 0} and not value.get("normalStopBlocked") and audit.get("complete") is True and audit.get("normalChildrenExit") is True and all(audit.get(k) == [] for k in ("remaining", "unknown", "errors", "actions")), "Both exact runs must be normally closed with empty native audits")
    return ref(path)

def result(root, owner, name, passing=True):
    path = root / "console-qa" / (name + "-result.json"); value = read(path)
    need(value["runId"] == owner["runId"] and value["productSha256"] == owner["galleryProductSha256"] and value["scenarioComplete"] is passing and value.get("physicalOSInput") is False and not value.get("cleanupFailure") and value["checks"] and all(c["passed"] is True for c in value["checks"]), "Foreign or incomplete scope: " + name)
    need(all(not c["workers"] and c["forcedWorkerExits"] == c["failedWorkerExits"] == 0 for c in value.get("finalClients", {}).values()), "Unsafe worker lifecycle: " + name)
    return path, value

def cards_in(world, cards):
    for card in cards.values():
        rows = [i for i in world["items"] if i.get("card") == card["instance"]]
        need(len(rows) == 1 and rows[0]["content"] == card["hash"] and rows[0].get("insertedNbt", rows[0]["nbt"]) == card["nbt"] and not any(i.get("card") == card["instance"] for i in world["drops"]), "Copied world must retain each complete original card exactly once")

def saves(world):
    result={(s["machine"], s["content"]): (s["generation"], s["hash"], s["diskSha256"]) for s in world["saved"]}
    need(len(result)==len(world["saved"]) and all(s["hash"]==s["diskSha256"] for s in world["saved"]),"Machine/game keys must be unique and match actual disk bytes")
    return result

def build(source_run, run, report, output, rejected_run=None):
    source, old_owner = run_root(source_run); current, owner = run_root(run)
    digest=owner["galleryProductSha256"]
    need(source != current and owner["runId"] != old_owner["runId"], "Separate exact source/current candidates required")
    closures = [closed(source, old_owner), closed(current, owner)]
    report = report.absolute(); qa = read(report)
    need(REPO / "build/qa" in report.parents and qa["product"]["sha256"] == digest and sha(Path(qa["product"]["path"])) == digest, "Exact current QA/product required")
    for role, pin in qa["artifacts"].items():
        need(sha(Path(pin["path"])) == pin["sha256"] and any(i["scope"] == role and i["file"] == Path(pin["path"]).name and i["sha256"] == pin["sha256"] for i in owner["inputArtifacts"]), "QA artifact differs from current run")
    old = {name: result(source, old_owner, name, name != "machine") for name in SOURCE_PINS}
    for name, (path, value) in old.items():
        count, images = {"case": (35, 4), "machine": (19, 2), "machine-continuation": (57, 5)}[name]
        need(sha(path) == SOURCE_PINS[name] and len(value["checks"]) == count and len(value["screenshots"]) == images, "Preserved source scope changed")
    case, failed, continued = (old[k][1] for k in ("case", "machine", "machine-continuation")); cards = case["cards"]
    need(len(cards) == len({c["instance"] for c in cards.values()}) == 4 and failed["cards"] == continued["cards"] == cards and failed["failure"] == "java.lang.IllegalStateException: java.lang.IllegalStateException: Actual vanilla inventory screen required" and failed["case"] == continued["case"] == ref(old["case"][0]) and continued["machine"] == ref(old["machine"][0]), "Original false and same-card continuation chain changed")
    a,b,desk=(continued[k] for k in ("machineA","machineB","desktopOwner")); machine_ids={a["machine"],b["machine"],desk["machine"]}
    keys={(a["machine"],a["content"]),(a["machine"],cards["gb"]["hash"]),(b["machine"],b["content"]),(desk["machine"],desk["content"])}
    need(len(machine_ids)==3 and len(keys)==4 and a["content"]==b["content"]==cards["gba_a"]["hash"]==cards["gba_b"]["hash"] and desk["content"]==cards["nes"]["hash"],"Source must preserve three machines and four exact machine/game keys")
    now = {name: result(current, owner, name) for name in ("machine-visual", "case-visual", "icons")}
    visual, box, icons = (now[k][1] for k in ("machine-visual", "case-visual", "icons"))
    paths = [source / "local-mc-owner.json", source / "run-result.json", *(old[k][0] for k in ("case", "machine", "machine-continuation"))]
    need(visual["sources"] == {p.name: ref(p) for p in paths} and visual["sourceRun"] == {"path": str(source), "runId": old_owner["runId"], "productSha256": old_owner["galleryProductSha256"]} and visual["card"] == cards["gba_a"] and visual["saveOwner"] == continued["machineA"], "Current visual scope must reference the exact source world/card/machine")
    need(saves(visual["copiedWorldBefore"]) == saves(continued["finalWorld"]) and saves(box["machineBefore"]) == saves(visual["finalWorld"]) and saves(box["machineAfter"]) == saves(box["machineBefore"]), "Copied checkpoint lineage or case follow-up changed")
    for world in (continued["finalWorld"], visual["copiedWorldBefore"], visual["finalWorld"], box["machineBefore"], box["machineAfter"]):
        cards_in(world,cards); need(set(saves(world))==keys and {i["machine"] for i in world["items"] if "machine" in i}|{i["machineRead"] for i in world["devices"] if i.get("machineRead")}==machine_ids,"Each copied stage must preserve the three original machines and four save keys")
    comparison_path = Path(qa["product"]["path"]).parent / "compared-to-machine01.json"; comparison = read(comparison_path)
    changes = comparison["changed"]; changed_classes = {n for n in changes if n.endswith(".class")}
    need(comparison["baselineSha256"] == old_owner["galleryProductSha256"] and comparison["candidateSha256"] == digest and not comparison["added"] and not comparison["removed"] and changed_classes == {"net/muxigame/console/client/HandheldRenderer.class", "net/muxigame/console/mc/CartridgeCaseBlock.class"} and len(changes) == 79 and sum(n.startswith("assets/") for n in changes) == 77, "Inherited storage/container scope differs from root's exact build comparison")
    rejected=[]
    if rejected_run is not None:
        rejected_root,rejected_owner=run_root(rejected_run)
        need(rejected_root not in (source,current) and rejected_owner["galleryProductSha256"]==PREVIEW03,"Rejected preview must be its separate exact candidate03 run")
        closures.append(closed(rejected_root,rejected_owner)); rejected.append(result(rejected_root,rejected_owner,"icons"));need(sha(rejected[0][0])==REJECTED_ICONS_SHA and len(rejected[0][1]["checks"])==12 and len(rejected[0][1]["screenshots"])==2 and rejected[0][1]["previewCount"]==51,"Preserve exact true-data but visually rejected preview originals")
    sources = [*old.values(), *now.values(), *rejected]; photos = []; seen_paths = set()
    for path, value in sources:
        for record in value["screenshots"]:
            p = Path(record["path"]); root = path.parent.parent
            need(record["role"] in ("host", "guest") and p.resolve(strict=True) == p and p.parent == root / record["role"] / "screenshots" and record["nativeMinecraftFramebuffer"] is True and sha(p) == record["sha256"] and str(p) not in seen_paths, "Original PNG identity, origin or uniqueness differs")
            with Image.open(p) as image:
                need(image.format == "PNG" and image.size == (record["width"], record["height"]) and image.width >= 640 and image.height >= 360, "Original PNG size differs"); image.verify()
            with Image.open(p) as image: image.load()
            seen_paths.add(str(p)); photos.append(dict(record, resultOrigin=path.name, historical=root != current))
    fresh = [p for p in photos if not p["historical"]]
    need(len(photos) == 19+2*len(rejected) and len(fresh) == len({p["sha256"] for p in fresh}) == 8 and {Path(p["path"]).stem for p in fresh} == set(TITLES) and [len(now[k][1]["screenshots"]) for k in now] == [2, 4, 2], "Eight unique current originals and every historical original required once")
    for photo in fresh:
        if photo["resultOrigin"] == "machine-visual-result.json":
            a, b = photo["lifecycleBefore"], photo["lifecycleAfter"]
            need(a["room"] == b["room"] == "handheld/" + visual["saveOwner"]["machine"] and a["workers"] == b["workers"] and a["workers"] and all(v["ready"] and v["workerRunning"] for v in (a, b)) and all(photo[k]["progress"]["card"] == cards["gba_a"]["instance"] for k in ("before", "after")), "New LCD/MODEL photo must retain the original live machine/card")
    need(icons["previewCount"] == 51 and all(p["before"]["entryCount"] == p["after"]["entryCount"] == (28 if p["iconPage"] == "hardware" else 23) and all(v["unissuedPreviewsOnly"] and v["inventoryWrites"] is False and v["renderItemCalls"] == v["entryCount"] and len(v["entries"]) == v["entryCount"] and all(r["unissued"] for r in v["entries"]) for v in (p["before"], p["after"])) for p in icons["screenshots"]), "Icon pages must be 51 actual identity-free previews, not issued cards")
    output = output.absolute(); need(output.resolve() == output and REPO / "build" in output.parents and output.suffix == ".html", "Gallery belongs under repository build")
    output.parent.mkdir(parents=True, exist_ok=True); esc = html.escape
    def url(p): return quote(os.path.relpath(p, output.parent).replace(os.sep, "/"), safe="/:.")
    figures = ''.join(f'<figure><a href="{url(p["path"])}"><img src="{url(p["path"])}" alt="{esc(TITLES[Path(p["path"]).stem])}" loading="lazy"></a><figcaption>{esc(TITLES[Path(p["path"]).stem])}<small>{p["width"]} × {p["height"]} · 原始 Minecraft 帧</small><details><summary>来源与 SHA</summary>{esc(p["resultOrigin"])}<br><code>{p["sha256"]}</code></details></figcaption></figure>' for p in fresh)
    links = ''.join(f'<li>{p.parent.parent.name} · <a href="{url(p)}">{p.name}</a> · {len(v["checks"])} 项 / {len(v["screenshots"])} 图 · scenarioComplete={str(v["scenarioComplete"]).lower()}<br><code>{sha(p)}</code></li>' for p, v in sources)
    history = ''.join(f'<li><a href="{url(p["path"])}">{Path(p["path"]).name}</a> · {Path(p["path"]).parents[2].name} / {p["resultOrigin"]} · <code>{p["sha256"]}</code></li>' for p in photos if p["historical"])
    metadata = {"sourceRun": str(source), "currentRun": str(current), "productSha256": digest, "qaBuild": ref(report), "comparison": ref(comparison_path), "closures": closures, "sources": {str(p): sha(p) for p, v in sources}, "originals": photos, "physicalOSInput": False, "visualAcceptance": False}
    page = '''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>机器存档与薄卡册 · 当前原图</title><style>body{margin:0;background:#eef2ef;color:#183b40;font:16px/1.65 system-ui,sans-serif}main{max-width:1320px;margin:auto;padding:28px 22px}.grid{display:grid;grid-template-columns:repeat(auto-fit,minmax(380px,1fr));gap:20px}figure{margin:0;background:white;border:1px solid #c6d2ce;border-radius:10px;overflow:hidden}img{display:block;width:100%;height:auto}figcaption{padding:12px 16px}small{display:block;color:#5e7370}code{font-size:11px;overflow-wrap:anywhere}a{color:#126e80}li{margin-bottom:12px}@media(max-width:460px){main{padding:16px 10px}.grid{grid-template-columns:1fr}}</style><main><h1>机器自己的进度 · 薄卡册 · 全物品图标</h1><p>主要展示当前最终候选、复制世界新运行的8张原图：原掌机世界LCD和完整机身MODEL各1图，薄卡册4图，物品图标2图。直接链接原PNG，不复制、不编辑、不拿历史图替代。两页共51项图标是无卡带/机器身份、无ROM、未进入背包的本地视觉预览，不能算发行了51张卡。</p><p>来源01的卡册35项、机器原范围19项、独立续办57项保留。机器原范围因第二玩家切到创造背包而被QA回调拒绝，scenarioComplete=false原样保留；19项成功和2张暗图不代表原范围通过。来源01还存在世界LCD暗屏及MODEL退化问题，历史图片全部只列为追溯，不计本轮显示通过；最终候选在同一台原机器、同一张原卡及正常关闭后复制的世界中补验显示。</p><p>按精确构建比较，最终候选仅变HandheldRenderer与薄卡册碰撞类和77项资源；存档、容器、核心及worker沿用01原字节。原卡集、状态代际与磁盘SHA的复制及后续保存引用均核对。所引用运行的Minecraft JVM均exit0、原生审计完整且为空。这里保留GUI/网络回调与实体OS键鼠、实体手柄及音响听感的验收边界；原结果visualAcceptance不改写。</p>'''
    if rejected: page += '<p>中间运行02的候选03图标数据记录保持true/51项，但原版新手提示遮挡3项、空GBA图标偏低，人工视觉审核否决。两张原图只作追溯，不能当最终图标通过；原true不改成false。最终运行清理新手提示后另拍新图。</p>'
    page += f'<p>当前产品 SHA：<code>{digest}</code><br><a href="{url(comparison_path)}">与01的构建比较</a> · <a href="{url(report)}">当前QA构建</a><br>closed=true · <a href="{url(closures[0]["path"])}">来源01关闭回执</a> · <a href="{url(closures[1]["path"])}">当前最终运行关闭回执</a></p><div class="grid">{figures}</div><h2>{len(sources)}份原始结果</h2><ul>{links}</ul><details><summary>{len(photos)-len(fresh)}张历史原图（不计本轮显示通过）</summary><ul>{history}</ul></details><script type="application/json" id="machine-gallery-manifest">' + json.dumps(metadata, ensure_ascii=False).replace("<", "\\u003c") + '</script></main></html>'
    if rejected: page=page.replace('</main></html>',f'<p><a href="{url(closures[2]["path"])}">中间拒绝预览运行的正常关闭回执</a></p></main></html>')
    need(all(sha(Path(p)) == digest for p, digest in metadata["sources"].items()) and all(sha(Path(p["path"])) == p["sha256"] for p in photos), "Original evidence changed during preparation")
    output.write_text(page, encoding="utf-8"); print(json.dumps({"gallery": str(output), "currentOriginals": len(fresh), "historicalOriginals": len(photos)-len(fresh), "allOriginalsVerified": len(photos), "distinctPngSha256": len({p["sha256"] for p in photos}), "closed": True}, ensure_ascii=False))

if __name__ == "__main__":
    p = argparse.ArgumentParser(description=__doc__); p.add_argument("--source-run", required=True, type=Path); p.add_argument("--run", required=True, type=Path); p.add_argument("--build", required=True, type=Path); p.add_argument("--rejected-icons-run", "--rejected-run", dest="rejected_run", type=Path); p.add_argument("--output", type=Path, default=REPO / "build/machine-save-review/index.html"); a = p.parse_args(); build(a.source_run, a.run, a.build, a.output, a.rejected_run)
