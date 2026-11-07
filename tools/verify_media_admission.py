"""Admit the pinned original 21-platform matrix through the exact product JAR; never starts a core or Minecraft."""
from pathlib import Path
import argparse, hashlib, json, os, subprocess, sys

def sha(path):
    with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--jar',type=Path,required=True);parser.add_argument('--sha256',required=True);parser.add_argument('--name',required=True);parser.add_argument('--matrix',type=Path)
    args=parser.parse_args();repo=Path(__file__).resolve().parents[1];workspace=repo.parent;artifact=args.jar.resolve()
    if sha(artifact)!=args.sha256:raise ValueError('Final product artifact identity differs')
    if not args.name or Path(args.name).name!=args.name or any(c not in 'abcdefghijklmnopqrstuvwxyz0123456789-' for c in args.name):raise ValueError('Choose a new simple output name')
    matrix=(args.matrix or repo/'build/platform-smoke/platform-matrix.json').resolve();data=json.loads(matrix.read_text(encoding='utf-8'))
    if data.get('platformCount')!=21 or len(data.get('platforms',[]))!=21:raise ValueError('Expected complete original 21-platform matrix')
    items=[];allowed=(repo/'build/platform-smoke').resolve()
    for row in data['platforms']:
        source=(repo/row['content']).resolve()
        if not source.is_relative_to(allowed) or source.is_symlink() or not source.is_file() or sha(source)!=row['contentSha256']:raise ValueError('Diagnostic content differs from matrix pin: '+row['platform'])
        items.append({'platform':row['platform'],'source':str(source),'sourceSha256':row['contentSha256']})
    output=repo/'build'/args.name;output.mkdir(exist_ok=False);(output/'items.json').write_text(json.dumps(items,indent=2),encoding='utf-8')
    sys.path.insert(0,str(workspace/'better-mc-remake/scripts'));import local_mc_runtime as sdk
    game=workspace/'test-environment/resources/client/game';gson=[p for p in sdk.client_libraries(game,'BatterMC5Remake',sdk.client_metadata(game,'BatterMC5Remake')) if p.name.startswith('gson-')]
    if len(gson)!=1:raise ValueError('Expected one current Gson dependency')
    java=workspace/'test-environment/toolchains/java-21/bin/java.exe';classes=output/'classes';classes.mkdir();cp=os.pathsep.join(map(str,[artifact,*gson]))
    compiled=subprocess.run([str(java.with_name('javac.exe')),'-J-Xmx512m','-J-XX:ActiveProcessorCount=1','--release','21','-encoding','UTF-8','-proc:none','-cp',cp,'-d',str(classes),str(repo/'tests/runtime/MediaAdmissionChecks.java')],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=180)
    (output/'compile.log').write_text(compiled.stdout+compiled.stderr,encoding='utf-8')
    if compiled.returncode:raise RuntimeError(compiled.stdout+compiled.stderr)
    result=subprocess.run([str(java),'-Xmx128m','-XX:ActiveProcessorCount=1','-ea','-cp',str(classes)+os.pathsep+cp,'net.muxigame.console.runtime.MediaAdmissionChecks',str(output),str(output/'items.json'),str(artifact)],capture_output=True,text=True,encoding='utf-8',errors='replace',timeout=120,cwd=repo)
    (output/'checks.log').write_text(result.stdout+result.stderr,encoding='utf-8');print(result.stdout);print(result.stderr)
    (output/'run.json').write_text(json.dumps({'artifact':str(artifact),'sha256':args.sha256,'matrix':str(matrix),'matrixSha256':sha(matrix),'exitCode':result.returncode,'nativeCoreStarted':False,'minecraftStarted':False},indent=2),encoding='utf-8')
    if sha(artifact)!=args.sha256:raise ValueError('Product artifact changed during admission checks')
    raise SystemExit(result.returncode)

if __name__=='__main__':
    sys.stdout.reconfigure(encoding='utf-8');main()
