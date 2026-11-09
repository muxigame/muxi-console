"""Two original 3DS cards through normal MC consent, real UDS, SD saves and fresh-Room CPU restoration. Never launches MC."""
from __future__ import annotations
import argparse,json,time,zipfile
from pathlib import Path
from drive import Driver,sha,environment
from polish_review import record,check
from cartridge_review import at,cursor,inserted,same_card
from link_review import LinkFront,link,idle,audit,invite,cache,metric_delta

NONCE=0x6d39a7c2
SEEDS=(0x319ac76d,0xb6502fe4)

def machine(view,identity):return next((row for row in view['link3ds']['machines']if row['machine']==identity),{})
def writes(view,identity):return machine(view,identity).get('save',{}).get('writes',0)

class ThreeDsFront(LinkFront):
    strict=True
    def wait(self,predicate,label,timeout=60):
        def guarded(v):
            if self.strict:
                current=link(v);checkpoint=v.get('link3ds',{}).get('checkpoint',{})
                if current['active']and checkpoint.get('link')==v['room'][5:]and checkpoint.get('epoch')==v['epoch']and checkpoint.get('stage')=='failed':raise AssertionError('Actual product checkpoint failed before '+label+': '+json.dumps(checkpoint,ensure_ascii=False))
                errors=[e for e in current['events']if e.get('operation')=='link_error'and e.get('link')==v.get('room','')[5:]and e.get('epoch')==v.get('epoch')]
                if current['active']and errors:raise AssertionError('Actual product request failed before '+label+': '+json.dumps(errors[-1],ensure_ascii=False))
                start,end=current.get('lastStart',{}),current.get('lastEnd',{})
                if not current['active']and not current['preparing']and start and end and start['link']==end['link']and end['epoch']>=start['epoch']and 'ready'in label:raise AssertionError('Actual Room ended before '+label+': '+end.get('reason',''))
            return predicate(v)
        return super().wait(guarded,label,timeout)
    def picture(self,name):
        self.settle(3);before=self.see();row=self.d.capture(self.role,name);after=self.see();row.update(role=self.role,before=audit(before),after=audit(after),threeDsBefore=before['link3ds'],threeDsAfter=after['link3ds'],presentation=before['polish'].get('presentation'));self.out['screenshots'].append(row)
        check(self.out,all(v['ready']and v['frames']>0 and v['width']==400 and v['height']==480 for v in(before,after))and before['room']==after['room']and before['epoch']==after['epoch'],'same actual 3DS picture and endpoint surrounds '+name);return row
    def press(self,key,mask,observer,identity,expected=None):
        before=self.see();self.do('link3ds_source');self.do('link3ds_key',dict(key=key,pressed=True))
        try:
            down=self.wait(lambda v:v['link3ds']['input']['buttons']&mask,'real mapped 3DS button is captured');self.wait(lambda v:v['frames']>=before['frames']+6,'actual 3DS CPU consumes held button',90)
            if expected is not None:after=observer.wait(lambda v:writes(v,identity)==expected,'actual guest A writes original SD record '+str(expected),90)
            else:after=observer.see()
            self.out.setdefault('inputEvidence',[]).append(dict(role=self.role,key=key,mask=mask,input=down['link3ds']['input'],observed=machine(after,identity),beforeFrame=before['frames']))
        finally:self.do('link3ds_key',dict(key=key,pressed=False));self.settle(3)

def live(host,guest,pin,label,machines,counts):
    views={f.role:f.wait(lambda v:link(v)['active']and v['ready']and link(v)['hasPicture']and v['frames']>=40,'actual '+label+' 3DS picture',240)for f in(host,guest)};h,g=views['host'],views['guest'];start=link(h)['lastStart'];other=link(g)['lastStart']
    check(host.out,h['room']==g['room']and h['epoch']==g['epoch']and len(h['workers'])==2 and h['workerRunning']and not g['workers']and not g['workerRunning'],'host runs two true 3DS processes; peer owns only its endpoint AV/input',dict(host=audit(h),guest=audit(g)))
    check(host.out,start['bridge']==pin['platforms']['windows-x86_64']['librarySha256']and start['single']==pin['baseSingleCoreCompatibility']['librarySha256']and start.get('queueCheckpoint')is False and not start.get('groupState')and start['port']!=other['port'],'3DS uses proved ordinary states in a fresh private Room, without DNL1 or ENet queue claims',start)
    expected=[dict(id='top',x=0,y=0,width=400,height=240,nativeWidth=400,nativeHeight=240,touchable=False),dict(id='bottom',x=40,y=240,width=320,height=240,nativeWidth=320,nativeHeight=240,touchable=True)]
    check(host.out,all(v['link3ds']['surfaces']==expected for v in views.values()),'native and relayed 3DS surfaces preserve the centered lower touch panel')
    host.wait(lambda v:all(machine(v,machines[r]).get('radio',{}).get('stage')==3 and machine(v,machines[r]).get('radio',{}).get('received',0)>0 and writes(v,machines[r])==counts[r]for r in machines),'both actual guest CPUs recognize peer UDS challenges and retain their own SD writes',180)
    workers=[dict(pid=pid,created=environment.process_created(pid))for pid in h['workers']];check(host.out,all(w['created']is not None for w in workers),'two actual owned 3DS PID generations exist',workers);return views,workers

