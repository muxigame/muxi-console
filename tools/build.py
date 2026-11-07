"""Build independent sources using the workspace's installed SDK; never launches Minecraft."""
from pathlib import Path
import argparse, hashlib, io, json, os, subprocess, sys, zipfile

ROOT = Path(__file__).resolve().parents[1]
WORKSPACE = ROOT.parent
sys.path.insert(0, str(WORKSPACE / 'better-mc-remake/scripts'))
import local_mc_runtime as sdk

def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()

def build(name, tests):
    out = ROOT / 'build' / name
    if out.exists() or Path(name).name != name:
        raise ValueError('Choose a new simple build name')
    if any(os.environ.get(v) for v in ['JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS']):
        raise ValueError('Unexpected inherited Java options')
    game = WORKSPACE / 'test-environment/resources/client/game'
    server = WORKSPACE / 'minecraft/release/server'
    jdk = WORKSPACE / 'test-environment/toolchains/java-21/bin'
    metadata = sdk.client_metadata(game, 'BatterMC5Remake')
    libraries = sdk.client_libraries(game, 'BatterMC5Remake', metadata)
    classpath = sdk.javac_classpath(server, game, '21.1.250', libraries)
    sources = sorted((ROOT/'common/src/main/java').rglob('*.java')) + sorted((ROOT/'domain/src/main/java').rglob('*.java'))
    if not sources:
        raise ValueError('No independent sources')
    forbidden = ['com.nanobuntv', 'cartridge_mod', 'cartridge-host']
    if any(word in p.read_text(encoding='utf-8') for p in sources for word in forbidden):
        raise ValueError('Forbidden legacy implementation reference')
    out.mkdir(parents=True)
    classes = out/'classes'; classes.mkdir()
    def compile_set(files, dest, cp, label):
        args = ['--release', '21', '-encoding', 'UTF-8', '-proc:none', '-implicit:none', '-cp', cp, '-d', str(dest), *map(str, files)]
        af = out/(label+'.args'); sdk.argfile(af,args)
        cmd = [str(jdk/'javac.exe'), '-J-Xmx512m', '-J-XX:ActiveProcessorCount=1', '-J-Duser.language=en', '-J-Duser.country=US', '-J-Dfile.encoding=UTF-8', '-J-Dsun.stdout.encoding=UTF-8', '-J-Dsun.stderr.encoding=UTF-8', '@'+str(af)]
        r = subprocess.run(cmd, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=180, cwd=ROOT)
        (out/(label+'.log')).write_text(r.stdout+r.stderr, encoding='utf-8')
        if r.returncode:
            print(r.stdout+r.stderr); raise RuntimeError(label+' failed')
    inputs = {str(p.relative_to(ROOT)):sha(p) for p in sources}
    compile_set(sources,classes,classpath,'compile')
    test_results=[]
    if tests:
        files=sorted((ROOT/'tests/domain').rglob('*.java'))+sorted((ROOT/'tests/runtime').rglob('*.java'))+sorted((ROOT/'tests/input').rglob('*.java'))+sorted((ROOT/'tests/save').rglob('*.java'))
        if files:
            tc=out/'test-classes';tc.mkdir(); compile_set(files,tc,str(classes)+os.pathsep+classpath,'tests')
            import re
            for p in files:
                body=p.read_text(encoding='utf-8')
                # Runtime probes require an explicit official core/ROM, exercised by runtime_smoke.py.
                if 'static void main(' not in body or p.is_relative_to(ROOT/'tests/runtime'): continue
                package=re.search(r'package\s+([\w.]+);',body).group(1)
                r=subprocess.run([str(jdk/'java.exe'),'-Xmx128m','-XX:ActiveProcessorCount=1','-ea','-cp',str(tc)+os.pathsep+str(classes)+os.pathsep+classpath,package+'.'+p.stem,str(out/'test-data')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60,cwd=ROOT)
                (out/(p.stem+'.log')).write_text(r.stdout+r.stderr,encoding='utf-8');print(r.stdout.strip())
                if r.returncode: raise RuntimeError(p.stem+' failed: '+r.stderr[-2000:])
                test_results.append(p.stem)
    artifact=out/'muxi-console-0.1.0-dev.1+mc1.21.1.jar'
    resources={}
    for p in (ROOT/'common/src/main/resources').rglob('*'):
        if p.is_file(): resources[p.relative_to(ROOT/'common/src/main/resources').as_posix()]=p.read_bytes()
    resources['META-INF/muxi/OWNERSHIP.md']=(ROOT/'OWNERSHIP.md').read_bytes()
    jna=game/'libraries/net/java/dev/jna/jna/5.14.0/jna-5.14.0.jar'
    resources['worker/jna-5.14.0.jar']=jna.read_bytes()
    # The child process has only our runtime classes and official JNA, never a Minecraft installation.
    worker=io.BytesIO()
    with zipfile.ZipFile(worker,'w',zipfile.ZIP_DEFLATED) as bundle:
        for p in sorted((classes/'net/muxigame/console/runtime').rglob('*.class')):
            info=zipfile.ZipInfo(p.relative_to(classes).as_posix(),(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;bundle.writestr(info,p.read_bytes())
    resources['worker/runtime.jar']=worker.getvalue()
    core_manifest=json.loads((ROOT/'third-party/cores.json').read_text(encoding='utf-8'))
    resources['META-INF/muxi/cores.json']=json.dumps(core_manifest,indent=2).encode()
    for name,core in core_manifest['cores'].items():
        if core.get('purpose')!='software-emulation':continue
        for platform,pin in core['platforms'].items():
            archive=WORKSPACE/pin['archive']
            if sha(archive)!=pin['archiveSha256']:raise ValueError('Official core archive changed: '+name)
            with zipfile.ZipFile(archive) as z:
                body=z.read(pin['libraryName'])
            if hashlib.sha256(body).hexdigest()!=pin['librarySha256']:raise ValueError('Official core library changed: '+name)
            resources['cores/'+platform+'/'+name+('.dll' if platform.startswith('windows') else '.so')]=body
    resources['META-INF/muxi/extract-hashes.json']=json.dumps({k:hashlib.sha256(v).hexdigest() for k,v in resources.items() if k.startswith(('worker/','cores/'))},sort_keys=True).encode()
    with zipfile.ZipFile(artifact,'w',zipfile.ZIP_DEFLATED) as jar:
        for p in classes.rglob('*.class'):resources[p.relative_to(classes).as_posix()]=p.read_bytes()
        for path,body in sorted(resources.items()):
            info=zipfile.ZipInfo(path,(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;jar.writestr(info,body)
    if any(sha(ROOT/p)!=h for p,h in inputs.items()): raise RuntimeError('Sources changed during compilation')
    report={'artifact':str(artifact),'sha256':sha(artifact),'sources':inputs,'tests':test_results,'minecraftTested':False,'productionPublished':False}
    (out/'build.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k!='sources'}))
    return artifact

if __name__=='__main__':
    sys.stdout.reconfigure(encoding='utf-8',errors='replace')
    p=argparse.ArgumentParser();p.add_argument('--name',required=True);p.add_argument('--tests',action='store_true');a=p.parse_args();build(a.name,a.tests)
