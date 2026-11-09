"""Compare cold/warm official mGBA state loading with per-frame CPU/RAM input observations."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile

ROOT=Path(__file__).resolve().parents[1]
def sha(path):return hashlib.sha256(Path(path).read_bytes()).hexdigest()

JAVA=r'''package net.muxigame.console.tests.link;
import java.io.*;import java.nio.*;import java.nio.file.*;import java.util.*;import net.muxigame.console.runtime.*;
public class GbaInputTrace {
 static byte[][] parts(byte[] bytes)throws Exception{var in=new DataInputStream(new ByteArrayInputStream(bytes));if(in.readInt()!=0x4d585331)throw new IOException("not MXS1");byte[][] p=new byte[3][];for(int i=0;i<3;i++){int n=in.readInt();if(n<0||n>in.available())throw new IOException();p[i]=in.readNBytes(n);}return p;}
 static void trace(ProcessConsoleRuntime runtime,Path output,String stage,int step,int mask)throws Exception{
  byte[][] p=parts(runtime.saveState());byte[] publicSram=runtime.readSram();ByteBuffer core=ByteBuffer.wrap(p[0]).order(ByteOrder.LITTLE_ENDIAN);if(core.getInt(0)!=0x0100000b)throw new IOException("expected pinned actual raw GBA state");
  String row="{\"phase\":\""+stage+"\",\"step\":"+step+",\"requestMask\":"+mask+",\"nativeFrame\":"+Integer.toUnsignedLong(core.getInt(0x1fc))+",\"cycles\":"+Integer.toUnsignedLong(core.getInt(0xc))+",\"pc\":"+Integer.toUnsignedLong(core.getInt(0x5c))+",\"r3\":"+Integer.toUnsignedLong(core.getInt(0x2c))+",\"r11\":"+Integer.toUnsignedLong(core.getInt(0x4c))+",\"r12\":"+Integer.toUnsignedLong(core.getInt(0x50))+",\"ioKeyInput\":"+(core.getShort(0x530)&65535)+",\"ewramWord\":"+Integer.toUnsignedLong(core.getInt(0x21000))+",\"sram\":["+(p[1][0]&255)+","+(p[1][1]&255)+","+(p[1][2]&255)+","+(p[1][3]&255)+"]}\n";
  row=row.substring(0,row.length()-2)+",\"publicSramCounter\":"+(publicSram[1]&255)+"}\n";Files.writeString(output,row,StandardOpenOption.CREATE,StandardOpenOption.APPEND);
 }
 public static void main(String[] args)throws Exception{
  Path java=Path.of(args[0]),base=Path.of(args[1]),rom=Path.of(args[2]),state=Path.of(args[3]),output=Path.of(args[4]);boolean warm=Boolean.parseBoolean(args[5]);Files.createDirectories(output.getParent());
  List<Path> cp=Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator)).map(Path::of).toList();
  var runtime=ProcessConsoleRuntime.open(new LaunchConfig(java,cp,base,rom,output.getParent().resolve("system"),output.getParent().resolve("save"),Map.of("mgba_use_bios","OFF")));
  try{
   if(warm)runtime.step(InputFrame.idle());runtime.loadState(Files.readAllBytes(state));trace(runtime,output,"loaded",0,0);
   int step=0;for(String phase:List.of("neutral-before","down","up")){int mask=phase.equals("down")?256:0;for(int i=0;i<12;i++){runtime.step(new InputFrame(List.of(new PortInput(mask))));trace(runtime,output,phase,++step,mask);}}Files.write(output.getParent().resolve("latest-export.mxs1"),runtime.saveState());
  }finally{runtime.close();if(runtime.exitCode().orElse(-1)!=0||runtime.wasForcedShutdown()||!runtime.nativeTemporaryFilesCleaned())throw new IOException("trace worker failed native closure");}
  System.out.println("trace worker naturally closed");
 }
}'''

SEED=r'''package net.muxigame.console.tests.link;
import java.io.*;import java.nio.file.*;import java.util.*;import net.muxigame.console.runtime.*;
class GbaOrdinarySeed {
 public static void main(String[] args)throws Exception{
  Path java=Path.of(args[0]),base=Path.of(args[1]),rom=Path.of(args[2]),state=Path.of(args[3]);List<Path> cp=Arrays.stream(System.getProperty("java.class.path").split(File.pathSeparator)).map(Path::of).toList();
  var runtime=ProcessConsoleRuntime.open(new LaunchConfig(java,cp,base,rom,state.getParent().resolve("seed-system"),state.getParent().resolve("seed-save"),Map.of("mgba_use_bios","OFF")));
  try{for(int i=0;i<30;i++)runtime.step(InputFrame.idle());for(int i=0;i<3;i++)runtime.step(new InputFrame(List.of(new PortInput(256))));for(int i=0;i<3;i++)runtime.step(InputFrame.idle());Files.write(state,runtime.saveState());GbaInputTrace.trace(runtime,state.getParent().resolve("seed.jsonl"),"original-single-seed",36,0);}
  finally{runtime.close();if(runtime.exitCode().orElse(-1)!=0||runtime.wasForcedShutdown()||!runtime.nativeTemporaryFilesCleaned())throw new IOException("ordinary seed worker did not close normally");}
 }
}'''

def main():
    ap=argparse.ArgumentParser();ap.add_argument('--output',type=Path,required=True);ap.add_argument('--state',type=Path);ap.add_argument('--ordinary',action='store_true');ap.add_argument('--rom',type=Path,required=True);ap.add_argument('--runtime-current',default='build/link-gba-runtime-03/classes');ap.add_argument('--skip-previous',action='store_true');ap.add_argument('--expect-count',type=int);args=ap.parse_args()
    if bool(args.state)==args.ordinary:raise ValueError('Choose an existing --state or an --ordinary base-to-base seed')
    out=args.output.resolve()
    if (ROOT/'build').resolve()not in out.parents:raise RuntimeError('Trace remains in repository build output')
    out.mkdir(parents=True,exist_ok=False);source=out/'GbaInputTrace.java';source.write_text(JAVA+'\n'+SEED.replace('package net.muxigame.console.tests.link;','').replace('import java.io.*;import java.nio.file.*;import java.util.*;import net.muxigame.console.runtime.*;',''),encoding='utf-8')
    if args.ordinary:args.state=out/'ordinary-base-export.mxs1'
    import build_worker
    dependencies=[]
    for dep in build_worker.dependencies():
        candidates=[ROOT.parent/'test-environment/cache/muxi-console/dependencies'/dep['file']]+list((ROOT.parent/'test-environment/resources/client/game/libraries').rglob(dep['file']))
        path=next((p for p in candidates if p.is_file()and p.stat().st_size==dep['bytes']and sha(p)==dep['sha256']),None)
        if path is None:raise RuntimeError('Existing worker dependency absent')
        dependencies.append(path)
    pin=json.loads((ROOT/'third-party/cores.json').read_text(encoding='utf-8'))['cores']['mgba']['platforms']['windows-x86_64'];archive=ROOT.parent/pin['archive'];assert sha(archive)==pin['archiveSha256']
    base=out/pin['libraryName']
    with zipfile.ZipFile(archive)as z:base.write_bytes(z.read(pin['libraryName']))
    assert sha(base)==pin['librarySha256'];jdk=ROOT.parent/'test-environment/toolchains/java-21/bin';records=[]
    for label,relative in [('current',args.runtime_current),*([]if args.skip_previous else[('previous','build/link-runtime-smoke-04/classes')])]:
        runtime=ROOT/relative;classes=out/(label+'-classes');classes.mkdir();cp=os.pathsep.join(map(str,[runtime,*dependencies]));compile=[str(jdk/'javac.exe'),'-J-Xmx512m','-J-XX:ActiveProcessorCount=1','--release','21','-encoding','UTF-8','-cp',cp,'-d',str(classes),str(source)]
        p=subprocess.run(compile,capture_output=True,text=True);(out/(label+'-compile.log')).write_text(p.stdout+p.stderr,encoding='utf-8');assert p.returncode==0
        if label=='current'and args.ordinary:
            cpseed=os.pathsep.join([str(classes),cp]);p=subprocess.run([str(jdk/'java.exe'),'-Xmx256m','-XX:ActiveProcessorCount=1','-cp',cpseed,'net.muxigame.console.tests.link.GbaOrdinarySeed',str(jdk/'java.exe'),str(base),str(args.rom.resolve()),str(args.state)],capture_output=True,text=True,timeout=90);(out/'seed.log').write_text(p.stdout+p.stderr,encoding='utf-8');assert p.returncode==0,(p.stdout+p.stderr)[-2000:]
        for warm in (False,True):
            trace=out/(label+('-warm'if warm else'-cold'))/'frames.jsonl';classpath=os.pathsep.join([str(classes),cp]);command=[str(jdk/'java.exe'),'-Xmx256m','-XX:ActiveProcessorCount=1','-cp',classpath,'net.muxigame.console.tests.link.GbaInputTrace',str(jdk/'java.exe'),str(base),str(args.rom.resolve()),str(args.state.resolve()),str(trace),str(warm).lower()]
            p=subprocess.run(command,capture_output=True,text=True,timeout=90);trace.parent.mkdir(exist_ok=True);(trace.parent/'driver.log').write_text(p.stdout+p.stderr,encoding='utf-8')
            rows=[json.loads(line)for line in trace.read_text().splitlines()]if trace.exists()else[];record={'runtime':relative,'runtimeSHA256':sha(runtime)if runtime.is_file()else None,'warmBeforeRestore':warm,'exitCode':p.returncode,'frames':str(trace),'framesSha256':sha(trace)if trace.exists()else None,'counters':{phase:[r['sram'][1]for r in rows if r['phase']==phase]for phase in ('loaded','neutral-before','down','up')}};records.append(record);print(json.dumps(record),flush=True)
            if args.expect_count is not None:
                assert p.returncode==0 and rows,'Actual trace worker must close normally'
                for row in rows:
                    if row['phase']in('down','up'):assert row['sram'][1]==row['publicSramCounter']==((row['ewramWord']>>8)&255)==args.expect_count,row
                record['actualCPU_PublicSRAM_ExportAgreement']=True
    (out/'result.json').write_text(json.dumps({'generatedOrdinarySeed':args.ordinary,'inputState':str(args.state.resolve()),'baseSHA256':sha(base),'romSHA256':sha(args.rom),'stateSHA256':sha(args.state),'generatedDiagnosticSourceSHA256':sha(source),'cases':records},indent=2),encoding='utf-8')

if __name__=='__main__':main()