def nonce_round(host,guest,machines,label):
    before=host.see();old={r:machine(before,machines[r])['radio']for r in machines}
    for f in(host,guest):f.press(75,1,host,machines[f.role])
    after=host.wait(lambda v:all(machine(v,machines[r]).get('radio',{}).get('peerChallenge')==(old[r]['peerChallenge']^NONCE)and machine(v,machines[r]).get('radio',{}).get('peerSequence',0)>old[r]['peerSequence']for r in machines),'fresh actual guest B challenges cross both UDS endpoints after '+label,180)
    evidence=dict(label=label,before=old,after={r:machine(after,machines[r])['radio']for r in machines});check(host.out,all(evidence['after'][r]['peerRole']==1-evidence['after'][r]['role']and evidence['after'][r]['totalNodes']==2 for r in machines),'both guest PullPacket APIs receive newly changed opposite challenges, beyond stale queued data',evidence);host.out.setdefault('nonceRounds',[]).append(evidence)

def closed(d,workers):
    log=d.root/'host/logs/latest.log';end=time.monotonic()+30
    while time.monotonic()<end:
        gone=all((now:=environment.process_created(w['pid']))is None or abs(now-w['created'])>.1 for w in workers)
        if gone and log.is_file():
            with log.open('rb')as source:source.seek(max(0,log.stat().st_size-2*1024*1024));lines=source.read().decode('utf-8','replace').splitlines()
            matches=[v for v in lines if '3DS link worker closure pids='in v and'naturalExit=true'in v and all(str(w['pid'])in v for w in workers)]
            if matches:return dict(workers=workers,record=matches[-1],naturalExit=True)
        time.sleep(.1)
    raise AssertionError('Actual 3DS worker handles/native files failed to close naturally')

def end_link(host,guest,workers,label):
    for f in(host,guest):f.do('polish_release_keys')
    host.open_link();began=time.monotonic();host.button('保存并结束连线')
    for f in(host,guest):f.wait(idle,'normal '+label+' profiles/state finish and native closure',300)
    evidence=closed(host.d,workers);host.out.setdefault('workerClosures',[]).append(evidence);host.out.setdefault('saveDurations',[]).append(dict(label=label,seconds=time.monotonic()-began,window=8));check(host.out,True,'normal '+label+' closes both true workers after committed machine checkpoints',evidence)

def automatic(host):
    identity=host.see()['room'][5:]
    v=host.wait(lambda v:any(r['link']==identity and not r['ending']and r['stage']=='complete'for r in v['linkDs']['checkpoints']),'actual 60-second 3DS automatic CPU/profile upload completes',240)
    row=next(r for r in v['linkDs']['checkpoints']if r['link']==identity and not r['ending']and r['stage']=='complete')
    check(host.out,3600<=row['frame']<=3603 and row['uploadedBytes']==row['totalBytes']and row['totalBytes']>0 and row['captureNanos']>0 and row['peakQueued']<=6 and row['maxFrameDuringUpload']>row['frame'],'immutable 3DS automatic states/files upload while actual CPU frames continue within six pending batches',row);host.out['automaticUpload']=row

def barrier(d,out,cards,machines,counts,label):
    v=d.command('server','link3ds_observe');rows={};check(out,v['queueCheckpoint']is False and v['ndsGroupStateFiles']==0,'3DS creates ordinary machine checkpoints, never an NDS group artifact',v)
    for role in cards:
        states=[r for r in v['saved']if r['machine']==machines[role]and r['content']==cards[role]['hash']];profiles=[p for p in v['profiles']if p['machine']==machines[role]];check(out,len(states)==len(profiles)==1,'one exact '+role+' CPU/profile generation at '+label)
        s,p=states[0],profiles[0];c=p['stored']['checkpoint'];check(out,s['hash']==s['diskSha256']and s['size']==s['diskBytes']and s['fingerprint']==s['expectedFingerprint']and s['profileGeneration']==c['generationID']and c['hash']==p['readSha256']and c['size']==p['readBytes']and c['frame']==s['frame']and c['cardUUID']==machines[role]and p['key']['machine']==machines[role]and p['key']['platform']=='N3DS'and p['stored']['key']['machineUUID']==machines[role]and p['save']['magic']==0x52454c31 and p['save']['writes']==counts[role]and p['save']['role']==(0 if role=='host'else 1),'actual '+role+' serialized CPU binds its matching native SD generation at '+label,dict(state=s,profile=p));rows[role]=s
    out.setdefault('barriers',[]).append(dict(label=label,evidence=v));return rows

