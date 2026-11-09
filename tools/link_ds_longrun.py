"""Run exact candidate worker bytes offline; JIT alternatives affect this owned process tree only."""
from pathlib import Path
import argparse,hashlib,json,os,subprocess,zipfile,time
ROOT=Path(__file__).resolve().parents[1]
def sha(p):
 d=hashlib.sha256()
 with p.open('rb')as f:
  for b in iter(lambda:f.read(65536),b''):d.update(b)
 return d.hexdigest()
def main():
 p=argparse.ArgumentParser();p.add_argument('--product',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--left',type=Path,required=True);p.add_argument('--right',type=Path,required=True);p.add_argument('--variant',choices=['baseline','exclude-invoke','c1'],default='baseline');p.add_argument('--frames',type=int,default=10000);p.add_argument('--core',type=Path);p.add_argument('--core-sha');a=p.parse_args()
 out=a.output.resolve();product=a.product.resolve();left=a.left.resolve();right=a.right.resolve()
 if (ROOT/'build').resolve()not in out.parents or not 10000<=a.frames<=30000:raise ValueError('Owned build output and 10000..30000 actual frames required')
 out.mkdir(exist_ok=False);payload=out/'payload';payload.mkdir();classes=out/'classes';classes.mkdir();data=out/'data';logs=out/'private-crash-logs';logs.mkdir();deps=[]
 if bool(a.core)!=bool(a.core_sha)or a.core and sha(a.core)!=a.core_sha:raise ValueError('Explicit diagnostic native override requires its exact actual SHA')
 with zipfile.ZipFile(product)as z:
  for name in z.namelist():
   if name.startswith('worker/')and name.endswith('.jar'):
    target=payload/Path(name).name;target.write_bytes(z.read(name));deps.append(target)
  core=payload/'melondsds_link.dll';core.write_bytes(z.read('cores/windows-x86_64/melondsds_link.dll'))
 if a.core:core.write_bytes(a.core.read_bytes())
 runtime=payload/'runtime.jar';deps.remove(runtime);deps.insert(0,runtime)
 jdk=ROOT.parent/'test-environment/toolchains/java-21/bin';source=ROOT/'tests/link/native/LinkDsLongRun.java';cp=os.pathsep.join(map(str,deps));inputs={'product':sha(product),'runtime':sha(runtime),'core':sha(core),'left':sha(left),'right':sha(right),'helper':sha(source),'driver':sha(Path(__file__))};(out/'driver-used.py').write_bytes(Path(__file__).read_bytes());(out/'helper-used.java').write_bytes(source.read_bytes())
 result=subprocess.run([str(jdk/'javac.exe'),'-J-Xmx512m','-J-XX:ActiveProcessorCount=1','--release','21','-encoding','UTF-8','-proc:none','-cp',cp,'-d',str(classes),str(source)],capture_output=True,text=True);(out/'compile.log').write_text(result.stdout+result.stderr,encoding='utf-8')
 if result.returncode:raise RuntimeError('Helper compilation failed; preserved compile.log')
 extra=[]
 if a.variant=='exclude-invoke':extra=['-XX:CompileCommand=exclude,com.sun.jna.Function::invoke']
 if a.variant=='c1':extra=['-XX:TieredStopAtLevel=1']
 env=os.environ.copy()
 for key in ('JAVA_TOOL_OPTIONS','JDK_JAVA_OPTIONS','_JAVA_OPTIONS'):env.pop(key,None)
 flags=extra+['-XX:ErrorFile='+str(logs/'hs_err_pid%p.log'),'-Xlog:jit+compilation=debug:file=private-crash-logs/jit-%p.log']
 # No inherited host JVM flags or system/global environment mutation. Child JVMs inherit only this scoped value.
 env['JDK_JAVA_OPTIONS']=' '.join('"'+flag+'"'for flag in flags)
 report={'schema':1,'variant':a.variant,'inputs':inputs,'childJvmOptions':flags,'startedUtc':time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime())};(out/'invocation.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
 cmd=[str(jdk/'java.exe'),'-Xmx256m','-XX:ActiveProcessorCount=1','-cp',os.pathsep.join([str(classes),cp]),'net.muxigame.console.tests.link.LinkDsLongRun',str(jdk/'java.exe'),str(core),inputs['core'],str(left),str(right),str(data),str(a.frames)]
 with(out/'run.log').open('w',encoding='utf-8')as log:result=subprocess.run(cmd,cwd=out,env=env,stdout=log,stderr=subprocess.STDOUT,timeout=600)
 report.update(exitCode=result.returncode,finishedUtc=time.strftime('%Y-%m-%dT%H:%M:%SZ',time.gmtime()),productDrift=sha(product)!=inputs['product'],sourceDrift=sha(source)!=inputs['helper']);(out/'invocation.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
 print(json.dumps({'variant':a.variant,'exitCode':result.returncode,'result':str(data/'result.json'),'privateCrashLogCount':len(list(logs.glob('hs_err*')))}))
 if result.returncode:raise SystemExit(result.returncode)
if __name__=='__main__':main()
