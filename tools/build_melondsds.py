"""Build melonDS DS from an explicit complete dependency lock, never infer old binary sources."""
from pathlib import Path
import argparse,concurrent.futures,hashlib,json,os,subprocess,tarfile,urllib.request
ROOT=Path(__file__).resolve().parents[1]
CACHE=ROOT.parent/'test-environment/cache/muxi-console/expansion/melondsds/rebuild'
DEPS={
 'frontend':('JesseTG/melonds-ds','f394adbacb5722ee97c1b37c8064da9a25818310'),
 'melonds':('JesseTG/melonDS','jtg/fix-uninitialized-opengl'),
 'libretro-common':('libretro/libretro-common','fa8a1b5'),
 'embed-binaries':('andoalon/embed-binaries','078b62b'),
 'glm':('g-truc/glm','6f14f47'),
 'libslirp':('JesseTG/libslirp-mirror','e61dbd4'),
 'pntr':('robloach/pntr','a47fe16'),
 'fmt':('fmtlib/fmt','12.2.0'),
 'yamc':('yohhoy/yamc','4e015a7'),
 'span-lite':('martinmoene/span-lite','f63fe29'),
 'date':('HowardHinnant/date','v3.0.5'),
 'zlib':('madler/zlib','v1.3.2')}
def sha(path):
 h=hashlib.sha256()
 with path.open('rb') as f:
  for block in iter(lambda:f.read(1048576),b''):h.update(block)
 return h.hexdigest()
def download(url,path):
 side=path.with_name(path.name+'.origin.json')
 if path.exists():
  old=json.loads(side.read_text())
  if old['url']!=url or old['sha256']!=sha(path):raise ValueError('Existing source bytes differ: '+path.name)
  return old
 req=urllib.request.Request(url,headers={'User-Agent':'MuxiConsole-CorrespondingSource/1','Accept-Encoding':'identity'});tmp=path.with_suffix(path.suffix+'.part')
 with urllib.request.urlopen(req,timeout=60) as source,tmp.open('xb') as target:
  count=0
  while block:=source.read(1048576):
   count+=len(block)
   if count>256*1024**2:raise ValueError('Dependency exceeds 256 MiB')
   target.write(block)
 tmp.replace(path);record={'url':url,'bytes':path.stat().st_size,'sha256':sha(path)};side.write_text(json.dumps(record,indent=2)+'\n');return record
def api(url,path):download(url,path);return json.loads(path.read_text(encoding='utf-8'))
def dependency(name,repo,ref):
 CACHE.mkdir(parents=True,exist_ok=True);meta=api('https://api.github.com/repos/'+repo+'/commits/'+urllib.parse.quote(ref,safe=''),CACHE/(name+'-commit.json'));commit=meta['sha']
 if len(commit)!=40:raise ValueError('Invalid resolved commit')
 archive=CACHE/(name+'-'+commit+'.tar.gz');record=download('https://codeload.github.com/'+repo+'/tar.gz/'+commit,archive)
 tree=api('https://api.github.com/repos/'+repo+'/git/trees/'+commit+'?recursive=1',CACHE/(name+'-tree.json'))
 if tree.get('truncated'):raise ValueError('Source tree listing truncated')
 gitlinks=[e for e in tree['tree'] if e.get('mode')=='160000']
 out=CACHE/'source'/name
 if not out.exists():
  out.mkdir(parents=True)
  with tarfile.open(archive) as source:
   members=source.getmembers();prefix=members[0].name.split('/')[0]+'/'
   for member in members:
    if member.name==prefix[:-1]:continue
    if not member.name.startswith(prefix):raise ValueError('Unexpected dependency root')
    member.name=member.name[len(prefix):];source.extract(member,out,filter='data')
 return {'name':name,'repository':'https://github.com/'+repo,'requestedRef':ref,'commit':commit,'archive':record,'sourceDirectory':str(out),'gitlinks':gitlinks}
