"""Strict same-run continuation after the preserved profile-observer schema error.

No prepare, burn, item replacement, profile modification or state injection.
"""
from __future__ import annotations
import argparse,json,zipfile
from pathlib import Path
from drive import Driver,read_json,sha
from polish_review import record,check
from cartridge_review import at,cursor,inserted,same_card
from link_review import link,idle,invite
from n3ds_link_review import ThreeDsFront,live,nonce_round,end_link,cleanup

SOURCE='e92f7f6d336607f5e361ea5c11072e359bb741753baa1bdabf54c42521bf0100'
RUN='890af90a2d5e4143bbeed2cfd43860f8'
PRODUCT='d20c68350690ce00842ef5e1f73d12a69718c88cabb6f413508e94326e16ffa5'
QA='4b05780f29500e27834fbde520e83cbb97e121cd82d15dcaff6037668ce07113'
HELPER='ed80f664d8354894ac9833d0823c00a6d391c4d1ce79ec5ded0d45e8378983f3'


def barrier(d,out,cards,machines,counts,label,retained=None):
    v=d.command('server','link3ds_observe');rows={}
    check(out,v['queueCheckpoint']is False and v['ndsGroupStateFiles']==0,'3DS keeps ordinary machine checkpoints without an NDS/ENet group artifact',v)
    if retained is not None:check(out,v==retained,'first committed state/profile records are unchanged from the exact preserved false observation',v)
    for role in cards:
        states=[r for r in v['saved']if r['machine']==machines[role]and r['content']==cards[role]['hash']]
        profiles=[p for p in v['profiles']if p['machine']==machines[role]]
        check(out,len(states)==len(profiles)==1,'one exact '+role+' CPU/profile generation at '+label)
        s,p=states[0],profiles[0];c=p['stored']['checkpoint']
        check(out,s['hash']==s['diskSha256']and s['size']==s['diskBytes']and s['fingerprint']==s['expectedFingerprint']and s['profileGeneration']==c['generationID']and c['hash']==p['readSha256']and c['size']==p['readBytes']and c['frame']==s['frame']and c['cardUUID']==machines[role]and p['key']['machine']==machines[role]and p['key']['platform']=='N3DS'and p['stored']['key']['machineUUID']==machines[role]and p['save']['magic']==0x52454c31 and p['save']['writes']==counts[role]and p['save']['role']==(0 if role=='host'else 1),'actual '+role+' CPU hash/generation binds the exact native SD profile at '+label,dict(state=s,profile=p));rows[role]=s
    out.setdefault('barriers',[]).append(dict(label=label,evidence=v));return rows


