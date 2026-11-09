"""Compile owned DS worker helpers and run explicit ABI or real-core diagnostics (never Minecraft)."""
from pathlib import Path
import argparse,hashlib,json,os,subprocess
ROOT=Path(__file__).resolve().parents[1]
def sha(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def main():
 ap=argparse.ArgumentParser();ap.add_argument('--output',type=Path,required=True);ap.add_argument('--core',type=Path);ap.add_argument('--core-sha');ap.add_argument('--rom',type=Path);ap.add_argument('--rom-right',type=Path);ap.add_argument('--wireless',action='store_true');ap.add_argument('--group',action='store_true');ap.add_argument('--frontend-queued',action='store_true');ap.add_argument('--base-core',type=Path);ap.add_argument('--base-core-sha');ap.add_argument('--runtime-jar',type=Path,help='Use these exact already-built runtime bytes; compile only the owned diagnostic helpers');args=ap.parse_args();out=args.output.resolve()
 if bool(args.base_core)!=bool(args.base_core_sha)or args.base_core and sha(args.base_core)!=args.base_core_sha:raise ValueError('Original single-core compatibility needs its exact actual pin')
 if bool(args.core)!=bool(args.core_sha)or bool(args.core)!=bool(args.rom):raise ValueError('Real core mode requires --core, --core-sha and original --rom')
 if args.core and sha(args.core)!=args.core_sha:raise ValueError('Native core differs from requested exact pin')
 if (ROOT/'build').resolve()not in out.parents:raise ValueError('Diagnostic output must remain in repository build')
 out.mkdir(exist_ok=False);classes=out/'classes';classes.mkdir();import build_worker
 jars=[]
 for dep in build_worker.dependencies():
  candidates=[ROOT.parent/'test-environment/cache/muxi-console/dependencies'/dep['file']]+list((ROOT.parent/'test-environment/resources/client/game/libraries').rglob(dep['file']))
  path=next((p for p in candidates if p.is_file()and p.stat().st_size==dep['bytes']and sha(p)==dep['sha256']),None)
  if path is None:raise RuntimeError('Existing pinned worker dependency absent')
  jars.append(path)
 jdk=ROOT.parent/'test-environment/toolchains/java-21/bin';sources=([]if args.runtime_jar else sorted((ROOT/'common/src/main/java/net/muxigame/console/runtime').glob('*.java')))+[ROOT/'tests/link/native/LinkDsAbiSmoke.java',ROOT/'tests/link/native/LinkDsCoreSmoke.java',ROOT/'tests/link/native/LinkDsGroupSmoke.java',ROOT/'tests/link/native/LinkDsStateSmoke.java'];inputs={str(p.relative_to(ROOT)):sha(p)for p in sources};cp=os.pathsep.join(map(str,jars))
 if args.runtime_jar:
  runtime=args.runtime_jar.resolve();inputs[str(runtime.relative_to(ROOT))]=sha(runtime);cp=str(runtime)+os.pathsep+cp
 p=subprocess.run([str(jdk/'javac.exe'),'-J-Xmx512m','-J-XX:ActiveProcessorCount=1','--release','21','-encoding','UTF-8','-proc:none','-cp',cp,'-d',str(classes),*map(str,sources)],capture_output=True,text=True);(out/'compile.log').write_text(p.stdout+p.stderr,encoding='utf-8')
 if p.returncode:raise RuntimeError('DS helper compilation failed; see compile.log')
 cp=os.pathsep.join([str(classes),cp]);temp=out/'jna';temp.mkdir()
 p=subprocess.run([str(jdk/'java.exe'),'-Xmx256m','-XX:ActiveProcessorCount=1','-Djna.tmpdir='+str(temp),'-cp',cp,'net.muxigame.console.tests.link.LinkDsAbiSmoke'],capture_output=True,text=True,timeout=30);(out/'abi.log').write_text(p.stdout+p.stderr,encoding='utf-8');(out/'inputs.json').write_text(json.dumps(inputs,indent=2),encoding='utf-8');print(p.stdout+p.stderr)
 if p.returncode:raise RuntimeError('DS ABI/socket probe failed; original log retained')
 if args.core:
  p=subprocess.run([str(jdk/'java.exe'),'-Xmx256m','-XX:ActiveProcessorCount=1','-Djna.tmpdir='+str(temp),'-cp',cp,'net.muxigame.console.tests.link.LinkDsCoreSmoke',str(jdk/'java.exe'),str(args.core.resolve()),args.core_sha,str(args.rom.resolve()),str((args.rom_right or args.rom).resolve()),str(out/'core'),str(args.wireless).lower()],capture_output=True,text=True,timeout=120);(out/'core.log').write_text(p.stdout+p.stderr,encoding='utf-8');print(p.stdout+p.stderr)
  if p.returncode:raise RuntimeError('Actual DS worker probe failed; original log retained')
  if args.group:
   p=subprocess.run([str(jdk/'java.exe'),'-Xmx256m','-XX:ActiveProcessorCount=1','-Djna.tmpdir='+str(temp),'-cp',cp,'net.muxigame.console.tests.link.LinkDsGroupSmoke',str(jdk/'java.exe'),str(args.core.resolve()),args.core_sha,str(args.rom.resolve()),str((args.rom_right or args.rom).resolve()),str(out/'group'),str(args.frontend_queued).lower()],capture_output=True,text=True,timeout=180);(out/'group.log').write_text(p.stdout+p.stderr,encoding='utf-8');print(p.stdout+p.stderr)
   if p.returncode:raise RuntimeError('Actual DS combined restore probe failed; original log retained')
  if args.base_core:
   p=subprocess.run([str(jdk/'java.exe'),'-Xmx256m','-XX:ActiveProcessorCount=1','-Djna.tmpdir='+str(temp),'-cp',cp,'net.muxigame.console.tests.link.LinkDsStateSmoke',str(jdk/'java.exe'),str(args.core.resolve()),args.core_sha,str(args.base_core.resolve()),args.base_core_sha,str(args.rom.resolve()),str((args.rom_right or args.rom).resolve()),str(out/'single-compat')],capture_output=True,text=True,timeout=180);(out/'single-compat.log').write_text(p.stdout+p.stderr,encoding='utf-8');print(p.stdout+p.stderr)
   if p.returncode:raise RuntimeError('Actual DS single-state compatibility probe failed; original log retained')
 if any(sha(ROOT/name)!=digest for name,digest in inputs.items()):raise RuntimeError('Runtime sources changed during explicit diagnostic')
 # The parent JVM has exited; only its own now-unlocked, ordinary JNA files are removed.
 for path in temp.iterdir():
  if path.is_symlink()or not path.is_file():raise RuntimeError('Unexpected file in owned diagnostic native directory')
  path.unlink()
 temp.rmdir();print('sourceDrift=[]; diagnostic JVM exited normally; see separate actual-core radio result; no commercial-game compatibility claim')
if __name__=='__main__':main()