def prepare():
 lock=CACHE/'source-lock.json'
 if lock.exists():return json.loads(lock.read_text())
 with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:rows=list(pool.map(lambda row:dependency(row[0],*row[1]),DEPS.items()))
 value={'schema':1,'purpose':'New binary built from these explicit dependencies; does not assert old official binary correspondence','dependencies':rows}
 lock.write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8');return value
def build(llvm_root=None,make_path=None,cmake_path=None,git_root=None):
 lock=prepare()
 if any(row['gitlinks'] for row in lock['dependencies']):raise ValueError('Resolve recursive submodule sources before compiling')
 tools=ROOT.parent/'test-environment/toolchains';llvm=next((Path(llvm_root) if llvm_root else tools/'muxi-console-rebuild-llvm-mingw-20250613').rglob('x86_64-w64-mingw32-clang.exe'));make=Path(make_path) if make_path else next((tools/'muxi-console-rebuild-gnu-make-4.4.1').rglob('mingw32-make.exe'))
 cmake=Path(cmake_path or 'C:/Program Files/CMake/bin/cmake.exe');output=CACHE/'build';output.mkdir(exist_ok=True)
 args=[str(cmake),'-S',str(CACHE/'source/frontend'),'-B',str(output),'-G','MinGW Makefiles','-DCMAKE_BUILD_TYPE=Release','-DCMAKE_MAKE_PROGRAM='+str(make),'-DCMAKE_C_COMPILER='+str(llvm),'-DCMAKE_CXX_COMPILER='+str(llvm.with_name('x86_64-w64-mingw32-clang++.exe')),'-DENABLE_TESTING=OFF','-DTRACY_ENABLE=OFF','-DENABLE_OPENGL=ON','-DENABLE_JIT=ON','-DFETCHCONTENT_FULLY_DISCONNECTED=ON']
 for row in lock['dependencies']:
  if row['name']!='frontend':
   directory=Path(row['sourceDirectory']);directory=directory if directory.is_absolute() else CACHE/directory
   for spelling in sorted({row['name'].upper(),row['name'].upper().replace('-','_')}):args.append('-DFETCHCONTENT_SOURCE_DIR_'+spelling+'='+directory.as_posix())
 args.append('-DCMAKE_MODULE_PATH='+(CACHE/'source/melonds/cmake').as_posix()+';'+(CACHE/'source/embed-binaries/cmake').as_posix())
 git=Path(git_root or 'C:/Program Files/Git');env=dict(os.environ);env['PATH']=os.pathsep.join([str(llvm.parent),str(make.parent),str(git/'mingw64/bin'),str(git/'usr/bin'),env['PATH']])
 for name,command in [('configure',args),('compile',[str(cmake),'--build',str(output),'--parallel','1'])]:
  with (CACHE/(name+'.log')).open('w',encoding='utf-8') as log:result=subprocess.run(command,env=env,stdout=log,stderr=subprocess.STDOUT)
  if result.returncode:raise RuntimeError(name+' failed; see owned rebuild cache log')
 dlls=list(output.rglob('melondsds_libretro.dll'))
 if len(dlls)!=1:raise ValueError('Expected unique compiled DLL')
 print(json.dumps({'library':str(dlls[0]),'bytes':dlls[0].stat().st_size,'sha256':sha(dlls[0])},indent=2))
if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--prepare-only',action='store_true');p.add_argument('--source-tree',help='Offline package root with source-lock.json and source/');p.add_argument('--llvm-root');p.add_argument('--make');p.add_argument('--cmake');p.add_argument('--git-root');a=p.parse_args()
 if a.source_tree:
  CACHE=Path(a.source_tree).resolve()
  if not (CACHE/'source-lock.json').is_file():raise ValueError('Offline source package has no explicit lock')
 if a.prepare_only:print(json.dumps(prepare(),indent=2))
 else:build(a.llvm_root,a.make,a.cmake,a.git_root)
