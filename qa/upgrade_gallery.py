"""Build a read-only gallery from the exact closed08 results; preserves all original false scopes."""
import argparse,html,json,os,re
from pathlib import Path
from PIL import Image
from drive import read_json,sha,REPO,WORKSPACE

CLOSURE="eccb96733b2c9f151676b2714a110ab41a0e6d8e02b060b7b91aa40be188f8d6"


def build(run,accepted_link=False):
    run=run.resolve(strict=True);parts=run.relative_to(WORKSPACE/"test-environment").parts
    if len(parts)!=3 or not re.fullmatch("client-[1-9][0-9]*",parts[0])or parts[1]!="runs":raise ValueError("Closed unified run required")
    source=run/"console-qa/closed-review.json";c=read_json(source)
    if sha(source)!=CLOSURE or not c["normalClosure"]["normalExit"]or not c["workerAudit"]["resourceComplete"]or not c["workerAudit"]["coverageComplete"]or c["imageCount"]!=39 or c["passedAssertionRows"]!=143:raise ValueError("Exact closed candidate08 proof required")
    out=REPO/"build/upgrade-review";out.mkdir(exist_ok=True)
    def relative(p):return os.path.relpath(p,out).replace("\\","/")
    def link(p,label):return '<a href="'+html.escape(relative(p),quote=True)+'">'+html.escape(label)+'</a>'
    rows=[]
    for row in c["sourceResults"]:
        p=Path(row["path"])
        if sha(p)!=row["sha256"]:raise ValueError("Source result hash changed")
        rows.append('<tr><td>'+link(p,p.name)+'</td><td>'+str(row["scenarioComplete"]).lower()+'</td><td>'+str(row["passed"])+'</td><td>'+str(row["images"])+'</td><td>'+html.escape(row["failure"])+'</td></tr>')
    figures=[];hashes=[];manifest=[]
    for row in c["images"]:
        p=Path(row["path"])
        if p.parent!=run/row["role"]/'screenshots'or p.resolve()!=p or sha(p)!=row["sha256"]:raise ValueError("Raw image path/hash changed")
        with Image.open(p)as im:im.verify()
        with Image.open(p)as im:im.load();assert im.size==(960,540)
        status=row["visualStatus"]
        if accepted_link and p.name.startswith("link-"):status="root-reviewed-in-scope"
        scope=row["scope"]
        if p.name=="link-invitation-consent.png":scope="Root viewed: title, accept/reject and both names readable; vanilla tutorial obscures the explanation ending, which is not accepted as fully readable."
        record={**row,"visualStatus":status,"scope":scope};manifest.append(record);hashes.append(row["sha256"])
        figures.append('<figure class="'+('bad'if status=='rejected'else'')+'"><a href="'+html.escape(relative(p),quote=True)+'"><img loading="lazy" src="'+html.escape(relative(p),quote=True)+'" width="960" height="540"></a><figcaption><b>'+html.escape(p.name)+'</b> · '+html.escape(status)+'<br>'+html.escape(scope)+'<br><small>SHA256 '+row["sha256"]+'</small></figcaption></figure>')
    assert len(set(row["path"]for row in manifest))==39
    text='<!doctype html><meta charset="utf-8"><title>Candidate08 native originals</title><style>body{background:#171a20;color:#e8edf2;font:16px system-ui;max-width:1280px;margin:2rem auto;padding:0 1rem}a{color:#92ccff}table{border-collapse:collapse;width:100%}td,th{border:1px solid #48505a;padding:.5rem}main{display:grid;grid-template-columns:repeat(auto-fit,minmax(420px,1fr));gap:1rem}figure{margin:0;background:#222831;padding:.7rem}figure.bad{border:2px solid #f18484}img{display:block;width:100%;height:auto}small{word-break:break-all}</style><h1>固定候选08原生图集</h1><p>Product SHA '+c["product"]["sha256"]+'</p><p>143条真实已过记录；六份结果中四份原false保留。39次原始采图、37不同字节哈希。三张绑定页视觉否决，修复后需新候选补验；不能把此图集写成零失败或整体视觉通过。</p><p>'+link(source,'关服后磁盘/卡/进程闭合')+' · '+link(Path(c["workerAudit"]["path"]),'11实际worker只读审计')+'</p><table><tr><th>原结果</th><th>complete</th><th>pass rows</th><th>images</th><th>原失败</th></tr>'+''.join(rows)+'</table><main>'+''.join(figures)+'</main>'
    report=dict(runId=c["runId"],productSha256=c["product"]["sha256"],closureSha256=CLOSURE,sourceResults=c["sourceResults"],imageCount=39,distinctImageHashes=len(set(hashes)),originalFalseResults=4,linkVisualRootReviewed=accepted_link,visualAcceptance=False,images=manifest)
    (out/'manifest.json').write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding='utf-8');index=out/'index.html';index.write_text(text,encoding='utf-8')
    # Resolve every actual source/image link; never copy or modify a PNG.
    for row in c["sourceResults"]+c["images"]:assert Path(row["path"]).is_file()
    print(json.dumps(dict(index=str(index),sha256=sha(index),images=39,distinctHashes=len(set(hashes)),originalFalseResults=4,acceptedLink=accepted_link)))


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--run',type=Path,required=True);p.add_argument('--root-reviewed-link',action='store_true');a=p.parse_args();build(a.run,a.root_reviewed_link)
