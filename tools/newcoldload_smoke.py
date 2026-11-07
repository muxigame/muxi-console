"""Restore real diagnostic states in fresh, unprimed workers for every platform; never starts Minecraft."""
from __future__ import annotations
import argparse,hashlib,json,os,subprocess
from pathlib import Path

CORES={'nes':'fceumm','snes':'bsnes2014_performance','gb':'sameboy','gbc':'sameboy','gba':'mgba','md':'blastem','n64':'mupen64plus_next','sms':'gearsystem','gg':'gearsystem','sg1000':'gearsystem','pce':'mednafen_pce_fast','a2600':'stella2014','a7800':'prosystem','lynx':'mednafen_lynx','ngp':'mednafen_ngp','ngpc':'mednafen_ngp','ws':'mednafen_wswan','wsc':'mednafen_wswan','vb':'mednafen_vb','fds':'fceumm','arcade':'mame'}

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--only',help='Comma-separated platform ids; default all 21');parser.add_argument('--name',required=True,help='New simple build output name');args=parser.parse_args()
    repo=Path(__file__).resolve().parents[1];workspace=repo.parent
    if not args.name or Path(args.name).name!=args.name or any(ch not in 'abcdefghijklmnopqrstuvwxyz0123456789-' for ch in args.name):raise ValueError('Choose a new simple output name')
    names=args.only.split(',') if args.only else list(CORES)
    if not names or any(name not in CORES for name in names):raise ValueError('Unknown platform')
    output=repo/'build'/args.name;output.mkdir(exist_ok=False);classes=output/'classes';classes.mkdir()
    libraries=workspace/'test-environment/resources/client/game/libraries';java=workspace/'test-environment/toolchains/java-21/bin/java.exe'
    jars=[libraries/'net/java/dev/jna/jna/5.14.0/jna-5.14.0.jar']
    for module in ('lwjgl','lwjgl-glfw','lwjgl-opengl'):jars.extend(libraries/f'org/lwjgl/{module}/3.3.3/{module}-3.3.3{suffix}.jar' for suffix in ('','-natives-windows'))
    sources=sorted((repo/'common/src/main/java/net/muxigame/console/runtime').glob('*.java'))+[repo/'tests/runtime/CoreColdLoadSmoke.java']
    subprocess.run([str(java.with_name('javac.exe')),'-J-Xmx512m','-J-XX:ActiveProcessorCount=1','--release','21','-cp',os.pathsep.join(map(str,jars)),'-d',str(classes),*map(str,sources)],check=True)
    cp=os.pathsep.join(map(str,[classes,*jars]));manifest=json.loads((repo/'third-party/cores.json').read_text(encoding='utf-8'))
    from platform_smoke_software import GENERATORS,lynx_diagnostic_firmware
    from platform_smoke_fds import diagnostic_disk,diagnostic_firmware
    from platform_smoke import n64_diagnostic
    from runtime_smoke import diagnostic_rom as nes_diagnostic
    from gba_diagnostic import diagnostic_rom as gba_diagnostic
    results=[]
    for name in names:
        item=output/name;item.mkdir();system=item/'system';system.mkdir()
        extension={'nes':'nes','snes':'sfc','gba':'gba','n64':'z64','sg1000':'sg','a2600':'a26','a7800':'a78','lynx':'o','arcade':'cmd'}.get(name,name)
        if name=='nes':raw=nes_diagnostic()
        elif name=='gba':raw=gba_diagnostic()
        elif name=='n64':raw=n64_diagnostic()
        elif name=='fds':raw=diagnostic_disk();(system/'disksys.rom').write_bytes(diagnostic_firmware())
        elif name=='arcade':raw=b'pong -skip_gameinfo -nothrottle'
        else:raw=GENERATORS[name]()
        if name=='lynx':(system/'lynxboot.img').write_bytes(lynx_diagnostic_firmware())
        content=item/('pong.cmd' if name=='arcade' else 'original.'+extension);content.write_bytes(raw)
        pin=manifest['cores'][CORES[name]]['platforms']['windows-x86_64'];core=workspace/Path(pin['archive']).parent/pin['libraryName']
        if hashlib.sha256(core.read_bytes()).hexdigest()!=pin['librarySha256']:raise ValueError('Core pin mismatch: '+name)
        command=[str(java),'-Xmx256m','-XX:ActiveProcessorCount=1','-cp',cp,'net.muxigame.console.runtime.CoreColdLoadSmoke',str(java),cp,str(core),str(content),str(item),name,str(system),'360' if name in ('gb','gbc') else '120']
        result=subprocess.run(command,capture_output=True,text=True,timeout=180)
        checks=next((int(line.split()[1]) for line in result.stdout.splitlines() if line.startswith('COLD_LOAD_CHECKS ')),0)
        record={'platform':name,'coreSha256':pin['librarySha256'],'contentSha256':hashlib.sha256(raw).hexdigest(),'exitCode':result.returncode,'checks':checks,'targetPublicStepsBeforeLoad':0,'stdout':result.stdout,'stderr':result.stderr}
        (item/'result.json').write_text(json.dumps(record,indent=2),encoding='utf-8');results.append(record)
        print(result.stdout,end='',flush=True);print(result.stderr,end='',flush=True)
    report={'platformCount':len(results),'checks':sum(r['checks'] for r in results),'allPassed':all(r['exitCode']==0 and r['checks']>0 for r in results),'runtimeSources':{p.relative_to(repo).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in sources if 'src/main/java' in p.as_posix()},'platforms':results}
    (output/'result.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print('COLD_LOAD_MATRIX',len(results),report['checks'],'PASS' if report['allPassed'] else 'FAIL')
    raise SystemExit(0 if report['allPassed'] else 1)

if __name__=='__main__':main()
