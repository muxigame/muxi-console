"""Test final packaged RuntimeFiles and isolated MAME/N64/FDS/Lynx workers; never launches Minecraft."""
from pathlib import Path
import argparse, hashlib, json, os, shutil, subprocess, sys

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar',type=Path,required=True)
    parser.add_argument('--sha256',required=True)
    parser.add_argument('--name',required=True)
    parser.add_argument('--platforms',nargs='+',choices=['arcade','n64','fds','lynx'],default=['n64','fds','arcade','lynx'])
    args=parser.parse_args();repo=Path(__file__).resolve().parents[1];workspace=repo.parent
    artifact=args.jar.resolve();actual=hashlib.sha256(artifact.read_bytes()).hexdigest()
    if actual!=args.sha256 or len(args.sha256)!=64:raise ValueError('Final artifact identity differs')
    if Path(args.name).name!=args.name or not args.name or any(c not in 'abcdefghijklmnopqrstuvwxyz0123456789-' for c in args.name):raise ValueError('Choose a simple new lowercase output name')
    output=repo/'build'/args.name;output.mkdir(exist_ok=False);inputs=output/'inputs';inputs.mkdir()
    from platform_smoke import n64_diagnostic
    from platform_smoke_fds import diagnostic_disk,diagnostic_firmware
    from platform_smoke_software import lynx_diagnostic,lynx_diagnostic_firmware
    (inputs/'original-vi.z64').write_bytes(n64_diagnostic());(inputs/'original-transfer.fds').write_bytes(diagnostic_disk())
    (inputs/'original.o').write_bytes(lynx_diagnostic())
    firmware=output/'game/muxi-console/firmware';firmware.mkdir(parents=True);(firmware/'disksys.rom').write_bytes(diagnostic_firmware())
    (firmware/'lynxboot.img').write_bytes(lynx_diagnostic_firmware())
    sys.path.insert(0,str(workspace/'better-mc-remake/scripts'));import local_mc_runtime as sdk
    game=workspace/'test-environment/resources/client/game'
    gson=[p for p in sdk.client_libraries(game,'BatterMC5Remake',sdk.client_metadata(game,'BatterMC5Remake')) if p.name.startswith('gson-')]
    if len(gson)!=1:raise ValueError('Expected exactly one Gson artifact in the current client metadata')
    java=workspace/'test-environment/toolchains/java-21/bin/java.exe';classes=output/'classes';classes.mkdir()
    cp=os.pathsep.join(map(str,[artifact,*gson]));source=repo/'tests/runtime/MediaLaunchSmoke.java'
    compiled=subprocess.run([str(java.with_name('javac.exe')),'-J-Xmx512m','-J-XX:ActiveProcessorCount=1','--release','21','-encoding','UTF-8','-proc:none','-cp',cp,'-d',str(classes),str(source)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=180)
    (output/'compile.log').write_text(compiled.stdout+compiled.stderr,encoding='utf-8')
    if compiled.returncode:raise RuntimeError(compiled.stdout+compiled.stderr)
    result=subprocess.run([str(java),'-Xmx256m','-XX:ActiveProcessorCount=1','-Dmuxi.console.workerDiagnostics=true','-cp',str(classes)+os.pathsep+cp,'net.muxigame.console.runtime.MediaLaunchSmoke',str(output),','.join(args.platforms),str(artifact)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=480,cwd=repo)
    (output/'native.log').write_text(result.stdout+result.stderr,encoding='utf-8');print(result.stdout);print(result.stderr)
    record={'artifact':str(artifact),'sha256':actual,'bytes':artifact.stat().st_size,'platforms':args.platforms,'exitCode':result.returncode,'minecraftStarted':False,'productionPublished':False}
    if result.returncode==0:
        report=json.loads((output/'result.json').read_text(encoding='utf-8'))
        if not report['allWorkersClosed']:raise RuntimeError('Cannot prune while worker closure is unknown')
        payload=(output/'game/natives').resolve()
        if not payload.is_relative_to(output.resolve()) or payload.name!='natives':raise ValueError('Unsafe runtime payload path')
        shutil.rmtree(payload);record['runtimePayloadPruned']=True
    (output/'run.json').write_text(json.dumps(record,indent=2),encoding='utf-8')
    if hashlib.sha256(artifact.read_bytes()).hexdigest()!=actual:raise ValueError('Final artifact changed during native test')
    raise SystemExit(result.returncode)

if __name__=='__main__':
    sys.stdout.reconfigure(encoding='utf-8');main()
