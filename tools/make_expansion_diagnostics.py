"""Compile our original DS/3DS/Wii diagnostics with the separately pinned portable SDK."""
from pathlib import Path
import argparse,hashlib,json,os,subprocess
from prepare_expansion_sdk import wii_diagnostic_compatibility
ROOT=Path(__file__).resolve().parents[1]
SDK=ROOT.parent/'test-environment/toolchains/muxi-console-expansion-devkitpro/install/opt/devkitpro'
def build(name):
 out=ROOT/'build'/name
 if out.exists() or Path(name).name!=name:raise ValueError('Choose a new output name')
 out.mkdir();env=dict(os.environ);env.update({'DEVKITPRO':str(SDK),'DEVKITARM':str(SDK/'devkitARM'),'DEVKITPPC':str(SDK/'devkitPPC')})
 def run(label,args):
  r=subprocess.run(list(map(str,args)),cwd=out,env=env,capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120)
  (out/(label+'.log')).write_text(r.stdout+r.stderr,encoding='utf-8')
  if r.returncode:raise RuntimeError(label+' failed: '+(r.stdout+r.stderr)[-3000:])
 src=ROOT/'tests/runtime/diagnostics/expansion'
 arm=SDK/'devkitARM/bin/arm-none-eabi-gcc.exe'
 for cpu,address,arch in [(9,'0x02000000','arm946e-s'),(7,'0x03800000','arm7tdmi')]:
  run('nds'+str(cpu),[arm,'-Os','-marm','-mcpu='+arch,'-nostdlib','-fno-builtin','-Wl,-Ttext='+address,'-Wl,-e,_start',src/('nds'+str(cpu)+'.S'),src/('nds'+str(cpu)+'.c'),'-o',out/('nds'+str(cpu)+'.elf')])
 run('nds-package',[SDK/'tools/bin/ndstool.exe','-c',out/'muxi-input.nds','-9',out/'nds9.elf','-7',out/'nds7.elf','-h','0x200','-g','MXI0','00','MUXI'])
 arm=SDK/'devkitARM/bin/arm-none-eabi-gcc.exe';flags=['-O2','-march=armv6k','-mtune=mpcore','-mfloat-abi=hard','-mfpu=vfp','-mword-relocations','-D__3DS__','-I'+str(SDK/'libctru/include')]
 run('n3ds-compile',[arm,*flags,'-c',src/'n3ds.c','-o',out/'n3ds.o'])
 run('n3ds-link',[arm,*flags,'-specs=3dsx.specs',out/'n3ds.o','-L'+str(SDK/'libctru/lib'),'-lctru','-lm','-o',out/'n3ds.elf'])
 run('n3ds-package',[SDK/'tools/bin/3dsxtool.exe',out/'n3ds.elf',out/'muxi-input.3dsx'])
 compatibility=wii_diagnostic_compatibility();wii=compatibility['libogc']
 specs=out/'wii-diagnostic.specs';specs.write_text('*link_start_rvl:\n-T '+compatibility['linkScript'].as_posix()+'\n')
 ppc=SDK/'devkitPPC/bin/powerpc-eabi-gcc.exe';flags=['-O2','-mrvl','-mcpu=750','-meabi','-mhard-float','-DGEKKO','-DHW_RVL','-I'+str(wii/'include')]
 run('wii-compile',[ppc,*flags,'-c',src/'wii.c','-o',out/'wii.o'])
 run('wii-link',[ppc,*flags,'-specs='+str(specs),out/'wii.o','-L'+str(wii/'lib/wii'),'-lwiiuse','-lbte','-logc','-lm','-o',out/'wii.elf'])
 run('wii-package',[SDK/'tools/bin/elf2dol.exe',out/'wii.elf',out/'muxi-input.dol'])
 rows=[{'path':str(p),'bytes':p.stat().st_size,'sha256':hashlib.sha256(p.read_bytes()).hexdigest()} for p in (out/'muxi-input.nds',out/'muxi-input.3dsx',out/'muxi-input.dol')]
 inputs=[*sorted(src.glob('*.c')),*sorted(src.glob('*.S')),Path(__file__).resolve(),ROOT/'tools/prepare_expansion_sdk.py']
 sources={p.relative_to(ROOT).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in inputs}
 (out/'diagnostics.json').write_text(json.dumps({'original':True,'biosIncluded':False,'gameAssetsIncluded':False,'sdkLock':str(SDK.parents[2]/'sdk-lock.json'),'sources':sources,'wiiDiagnosticCompatibility':compatibility['packages'],'outputs':rows},indent=2)+'\n');print(json.dumps(rows,indent=2))
if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--name',required=True);build(p.parse_args().name)
