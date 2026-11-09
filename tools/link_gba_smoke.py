"""Run the GBA cable/official-single state proof in isolated non-Minecraft Java processes."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
import re
from pathlib import Path
import subprocess
import zipfile
from link_gba_diagnostic import diagnostic

ROOT=Path(__file__).resolve().parents[1]
def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--adapter',type=Path,required=True);ap.add_argument('--output',type=Path,required=True);args=ap.parse_args()
    out=args.output.resolve()
    if (ROOT/'build').resolve() not in out.parents:raise RuntimeError('Proof output must remain in this repository build directory')
    out.mkdir(parents=True,exist_ok=False);classes=out/'classes';classes.mkdir();native_temp=out/'native-temp';native_temp.mkdir()
    jdk=ROOT.parent/'test-environment/toolchains/java-21/bin';runtime=ROOT/'build/link-runtime-smoke-04/classes'
    if not runtime.is_dir():raise RuntimeError('Existing frozen runtime proof classes required')
    import build_worker
    dep=next(d for d in build_worker.dependencies()if d['file']=='jna-5.14.0.jar')
    candidates=[ROOT.parent/'test-environment/cache/muxi-console/dependencies'/dep['file']]+list((ROOT.parent/'test-environment/resources/client/game/libraries').rglob(dep['file']))
    jna=next((p for p in candidates if p.is_file()and p.stat().st_size==dep['bytes']and sha(p)==dep['sha256']),None)
    if jna is None:raise RuntimeError('Existing pinned JNA missing')
    pin=json.loads((ROOT/'third-party/cores.json').read_text(encoding='utf-8'))['cores']['mgba']['platforms']['windows-x86_64'];archive=ROOT.parent/pin['archive']
    if sha(archive)!=pin['archiveSha256']:raise RuntimeError('Official base archive differs')
    base=out/pin['libraryName']
    with zipfile.ZipFile(archive)as z:base.write_bytes(z.read(pin['libraryName']))
    if sha(base)!=pin['librarySha256']:raise RuntimeError('Official base DLL differs')
    roms=[]
    for i in range(2):p=out/f'endpoint-{i}.gba';p.write_bytes(diagnostic(i));roms.append(p)
    source=ROOT/'tests/link/native/GbaLinkStateSmoke.java';cp=str(runtime)+os.pathsep+str(jna)
    command=[str(jdk/'javac.exe'),'-J-Xmx512m','-J-XX:ActiveProcessorCount=1','-J-Duser.language=en','--release','21','-encoding','UTF-8','-cp',cp,'-d',str(classes),str(source)]
    result=subprocess.run(command,capture_output=True,text=True,encoding='utf-8',errors='replace');(out/'compile.log').write_text(result.stdout+result.stderr,encoding='utf-8')
    if result.returncode:print(result.stdout+result.stderr);raise SystemExit(result.returncode)
    cp=str(classes)+os.pathsep+cp
    command=[str(jdk/'java.exe'),'-Xmx256m','-XX:ActiveProcessorCount=1','-Djna.tmpdir='+str(native_temp),'-cp',cp,'net.muxigame.console.tests.link.GbaLinkStateSmoke',str(jdk/'java.exe'),cp,str(args.adapter.resolve()),str(base),*map(str,roms),str(out/'result')]
    result=subprocess.run(command,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=180);(out/'native.log').write_text(result.stdout+result.stderr,encoding='utf-8')
    # The direct native proof's JNA cache is private to this output, and removed only after its JVM exited.
    if native_temp.resolve().parent!=out or native_temp.is_symlink():raise RuntimeError('Native proof cache identity changed')
    for entry in native_temp.iterdir():
        if entry.is_symlink()or not entry.is_file()or entry.resolve().parent!=native_temp.resolve():raise RuntimeError('Unexpected native proof cache entry')
        entry.unlink()
    native_temp.rmdir()
    marker=re.search(r'GbaLinkStateSmoke (\d+) checks passed',result.stdout)
    report={'exitCode':result.returncode,'checksPassed':int(marker.group(1))if marker and not result.returncode else 0,'adapterSHA256':sha(args.adapter),'baseSHA256':sha(base),'sourceSHA256':sha(source),'diagnosticSHA256':[sha(p)for p in roms],'runtimeClasses':'build/link-runtime-smoke-04/classes (frozen original state proof runtime)','parentNativeTemporaryFilesCleaned':not native_temp.exists(),'minecraftExecuted':False}
    (out/'result.json').write_text(json.dumps(report,indent=2),encoding='utf-8');print(json.dumps(report))
    if result.returncode:print((result.stdout+result.stderr)[-5000:])
    if not result.returncode and not marker:raise RuntimeError('Native success marker missing')
    raise SystemExit(result.returncode)

if __name__=='__main__':main()