def run(d):
    source=d.channel/'n3ds-link-result.json';old=read_json(source)
    if sha(source)!=SOURCE or old['scenarioComplete']or len(old['checks'])!=38 or not all(c['passed']for c in old['checks'])or len(old['screenshots'])!=5 or old.get('cleanupFailure')or old.get('failure')!="'generation'"or old['runId']!=RUN or d.marker['runId']!=RUN or old['productSha256']!=PRODUCT or d.build['product']['sha256']!=PRODUCT or old['qaBuildSha256']!=QA or sha(d.build_path)!=QA or old['driverSha256']!=HELPER or sha(Path(__file__).with_name('n3ds_link_review.py'))!=HELPER:raise ValueError('Exact preserved profile-schema false and unchanged same run/product/QA/helper are required')
    retained=next(c['evidence']for c in reversed(old['checks'])if c['label']=='3DS creates ordinary machine checkpoints, never an NDS group artifact')
    ends=[old['finalClients'][r]['linkReview']['lastEnd']for r in('host','guest')]
    if len(old['workerClosures'])!=1 or not old['workerClosures'][0]['naturalExit']or len(old['saveDurations'])!=1 or any(old['finalClients'][r]['workers']or old['finalClients'][r]['room']for r in('host','guest'))or ends[0]['link']!=ends[1]['link']or ends[0]['epoch']!=ends[1]['epoch']or any(e['stage']!='CLOSING'for e in ends):raise ValueError('Preserved original first-final closure is required')
    path,out=record(d,'n3ds-link-continuation');out.update(source=dict(path=str(source),sha256=SOURCE,scenarioComplete=False,passed=38,images=5),driverSha256=sha(Path(__file__)),helperSha256=HELPER,qaBuildSha256=QA,cards=old['cards'],machines=old['machines'],scope='Same candidate 17, original two whole issued cartridges and machine identities after successful first final save. Only profile observer nesting corrected; no prepare, burn, native-file mutation or state injection. Cold fresh Room, ordinary export/import and normal prepare remain required. ENet queues are never checkpointed.')
    host=ThreeDsFront(d,out,'host');guest=ThreeDsFront(d,out,'guest');cards=out['cards'];machines=out['machines']
    try:
        with zipfile.ZipFile(d.build['product']['path'])as jar:pin=json.loads(jar.read('META-INF/muxi/azahar-link.json'))
        out['nativePin']=pin
        current={f.role:f.see()for f in(host,guest)}
        check(out,all(idle(v)and cursor(v)['empty']and same_card(inserted(v,2),cards[r])and at(v,2)['machine']==machines[r]and link(v)['lastEnd']==old['finalClients'][r]['linkReview']['lastEnd']for r,v in current.items()),'same original whole cards/machines and exact first final epoch remain idle')
        saved=barrier(d,out,cards,machines,dict(host=1,guest=1),'retained first final barrier',retained)
        original_workers={w['pid']for w in old['workerClosures'][0]['workers']}
        invite(host,guest);guest.button('接受连线');cold,cold_workers=live(host,guest,pin,'cold fresh Room',machines,dict(host=1,guest=1))
        start=link(cold['host'])['lastStart'];check(out,not original_workers&{w['pid']for w in cold_workers}and all(s.get('hash')for s in start['states']),'new pair receives the original committed CPU/native-file generations without an ENet queue payload',start)
        for r in cards:
            endpoint=next(i for i,e in enumerate(start['endpoints'])if e['machine']==machines[r]);state=start['states'][endpoint];profile=start['profiles'][endpoint];original=next(p for p in retained['profiles']if p['machine']==machines[r])['stored']['checkpoint']
            check(out,state['hash']==saved[r]['hash']and profile['hash']==original['hash'],'cold '+r+' actually reads the exact retained CPU and native profile hashes',dict(state=state,profile=profile))
        nonce_round(host,guest,machines,'cold fresh Room');host.press(74,256,host,machines['host'],2);guest.press(74,256,host,machines['guest'],2);host.picture('3ds-link-cold-room-host');end_link(host,guest,cold_workers,'cold fresh Room');saved=barrier(d,out,cards,machines,dict(host=2,guest=2),'cold final barrier')
        for f in(host,guest):
            f.do('cartridge_use',dict(hand='MAIN_HAND'));v=f.wait(lambda v:v['ready']and v['workerRunning']and v['room'].startswith('handheld/')and v['frames']>=40,'ordinary 3DS restores its exported CPU/native profile',240);check(out,v['restoredStateHash']==saved[f.role]['hash']and n3ds_writes(v,machines[f.role])==2,'ordinary '+f.role+' resumes its own actual state/profile generation',dict(restored=v['restoredStateHash'],state=saved[f.role],records=v['link3ds']['machines']));f.press(74,256,f,machines[f.role],3)
        host.picture('3ds-link-ordinary-machine-host');out['preparingFromSingles']=True;invite(host,guest);guest.button('接受连线');prepared,prepared_workers=live(host,guest,pin,'prepared ordinary Room',machines,dict(host=3,guest=3));nonce_round(host,guest,machines,'actual ordinary prepare seam');end_link(host,guest,prepared_workers,'prepared ordinary Room');barrier(d,out,cards,machines,dict(host=3,guest=3),'prepared ordinary final barrier')
        final={f.role:f.see()for f in(host,guest)};check(out,all(same_card(inserted(final[r],2),cards[r])and at(final[r],2)['machine']==machines[r]and cursor(final[r])['empty']for r in cards),'same issued whole cards and machines survive all strict continuation transitions');out['audioStimulationTested']=False;out['touchGameLogicTested']=False;out['scenarioComplete']=True
    except Exception as failure:out['failure']=str(failure);raise
    finally:cleanup(d,out);out['observationTimeouts']=d.observation_timeouts;path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
    return out


def n3ds_writes(view,identity):
    return next((r for r in view['link3ds']['machines']if r['machine']==identity),{}).get('save',{}).get('writes',0)


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--run',type=Path,required=True);p.add_argument('--build',type=Path,required=True);a=p.parse_args();v=run(Driver(a.run,a.build));print(json.dumps(dict(complete=v['scenarioComplete'],checks=len(v['checks']),images=len(v['screenshots']))))
