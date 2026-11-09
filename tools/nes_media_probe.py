"""Validate an original audible two-controller ROM against a fixed candidate's exact worker/core."""
from pathlib import Path
import argparse, hashlib, json, os, re, subprocess, zipfile
from runtime_smoke import diagnostic_rom

ROOT=Path(__file__).resolve().parents[1]
def sha(path): return hashlib.sha256(path.read_bytes()).hexdigest()

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--name',required=True);parser.add_argument('--product',type=Path,required=True)
    args=parser.parse_args()
    if not re.fullmatch('[a-z0-9]+(?:-[a-z0-9]+)*',args.name):raise ValueError('Use a new simple build name')
    product=args.product.resolve(strict=True)
    if not product.is_relative_to(ROOT/'build'):raise ValueError('Use a fixed repository candidate')
    if any(os.environ.get(k)for k in ('JAVA_TOOL_OPTIONS','_JAVA_OPTIONS','JDK_JAVA_OPTIONS')):raise ValueError('Unexpected inherited Java options')
    out=ROOT/'build'/args.name;out.mkdir(exist_ok=False)
    base=ROOT/'build/runtime-smoke/two-port-battery.nes'
    if diagnostic_rom()!=base.read_bytes():raise ValueError('Default diagnostic changed; preserve the original baseline')
    extracted={}
    with zipfile.ZipFile(product)as jar:
        hashes=json.loads(jar.read('META-INF/muxi/extract-hashes.json'));sizes=json.loads(jar.read('META-INF/muxi/extract-sizes.json'))
        for name in ('worker/runtime.jar','worker/jna-5.14.0.jar','cores/windows-x86_64/fceumm.dll'):
            body=jar.read(name)
            if len(body)!=sizes[name]or hashlib.sha256(body).hexdigest()!=hashes[name]:raise ValueError('Candidate extraction identity differs')
            path=out/Path(name).name;path.write_bytes(body);extracted[name]=path
    rom=out/'original-two-port-tone.nes';rom.write_bytes(diagnostic_rom(True))
    classes=out/'classes';classes.mkdir();jdk=ROOT.parent/'test-environment/toolchains/java-21/bin'
    cp=os.pathsep.join(map(str,[classes,extracted['worker/runtime.jar'],extracted['worker/jna-5.14.0.jar']]))
    test=ROOT/'tests/runtime/NesMediaProbe.java'
    compile_result=subprocess.run([str(jdk/'javac.exe'),'-J-Xmx512m','-J-XX:ActiveProcessorCount=1','--release','21','-encoding','UTF-8','-cp',cp,'-d',str(classes),str(test)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60)
    (out/'compile.log').write_text(compile_result.stdout+compile_result.stderr,encoding='utf-8')
    if compile_result.returncode:raise RuntimeError('Probe compilation failed')
    command=[str(jdk/'java.exe'),'-Xmx128m','-XX:ActiveProcessorCount=1','-cp',cp,'net.muxigame.console.runtime.NesMediaProbe',str(jdk/'java.exe'),cp,str(extracted['cores/windows-x86_64/fceumm.dll']),str(rom),str(out)]
    result=subprocess.run(command,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=90)
    (out/'native.log').write_text(result.stdout+result.stderr,encoding='utf-8')
    report={'product':{'path':str(product),'sha256':sha(product)},'diagnosticRom':{'path':str(rom),'sha256':sha(rom),'bytes':rom.stat().st_size},'baseDiagnosticUnchanged':True,'source':{'runtime_smoke.py':sha(ROOT/'tools/runtime_smoke.py'),'NesMediaProbe.java':sha(test)},'extracted':{name:{'sha256':sha(path),'bytes':path.stat().st_size}for name,path in extracted.items()},'exitCode':result.returncode,'minecraftTested':False,'productionPublished':False}
    (out/'probe.json').write_text(json.dumps(report,indent=2),encoding='utf-8');print(result.stdout,result.stderr)
    if result.returncode:raise RuntimeError('Native probe failed; original results retained')

if __name__=='__main__':main()
