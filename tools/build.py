"""Build independent sources using the workspace's installed SDK; never launches Minecraft."""
from pathlib import Path
import argparse, hashlib, io, json, os, re, subprocess, sys, tomllib, zipfile

ROOT = Path(__file__).resolve().parents[1]
WORKSPACE = ROOT.parent
sys.path.insert(0, str(WORKSPACE / 'better-mc-remake/scripts'))
import local_mc_runtime as sdk

def sha(path):
    digest=hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda:stream.read(1024*1024),b''):digest.update(chunk)
    return digest.hexdigest()

def build(name, tests, release=False):
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
    # JEI is an optional runtime integration, never embedded in either product JAR.
    jei=WORKSPACE/'minecraft/release/client/mods/jei-1.21.1-neoforge-19.32.0.359.jar'
    jei_pin={'version':'19.32.0.359','bytes':1616910,'sha256':'0169e38d4771b1a0a6bd9fc92734297105fff2f6ad8a4ed73ad38139b824ea54'}
    if not jei.is_file() or jei.stat().st_size!=jei_pin['bytes'] or sha(jei)!=jei_pin['sha256']:
        raise ValueError('JEI compile-only SDK differs from its exact installed pin')
    classpath+=os.pathsep+str(jei)
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
        files=[p for folder in ['domain','runtime','input','save','compat','hardware','audio','stream','transfer','storage','link'] for p in sorted((ROOT/'tests'/folder).rglob('*.java'))]
        if files:
            tc=out/'test-classes';tc.mkdir(); compile_set(files,tc,str(classes)+os.pathsep+classpath,'tests')
            for p in files:
                body=p.read_text(encoding='utf-8')
                # Runtime probes require an explicit official core/ROM, exercised by runtime_smoke.py.
                if 'static void main(' not in body or p.is_relative_to(ROOT/'tests/runtime') or p.is_relative_to(ROOT/'tests/link/native'): continue
                package=re.search(r'package\s+([\w.]+);',body)
                main_class=(package.group(1)+'.' if package else '')+p.stem
                r=subprocess.run([str(jdk/'java.exe'),'-Xmx128m','-XX:ActiveProcessorCount=1','-ea','-cp',os.pathsep.join([str(tc),str(classes),str(ROOT/'common/src/main/resources'),classpath]),main_class,str(out/'test-data')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=60,cwd=ROOT)
                (out/(p.stem+'.log')).write_text(r.stdout+r.stderr,encoding='utf-8');print(r.stdout.strip())
                if r.returncode: raise RuntimeError(p.stem+' failed: '+r.stderr[-2000:])
                test_results.append(p.stem)
    mod_metadata=tomllib.loads((ROOT/'common/src/main/resources/META-INF/neoforge.mods.toml').read_text(encoding='utf-8'))
    version=mod_metadata['mods'][0]['version']
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+(?:[-+][A-Za-z0-9.-]+)?',version):
        raise ValueError('Unsafe or unresolved mod version')
    artifact=out/('muxi-console-'+version+'+mc1.21.1.jar')
    resources={}
    for p in (ROOT/'common/src/main/resources').rglob('*'):
        if p.is_file(): resources[p.relative_to(ROOT/'common/src/main/resources').as_posix()]=p.read_bytes()
    resources['META-INF/muxi/OWNERSHIP.md']=(ROOT/'OWNERSHIP.md').read_bytes()
    resources['META-INF/LICENSE']=(ROOT/'LICENSE').read_bytes()
    for p in (ROOT/'third-party/licenses').rglob('*'):
        if p.is_file(): resources['META-INF/muxi/licenses/'+p.relative_to(ROOT/'third-party/licenses').as_posix()]=p.read_bytes()
    jna=game/'libraries/net/java/dev/jna/jna/5.14.0/jna-5.14.0.jar'
    from build_worker import JNA
    if jna.stat().st_size!=JNA['bytes'] or sha(jna)!=JNA['sha256']:
        raise ValueError('Worker JNA dependency differs from its official pin')
    resources['worker/jna-5.14.0.jar']=jna.read_bytes()
    worker_deps=json.loads((ROOT/'third-party/worker-dependencies.json').read_text(encoding='utf-8'))
    for dependency in worker_deps['dependencies']:
        matches=list((game/'libraries/org/lwjgl').rglob(dependency['file']))
        if len(matches)!=1 or matches[0].stat().st_size!=dependency['bytes'] or sha(matches[0])!=dependency['sha256']:
            raise ValueError('Worker dependency differs from official pin: '+dependency['file'])
        resources['worker/'+dependency['file']]=matches[0].read_bytes()
    resources['META-INF/muxi/worker-dependencies.json']=json.dumps(worker_deps,indent=2).encode()
    # The child process has only our runtime classes and official JNA, never a Minecraft installation.
    worker=io.BytesIO()
    with zipfile.ZipFile(worker,'w',zipfile.ZIP_DEFLATED) as bundle:
        for p in sorted((classes/'net/muxigame/console/runtime').rglob('*.class')):
            info=zipfile.ZipInfo(p.relative_to(classes).as_posix(),(2026,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;bundle.writestr(info,p.read_bytes())
    resources['worker/runtime.jar']=worker.getvalue()
    core_manifest=json.loads((ROOT/'third-party/cores.json').read_text(encoding='utf-8'))
    system_manifest=json.loads((ROOT/'third-party/system-resources.json').read_text(encoding='utf-8'))
    for pin in system_manifest['resources']:
        path=(WORKSPACE/pin['archive']).resolve()
        if not path.is_relative_to((WORKSPACE/'test-environment/cache/muxi-console').resolve()) or not path.is_file() or path.stat().st_size!=pin['archiveBytes'] or sha(path)!=pin['archiveSha256']:
            raise ValueError('Core system resources differ from their source-bound pin')
        if not re.fullmatch(r'system/[a-z0-9_-]+\.zip',pin['entry']):
            raise ValueError('Invalid core system resource entry')
        if release and not pin.get('releaseReady'):
            raise ValueError('Core system resource licensing/source review is incomplete')
        resources[pin['entry']]=path.read_bytes()
    resources['META-INF/muxi/system-resources.json']=json.dumps(system_manifest,indent=2).encode()
    source_delivery=None
    if release:
        if any(not core.get('releaseReady') for core in core_manifest['cores'].values() if core.get('purpose') in ('software-emulation','emulation')):
            raise ValueError('Packaged core lacks verified corresponding source and licensing')
        from release_sources import source_delivery as verified_source_delivery
        source_delivery=verified_source_delivery(require_complete=True)
    resources['META-INF/muxi/cores.json']=json.dumps(core_manifest,indent=2).encode()
    for link_id, library_id in [("sameboy-link", "sameboy_link"), ("mgba-link", "mgba_link"), ("melondsds-link", "melondsds_link"), ("azahar-link", "azahar_link")]:
        link_manifest_path=ROOT/"third-party"/(link_id+".json")
        if link_manifest_path.is_file():
            link=json.loads(link_manifest_path.read_text(encoding='utf-8'))
            if release and not link.get('releaseReady'):
                raise ValueError('Link bridge source delivery has not completed release review')
            resources["META-INF/muxi/"+link_id+".json"]=link_manifest_path.read_bytes()
            for platform,pin in link['platforms'].items():
                archive=(WORKSPACE/pin['archive']).resolve()
                if not archive.is_relative_to((WORKSPACE/'test-environment/cache/muxi-console').resolve()) or not archive.is_file() or archive.stat().st_size!=pin['archiveBytes'] or sha(archive)!=pin['archiveSha256']:
                    raise ValueError('Link bridge archive differs from its source-bound pin')
                with zipfile.ZipFile(archive) as z:
                    members=[p for p in z.infolist() if p.filename==pin['libraryName'] and not p.is_dir()]
                    if len(members)!=1 or members[0].file_size!=pin['libraryBytes'] or members[0].file_size>64*1024*1024:
                        raise ValueError('Link bridge archive library is not uniquely bounded')
                    body=z.read(members[0])
                if hashlib.sha256(body).hexdigest()!=pin['librarySha256']:
                    raise ValueError('Link bridge library differs from its source-bound pin')
                resources['cores/'+platform+'/'+library_id+('.dll' if platform.startswith('windows') else '.so')]=body
    for name,core in core_manifest['cores'].items():
        if core.get('purpose') not in ('software-emulation','emulation'):continue
        for platform,pin in core['platforms'].items():
            archive=WORKSPACE/pin['archive']
            if sha(archive)!=pin['archiveSha256']:raise ValueError('Official core archive changed: '+name)
            with zipfile.ZipFile(archive) as z:
                body=z.read(pin['libraryName'])
            if hashlib.sha256(body).hexdigest()!=pin['librarySha256']:raise ValueError('Official core library changed: '+name)
            resources['cores/'+platform+'/'+name+('.dll' if platform.startswith('windows') else '.so')]=body
    resources['META-INF/muxi/extract-hashes.json']=json.dumps({k:hashlib.sha256(v).hexdigest() for k,v in resources.items() if k.startswith(('worker/','cores/','system/'))},sort_keys=True).encode()
    resources['META-INF/muxi/extract-sizes.json']=json.dumps({k:len(v) for k,v in resources.items() if k.startswith(('worker/','cores/','system/'))},sort_keys=True).encode()
    for p in classes.rglob('*.class'):resources[p.relative_to(classes).as_posix()]=p.read_bytes()
    from package_runtime import package_artifacts
    split=package_artifacts(artifact,resources,source_delivery)
    if any(sha(ROOT/p)!=h for p,h in inputs.items()): raise RuntimeError('Sources changed during compilation')
    report={'artifact':str(artifact),'bytes':artifact.stat().st_size,'sha256':sha(artifact),'version':version,**split,'compileOnlyDependencies':{'jei':{'path':str(jei),**jei_pin}},'releasePackaging':release,'correspondingSource':None if source_delivery is None else {'bytes':source_delivery['bytes'],'sha256':source_delivery['sha256']},'sources':inputs,'tests':test_results,'minecraftTested':False,'productionPublished':False}
    (out/'build.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k!='sources'}))
    return artifact

if __name__=='__main__':
    sys.stdout.reconfigure(encoding='utf-8',errors='replace')
    p=argparse.ArgumentParser();p.add_argument('--name',required=True);p.add_argument('--tests',action='store_true');p.add_argument('--release',action='store_true',help='Require complete verified sources and emit a separate same-release source attachment');a=p.parse_args();build(a.name,a.tests,a.release)
