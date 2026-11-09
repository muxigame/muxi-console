"""Collect fixed complete expansion source trees and original notices for the libretro targets."""
from pathlib import Path,PurePosixPath
import argparse,configparser,concurrent.futures,hashlib,json,re,subprocess,tarfile,urllib.parse
import fetch_expansion_cores as fetch
ROOT=Path(__file__).resolve().parents[1]
CACHE=ROOT.parent/'test-environment/cache/muxi-console/third-party-source'
LEGAL=re.compile(r'^(?:(?:LICEN[CS]ES?|COPYING|COPYRIGHTS?|NOTICES?|OFL)(?:[._-].*)?|.*[_-](?:licen[cs]es?|copyrights?|notices?)(?:[._-].*)?)$',re.I)
def gitlinks(repository,commit):
 key=hashlib.sha256((repository+'@'+commit).encode()).hexdigest();base=CACHE/'expansion-git'/key;base.parent.mkdir(parents=True,exist_ok=True)
 listing=base.with_suffix('.tree')
 if not listing.exists():
  if not base.exists():subprocess.run(['git','init','--bare',str(base)],capture_output=True,check=True)
  result=subprocess.run(['git','-c','credential.helper=','--git-dir='+str(base),'fetch','--depth=1','--no-tags',repository,commit],capture_output=True,timeout=180)
  if result.returncode:raise ValueError('Cannot obtain exact official git tree '+repository+' '+commit+': '+result.stderr.decode(errors='replace')[-400:])
  actual=subprocess.check_output(['git','--git-dir='+str(base),'rev-parse','FETCH_HEAD']).decode().strip()
  if actual!=commit:raise ValueError('Resolved git tree differs')
  listing.write_bytes(subprocess.check_output(['git','--git-dir='+str(base),'ls-tree','-r','-z','FETCH_HEAD']))
 result={}
 for row in listing.read_bytes().split(b'\0'):
  if not row:continue
  fields,path=row.split(b'\t',1);mode,kind,sha=fields.split()
  if mode==b'160000':result[path.decode()]=sha.decode()
 return result
def slug(repo):
 match=re.fullmatch(r'https://github.com/([^/]+/[^/]+?)(?:\.git)?',repo.rstrip('/'))
 if not match:raise ValueError('Source repository needs an explicit archive mapping: '+repo)
 return match.group(1)
def notices(component,archive):
 paths=[]
 with tarfile.open(archive,'r:*') as source:
  for member in source.getmembers():
   parts=PurePosixPath(member.name).parts
   if '..' in parts or PurePosixPath(member.name).is_absolute():raise ValueError('Unsafe source archive path')
   if member.isfile() and (LEGAL.fullmatch(parts[-1]) or any(p.upper()=='LICENSES' for p in parts[:-1])):
    if member.size>1024**2:raise ValueError('Oversized notice')
    dest=ROOT/'third-party/licenses'/component/PurePosixPath(*parts[1:]);dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(source.extractfile(member).read());paths.append(dest.relative_to(ROOT).as_posix())
 return paths
