"""Measure real modern-MAME Pong P1/P2 paddles through the frozen runtime, without Minecraft or game ROMs."""
from pathlib import Path
import hashlib,json,os,subprocess

def main():
    repo=Path(__file__).resolve().parents[1];workspace=repo.parent;output=repo/'build/pong-input-smoke-validated';output.mkdir(exist_ok=True)
    libraries=workspace/'test-environment/resources/client/game/libraries';jna=libraries/'net/java/dev/jna/jna/5.14.0/jna-5.14.0.jar'
    java=workspace/'test-environment/toolchains/java-21/bin/java.exe';classes=output/'classes';classes.mkdir(exist_ok=True)
    # Software MAME needs only JNA at execution, but all runtime sources compile against the three worker GL APIs.
    jars=[jna,*[libraries/f'org/lwjgl/{module}/3.3.3/{module}-3.3.3.jar' for module in ('lwjgl','lwjgl-glfw','lwjgl-opengl')]]
    sources=sorted((repo/'common/src/main/java/net/muxigame/console/runtime').glob('*.java'))+[repo/'tests/runtime/PongInputSmoke.java']
    subprocess.run([str(java.with_name('javac.exe')),'-J-Xmx512m','-J-XX:ActiveProcessorCount=1','--release','21','-cp',os.pathsep.join(map(str,jars)),'-d',str(classes),*map(str,sources)],check=True)
    cp=os.pathsep.join(map(str,[classes,jna]));core=workspace/'test-environment/cache/muxi-console/libretro/mame_libretro.dll'
    pin=json.loads((repo/'third-party/cores.json').read_text(encoding='utf-8'))['cores']['mame']['platforms']['windows-x86_64']
    if hashlib.sha256(core.read_bytes()).hexdigest()!=pin['librarySha256']:raise ValueError('MAME pin mismatch')
    content=output/'pong.cmd';content.write_text('pong -skip_gameinfo -nothrottle',encoding='ascii')
    result=subprocess.run([str(java),'-Xmx256m','-XX:ActiveProcessorCount=1','-cp',cp,'net.muxigame.console.runtime.PongInputSmoke',str(java),cp,str(core),str(content),str(output)],capture_output=True,text=True,timeout=240)
    observations=[json.loads(line[len('PONG_CASE '):]) for line in result.stdout.splitlines() if line.startswith('PONG_CASE ')]
    checks=next((int(line.split()[1]) for line in result.stdout.splitlines() if line.startswith('PONG_ASSERTIONS ')),0)
    report={'coreSha256':pin['librarySha256'],'exitCode':result.returncode,'checks':checks,'input':{'start':'P1 buttons=4 for 3 frames, then idle 123 frames','analog':'each port leftX=+24000 moves up; -24000 moves down; hold 30 frames','digital':'each port buttons=64 moves down; hold 60 frames','otherAxes':0},'observations':observations,'normalExit':result.returncode==0 and 'PONG_CLOSED exit0 forced=false cleaned=true' in result.stdout,'stdout':result.stdout,'stderr':result.stderr}
    (output/'result.json').write_text(json.dumps(report,indent=2),encoding='utf-8');print(result.stdout,end='');print(result.stderr,end='');raise SystemExit(result.returncode)

if __name__=='__main__':main()
