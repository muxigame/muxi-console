"""Strict Wii continuation after the original driver omitted its physical controller connection."""
from __future__ import annotations
import argparse,json
from pathlib import Path
from drive import Driver,read_json,sha
from polish_review import record,check
from cartridge_review import at,cleanup
from upgrade_review import Upgrade

SOURCE_SHA='7804b1bb63264124fa753b9de6cf7a388d0c35eaf1d6813c08fa9c267fd0bca2'
PRODUCT_SHA='1427f0c09fddc56df306ca02ead52e5904537d6c0c240efab5583fc5940a58a8'

def run(d):
    source=d.channel/'expansion-result.json'
    if sha(source)!=SOURCE_SHA:raise ValueError('The immutable original failure is not this continuation source')
    original=read_json(source)
    if original['runId']!=d.marker['runId']or original['productSha256']!=PRODUCT_SHA or d.build['product']['sha256']!=PRODUCT_SHA or original['scenarioComplete']is not False or len(original['checks'])!=41 or not all(row['passed']for row in original['checks'])or len(original['screenshots'])!=6 or 'disabled: P1'not in original.get('failure',''):raise ValueError('Original failure scope differs')
    if set(original['platforms'])!={'nds','n3ds'}or set(original['cards'])!={'nds','n3ds','wii'}:raise ValueError('Continuation must retain the two completed platforms and all three issued cards')
    prior_reply=d.channel/'reply-server.json';prior_bytes=prior_reply.read_bytes();prior=read_json(prior_reply)
    if prior['runId']!=d.marker['runId']or prior.get('ok')is not True:raise ValueError('Original driver world observation differs')
    prior_device=prior['result']['wiiDevice'];card=original['cards']['wii']
    if prior_device['ports']!=0 or prior_device['model']!=25 or prior_device['card']['instance']!=card['instance']or prior_device['card']['hash']!=card['hash']:raise ValueError('Original physical Wii machine/card precondition differs')
    frozen=d.channel/'expansion-wii-source-world.json'
    if frozen.exists():raise ValueError('Continuation source already frozen')
    frozen.write_bytes(prior_bytes)
    path,out=record(d,'expansion-wii-continuation');f=Upgrade(d,out)
    out.update(sourceFailure={'file':source.name,'sha256':SOURCE_SHA,'checks':41,'screenshots':6},sourceWorld={'file':frozen.name,'sha256':sha(frozen)},card=card,scope='Same run, exact candidate12, original issued Wii card and placed machine; normal physical controller connection, actual input, profile/state barrier and cold restore. Original false remains false. Physical OS input not tested.')
    inputs={p:sha(p)for p in [Path(__file__).resolve(),*[Path(__file__).resolve().with_name(name)for name in('drive.py','polish_review.py','cartridge_review.py','upgrade_review.py')]]};out['driverInputs']={str(p):v for p,v in inputs.items()}
    def world():return d.command('server','expansion_observe')
    def same(w):
        device=w['wiiDevice'];return device['identity']==prior_device['identity']and device['model']==25 and device['card']['instance']==card['instance']and device['card']['hash']==card['hash']and w.get('wiiMachine')==prior['result'].get('wiiMachine')
    def panel():
        f.close();f.do('slot',dict(slot=8));f.do('use_block',dict(x=5,y=90,z=6));f.wait(lambda v:v['screen'].endswith('DeviceScreen'),'ordinary Wii management menu')
        w=world()
        if not same(w):raise ValueError('Wii hardware/card identity changed')
        if not w['wiiDevice']['powered']:f.do('button',dict(text='启动 / 停止'));f.settle(4)
        f.do('button',dict(text='P1 加入'))
    def ready(state=None):return f.wait(lambda v:v['ready']and v['workerRunning']and v['frames']>=30 and v['expansion']['lastSession'].get('platform')=='wii'and(state is None or v['restoredStateHash']==state),'actual Wii runtime and expected restore',60)
    def focus():
        f.wait(lambda v:v['screen'].endswith('GameScreen'),'normal fullscreen opened for the controller seat');f.do('expansion_source');return f.wait(lambda v:v['polish'].get('presentation',{}).get('screens'),'actual Wii primary surface')
    def key(pressed=True,extra=None):f.do('expansion_key',dict(pressed=pressed,**({'bit':8}if extra is None else{'extra':extra})))
    def pointer(pressed=True):f.do('expansion_pointer',dict(surface='primary',x=.35,y=.6,pressed=pressed))
    def capture(label):
        f.settle(4);before=f.see();row=d.capture('host','expansion-wii-'+label);after=f.see();fields=('room','epoch','workers','ready','workerRunning','frames','crc');row.update(role='host',before={k:before[k]for k in fields},after={k:after[k]for k in fields},expansionBefore=before['expansion'],expansionAfter=after['expansion'],presentation=before['polish'].get('presentation'));out['screenshots'].append(row)
        check(out,all(v['ready']and v['workerRunning']and v['room']==before['room']and v['epoch']==before['epoch']and v['workers']==before['workers']for v in(before,after)),'same actual Wii worker surrounds '+label)
        flow=after['expansion']['frameFlow'];check(out,flow['enabled']and flow['acknowledgedBatches']>0 and 0<flow['peakQueued']<=8,'real Wii frame completion ACKs retain the bounded window',flow)
    try:
        initial=f.see();guest=d.command('guest','observe');w=world();check(out,same(w)and w['wiiDevice']['ports']==0 and 'wii'not in w['machines']and not initial['room']and not initial['workers']and not guest['room']and not guest['workers']and initial['upgrade']['cursor']['empty'],'idle original physical Wii/card and empty cursor survive the failure')
        check(out,not any(row['content']==card['hash']for row in w['saved']),'no Wii progress was fabricated or previously run')
        controller=at(initial,1);check(out,controller['item']=='muxi_console:controller'and controller['count']>=1,'existing physical controller is available for normal connection')
        f.close();f.do('slot',dict(slot=1));f.do('use_block',dict(x=5,y=90,z=6));f.settle(4);w=world();check(out,same(w)and w['wiiDevice']['ports']==1 and at(f.see(),1)['count']==controller['count']-1,'normal controller use consumes one original controller and opens one physical port',w['wiiDevice'])
        panel();ready();v=focus();check(out,{p['id']for p in v['expansion']['surfaces']}=={'primary'},'actual Wii AV exposes its real primary surface')
        pointer();f.settle(14);key();v=f.wait(lambda v:len(v['expansion'].get('nativeDiagnosticFile',{}).get('words',[]))==16 and v['expansion']['nativeDiagnosticFile']['words'][3]&8,'Wii application consumes actual A')
        ir=v['expansion']['nativeDiagnosticFile'];check(out,ir['words'][0]==0x4d585749 and ir['words'][6]==1,'actual Wii native application file records pointer IR',ir);out['wiiIRSample']=ir;capture('screen')
        key(False);pointer(False);f.settle(4);key(extra='TILT_RIGHT');f.settle(14);key();v=f.wait(lambda v:len(v['expansion'].get('nativeDiagnosticFile',{}).get('words',[]))==16 and v['expansion']['nativeDiagnosticFile']['words'][2]>ir['words'][2]and any(v['expansion']['nativeDiagnosticFile']['words'][i]for i in(9,10)),'separate actual tilt and second A press reach the Wii application');check(out,v['expansion']['nativeDiagnosticFile']['words'][3]&8,'real second A press saves the virtual accelerometer sample',v['expansion']['nativeDiagnosticFile'])
        key(False);key(False,extra='TILT_RIGHT');f.settle(4);owner=f.see()['machine'];world();f.do('polish_release_keys');ended=f.leave('Wii checkpoint');f.do('slot',dict(slot=8));f.close();w=world();state=next(row for row in w['saved']if row['machine']==owner['machine']and row['content']==owner['content']);profile=w['machines']['wii']['profile'];native_file=ended['expansion']['nativeDiagnosticFile']
        check(out,state['hash']==ended['savedStateHash']==state['diskSha256']and state['size']==state['diskBytes']>0,'world disk stores the real Wii transport checksum',state);check(out,state['profileGeneration']==profile['generation']and profile['size']>0,'Wii core checkpoint binds the committed machine profile',profile);out.update(owner=owner,state=state,profile=profile,nativeFile=native_file)
        downloads=f.see()['expansion']['profileReadBytes'];panel();restored=ready(state['hash']);check(out,restored['machine']==owner and restored['expansion']['lastSession']['profile']['generation']==profile['generation']and restored['expansion']['profileReadBytes']-downloads>=profile['size'],'new Wii worker downloads the original machine profile and restores its exact bound state');check(out,restored['expansion']['nativeDiagnosticFile']['sha256']==native_file['sha256'],'cold Wii boot retains the actual native application file');focus();capture('cold');f.leave('Wii cold cleanup');f.do('slot',dict(slot=8));f.close();check(out,same(world()),'original issued Wii card and placed machine remain intact after both normal saves');out['scenarioComplete']=True
    except Exception as error:out['failure']=str(error);raise
    finally:
        cleanup(d,out);out['observationTimeouts']=d.observation_timeouts;out['driverDrift']=[str(p)for p,v in inputs.items()if sha(p)!=v]
        if sha(source)!=SOURCE_SHA or out['driverDrift']:out['scenarioComplete']=False
        path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
    return out

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--run',type=Path,required=True);p.add_argument('--build',type=Path,required=True);a=p.parse_args();result=run(Driver(a.run,a.build));print(json.dumps({'complete':result['scenarioComplete'],'checks':len(result['checks']),'images':len(result['screenshots'])}))