def snapshot(component,repo,commit,active=()):
 if (repo,commit) in active:raise ValueError('Source submodule cycle')
 name=component+'-'+commit+'.tar.gz';archive=CACHE/'archives'/name
 url='https://gitlab.com/bzip2/bzip2/-/archive/'+commit+'/bzip2-'+commit+'.tar.gz' if repo.rstrip('/')=='https://gitlab.com/bzip2/bzip2' else 'https://codeload.github.com/'+slug(repo)+'/tar.gz/'+commit
 record=fetch.fetch(url,archive)
 result={'repository':repo,'revision':commit,'archive':'archives/'+name,**record,'licenseFiles':notices(component,archive),'submodules':[],'nonGitlinkDeclarations':[]}
 links=gitlinks(repo,commit);modules={}
 with tarfile.open(archive) as source:
  for member in source.getmembers():
   if not member.isfile() or PurePosixPath(member.name).name!='.gitmodules':continue
   if member.size>65536:raise ValueError('Oversized submodule manifest')
   relative=PurePosixPath(*PurePosixPath(member.name).parts[1:]);cfg=configparser.ConfigParser();cfg.read_string(source.extractfile(member).read().decode())
   for section in cfg.sections():modules[(relative.parent/PurePosixPath(cfg[section]['path'])).as_posix()]=cfg[section]['url']
 for path,declared in modules.items():
  if path not in links:result['nonGitlinkDeclarations'].append({'path':path,'declaredRepository':declared,'reason':'vendored manifest; not an omitted gitlink in this exact tree'})
 for path,revision in links.items():
  if component=='dolphin' and path in {'Externals/Qt','Externals/FFmpeg-bin'}:
   result.setdefault('excludedOptionalGitlinks',[]).append({'path':path,'revision':revision,'reason':'LIBRETRO force-disables ENABLE_QT and ENCODE_FRAMEDUMPS in the exact root CMakeLists.txt lines 485-502; these prebuilt GUI/video-export dependencies are not part of the distributed libretro target'})
   continue
  if path not in modules:raise ValueError('Actual gitlink lacks source declaration: '+path)
  declared=modules[path];child=urllib.parse.urljoin(repo.rstrip('/')+'/',declared) if declared.startswith('.') else declared
  child=child.removesuffix('.git');key=component+'--'+re.sub('[^A-Za-z0-9_.-]','-',path)
  source=snapshot(key,child,revision,(*active,(repo,commit)));source.update({'installPath':path,'declaredRepository':declared});result['submodules'].append(source)
 return result
def azahar():
 root=ROOT.parent/'test-environment/cache/muxi-console/expansion/azahar';record=json.loads((root/'acquisition.json').read_text());archive=root/'official-source.tar.xz';expected=record['spec']['commit']
 if fetch.sha(archive)!=record['sourceArchive']['sha256']:raise ValueError('Official unified archive differs')
 tree=root/'source/azahar-unified-source-2126.2'
 if (tree/'GIT-COMMIT').read_text().strip()!=expected:raise ValueError('Unified source identifies another commit')
 manifests=[]
 for file in tree.rglob('.gitmodules'):
  cfg=configparser.ConfigParser();cfg.read(file,encoding='utf-8')
  for section in cfg.sections():
   child=file.parent/cfg[section]['path'];relative=child.relative_to(tree).as_posix();present=child.is_dir() and any(child.iterdir());manifests.append({'installPath':relative,'repository':cfg[section]['url'],'sourcePresentInOfficialUnifiedArchive':present})
 missing=[x['installPath'] for x in manifests if not x['sourcePresentInOfficialUnifiedArchive']]
 result={'repository':record['spec']['repository'],'revision':expected,'archive':'archives/azahar-unified-'+expected+'.tar.xz','cachePath':'../expansion/azahar/official-source.tar.xz','bytes':archive.stat().st_size,'sha256':fetch.sha(archive),'url':record['sourceArchive']['url'],'archiveLayout':'Official unified preferred-source release, including nested externals; GIT-COMMIT binds the release revision','licenseFiles':notices('azahar',archive),'submodules':[],'unifiedModuleDeclarations':manifests,'missingDeclaredModuleDirectories':missing}
 return result
if __name__=='__main__':
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--core',required=True,choices=['azahar','dolphin']);a=p.parse_args();CACHE.mkdir(parents=True,exist_ok=True)
 result=azahar() if a.core=='azahar' else snapshot('dolphin','https://github.com/libretro/dolphin','32c61f9d0e933331bb7087ba57e96883241b4960')
 output=CACHE/('expansion-'+a.core+'-source.json');output.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');print(json.dumps({'report':str(output),'licenseFiles':len(result['licenseFiles']),'submodules':len(result['submodules']),'missing':result.get('missingDeclaredModuleDirectories',[])},indent=2))
