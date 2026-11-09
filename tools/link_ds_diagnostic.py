"""Build two original ARM9/ARM7 WiFi challenge ROMs with the existing pinned devkitARM SDK."""
from pathlib import Path
import argparse,hashlib,json,os,subprocess
ROOT=Path(__file__).resolve().parents[1]
SDK=ROOT.parent/'test-environment/toolchains/muxi-console-expansion-devkitpro/install/opt/devkitpro'
def main():
 ap=argparse.ArgumentParser();ap.add_argument('--output',type=Path,required=True);args=ap.parse_args();out=args.output.resolve()
 if (ROOT/'build').resolve()not in out.parents:raise ValueError('Original diagnostic output must remain in repository build')
 out.mkdir(exist_ok=False);src=ROOT/'tests/link/diagnostics';arm=SDK/'devkitARM/bin/arm-none-eabi-gcc.exe';rows=[]
 for role in (0,1):
  for cpu,address,arch in [(9,'0x02000000','arm946e-s'),(7,'0x03800000','arm7tdmi')]:
   command=[str(arm),'-Os','-marm','-mcpu='+arch,'-DROLE='+str(role),'-nostdlib','-fno-builtin','-Wl,-Ttext='+address,'-Wl,-e,_start',str(src/('nds'+str(cpu)+'.S')),str(src/('nds'+str(cpu)+'.c')),'-o',str(out/f'role-{role}-cpu{cpu}.elf')]
   p=subprocess.run(command,capture_output=True,text=True,timeout=60);(out/f'compile-{role}-{cpu}.log').write_text(p.stdout+p.stderr,encoding='utf-8')
   if p.returncode:raise RuntimeError('Original ARM diagnostic compile failed; log retained')
  rom=out/f'original-wireless-{role}.nds';p=subprocess.run([str(SDK/'tools/bin/ndstool.exe'),'-c',str(rom),'-9',str(out/f'role-{role}-cpu9.elf'),'-7',str(out/f'role-{role}-cpu7.elf'),'-h','0x200','-g','MXW'+str(role),'00','MUXI'],capture_output=True,text=True,timeout=60);(out/f'package-{role}.log').write_text(p.stdout+p.stderr,encoding='utf-8')
  if p.returncode:raise RuntimeError('Original wireless package failed; log retained')
  rows.append({'role':role,'path':str(rom),'bytes':rom.stat().st_size,'sha256':hashlib.sha256(rom.read_bytes()).hexdigest()})
 (out/'diagnostics.json').write_text(json.dumps({'original':True,'vendorBIOSBundled':False,'sourceInputs':{p.name:hashlib.sha256(p.read_bytes()).hexdigest()for p in sorted(src.glob('nds*'))},'outputs':rows},indent=2)+'\n',encoding='utf-8');print(json.dumps(rows,indent=2))
if __name__=='__main__':main()