def cleanup(d,out):
    try:
        for role in('host','guest'):
            f=ThreeDsFront(d,out,role);f.strict=False;f.do('polish_release_keys');v=f.see()
            if link(v)['active']or link(v)['preparing']:f.open_link();f.button('保存并结束连线')
            elif link(v)['invitation']:f.open_link();f.button('取消邀请'if link(v)['invitation']['host']else'拒绝')
        for role in('host','guest'):
            f=ThreeDsFront(d,out,role);f.strict=False;v=f.see()
            if v['room']and not v['room'].startswith('link/'):f.do('leave')
            f.wait(idle,'owned 3DS normal cleanup with all workers/leases released',300);f.close()
        out['finalClients']={r:audit(d.command(r,'observe'))for r in('host','guest')}
    except Exception as failure:out['cleanupFailure']=str(failure);out['scenarioComplete']=False

def run(d):
    path,out=record(d,'n3ds-link');out.update(driverSha256=sha(Path(__file__)),qaBuildSha256=sha(d.build_path));host=ThreeDsFront(d,out,'host');guest=ThreeDsFront(d,out,'guest')
    try:
        out['scope']='Two original authored UDS 3DSX cartridges; private host-local Room with actual guest UDS challenge changes, ordinary CPU+native-file checkpoints, fresh Room restore and ordinary prepare. ENet queues are not saved. Silent diagnostic has no touch game logic; touch UI/wire and PCM stimulation are reported separately.'
        d.prepare();out['fixture']=d.command('server','link3ds_fixture');product=Path(d.build['product']['path']);check(out,sha(product)==d.build['product']['sha256'],'exact current product remains pinned')
        with zipfile.ZipFile(product)as jar:pin=json.loads(jar.read('META-INF/muxi/azahar-link.json'))
        out['nativePin']=pin;diagnostics=d.build['n3dsLinkDiagnostics'];out['diagnostics']=diagnostics
        for label in('left','right'):check(out,sha(Path(diagnostics[label]['path']))==diagnostics[label]['sha256'],'original '+label+' 3DSX matches native nonce baseline',diagnostics[label])
        for f in(host,guest):f.do('dock_view',dict(hideGui=False));f.settle(10);f.craft(2,9,24)
        cards={'host':host.bare_burn('link_3ds_left',diagnostics['left'],4),'guest':guest.bare_burn('link_3ds_right',diagnostics['right'],4)};out['cards']=cards
        for f in(host,guest):f.inv();f.install(cards[f.role],2)
        for f in(guest,host):f.do('slot',dict(slot=2));f.settle(3)
        check(out,not cache(host,cards['guest'])and not cache(guest,cards['host']),'each author has only its own real ROM/cache before consent')
        invite(host,guest);guest.native_picture('3ds-link-invitation-consent');host.do('probe',dict(hash=cards['guest']['hash']));v=host.wait(lambda v:v['probe']!='pending','pre-consent 3DS partner download decision');check(out,v['probe']=='denied'and not cache(host,cards['guest']),'unaccepted invitation grants no partner ROM');guest.button('拒绝')
        for f in(host,guest):f.wait(idle,'declined 3DS pair remains unexecuted')
        invite(host,guest);before={f.role:f.see()for f in(host,guest)};guest.button('接受连线');machines={f.role:at(f.see(),2)['machine']for f in(host,guest)};out['machines']=machines;views,workers=live(host,guest,pin,'fresh Room',machines,dict(host=0,guest=0))
        out['transferMetrics']={r:metric_delta(before[r],(host if r=='host'else guest).see())for r in before};check(out,out['transferMetrics']['host']['dataBytes']==diagnostics['right']['bytes']and out['transferMetrics']['guest']['dataBytes']==0 and not cache(guest,cards['host']),'accepted host downloads only missing partner 3DSX; guest receives no host ROM',out['transferMetrics'])
        host.press(74,256,host,machines['host'],1);check(out,writes(host.see(),machines['guest'])==0,'host A modifies only its own actual SD record');guest.press(74,256,host,machines['guest'],1);check(out,writes(host.see(),machines['host'])==1,'peer A routes to its own actual guest CPU and SD')
        nonce_round(host,guest,machines,'fresh Room')
        host.picture('3ds-link-host-model');guest.picture('3ds-link-guest-model');guest.do('dock_right_click');guest.wait(lambda v:v['polish']['presentation'].get('mode')=='screen','normal right click enters pure dual-screen mode');guest.picture('3ds-link-guest-dual-screen')
        guest.do('link3ds_source');guest.do('link3ds_pointer',dict(x=.6,y=.4,pressed=True));p=guest.wait(lambda v:v['link3ds']['input']['pointer']['valid']and v['link3ds']['input']['pointer']['pressed'],'real lower 3DS surface pointer is captured');port=link(p)['port'];wire=host.wait(lambda v:len(v['link3ds']['stepInputs'])==2 and v['link3ds']['stepInputs'][port]['pointer']['pressed'],'real peer lower touch crosses Link InputCodec');guest.do('link3ds_pointer',dict(x=.6,y=.4,pressed=False));host.wait(lambda v:not v['link3ds']['stepInputs'][port]['pointer']['pressed'],'real touch release crosses same route');check(out,wire['link3ds']['stepInputs'][port]['pointer']['surface']=='bottom','touch retains actual centered lower surface metadata; original ROM has no touch paint logic',wire['link3ds']['stepInputs'])
        host.do('dock_esc');host.do('review_view',dict(held=True));host.settle(5);host.picture('3ds-link-world-dual-lcd');host.open_link();host.button('返回自己的游戏机')
        automatic(host);end_link(host,guest,workers,'first Room');saved=barrier(d,out,cards,machines,dict(host=1,guest=1),'first final barrier')
        invite(host,guest);guest.button('接受连线');cold,cold_workers=live(host,guest,pin,'cold fresh Room',machines,dict(host=1,guest=1));check(out,not set(w['pid']for w in workers)&set(w['pid']for w in cold_workers)and all(row.get('hash')for row in link(cold['host'])['lastStart']['states']),'new pair receives exact ordinary CPU/native-file generations without an ENet queue payload',link(cold['host'])['lastStart']);nonce_round(host,guest,machines,'cold fresh Room');host.press(74,256,host,machines['host'],2);guest.press(74,256,host,machines['guest'],2);host.picture('3ds-link-cold-room-host');end_link(host,guest,cold_workers,'cold fresh Room');saved=barrier(d,out,cards,machines,dict(host=2,guest=2),'cold final barrier')
        for f in(host,guest):
            f.do('cartridge_use',dict(hand='MAIN_HAND'));v=f.wait(lambda v:v['ready']and v['workerRunning']and v['room'].startswith('handheld/')and v['frames']>=40,'ordinary 3DS restores its exported CPU/native profile',240);check(out,v['restoredStateHash']==saved[f.role]['hash']and writes(v,machines[f.role])==2,'ordinary '+f.role+' resumes its own actual state/profile generation',dict(restored=v['restoredStateHash'],state=saved[f.role],records=v['link3ds']['machines']));f.press(74,256,f,machines[f.role],3)
        host.picture('3ds-link-ordinary-machine-host');out['preparingFromSingles']=True;invite(host,guest);guest.button('接受连线');prepared,prepared_workers=live(host,guest,pin,'prepared ordinary Room',machines,dict(host=3,guest=3));nonce_round(host,guest,machines,'actual ordinary prepare seam');end_link(host,guest,prepared_workers,'prepared ordinary Room');barrier(d,out,cards,machines,dict(host=3,guest=3),'prepared ordinary final barrier')
        final={f.role:f.see()for f in(host,guest)};check(out,all(same_card(inserted(final[r],2),cards[r])and at(final[r],2)['machine']==machines[r]and cursor(final[r])['empty']for r in cards),'same issued whole cards and machines survive all 3DS/ordinary transitions');out['audioStimulationTested']=False;out['touchGameLogicTested']=False;out['scenarioComplete']=True
    except Exception as failure:out['failure']=str(failure);raise
    finally:cleanup(d,out);out['observationTimeouts']=d.observation_timeouts;path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
    return out

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--run',type=Path,required=True);p.add_argument('--build',type=Path,required=True);a=p.parse_args();v=run(Driver(a.run,a.build));print(json.dumps(dict(complete=v['scenarioComplete'],checks=len(v['checks']),images=len(v['screenshots']))))
