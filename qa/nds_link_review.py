"""Real two-author NDS wireless/group acceptance in one pre-existing hash-bound unified run. Never launches MC."""
from __future__ import annotations
import argparse,json,time,zipfile
from pathlib import Path
from drive import Driver,sha,environment
from polish_review import record,check
from cartridge_review import at,cursor,inserted,same_card
from link_review import LinkFront,link,idle,audit,invite,cache,metric_delta


class DsFront(LinkFront):
    def press(self,expected):
        before=self.see();began=time.monotonic();self.do('linkqa_source');self.do('linkqa_key',dict(key=74,pressed=True))
        try:
            down=self.wait(lambda v:v['linkDs']['input']['buttons']&(1<<8),'real mapped NDS A is captured')
            after=self.wait(lambda v:v['ready']and v['frames']>before['frames']and v['linkDs'].get('counter')==expected,'own actual ARM9 A-edge counter reaches '+str(expected),90)
            self.out.setdefault('inputEvidence',[]).append(dict(role=self.role,counter=expected,beforeFrame=before['frames'],afterFrame=after['frames'],seconds=time.monotonic()-began,input=down['linkDs']['input']))
            return after
        finally:self.do('linkqa_key',dict(key=74,pressed=False));self.settle(3)
    def picture(self,name):
        self.settle(3);before=self.see();row=self.d.capture(self.role,name);after=self.see();row.update(role=self.role,before=audit(before),after=audit(after),dsBefore=before['linkDs'],dsAfter=after['linkDs'],presentation=before['polish'].get('presentation'));self.out['screenshots'].append(row)
        check(self.out,all(v['ready']and v['frames']>0 and v['width']==256 and v['height']==384 for v in(before,after))and before['room']==after['room']and before['epoch']==after['epoch'],'same real DS picture and endpoint surrounds '+name)
        return row


def live(host,guest,pin,label,counters):
    def ready_view(v,role):
        current=link(v);start=current.get('lastStart',{});end=current.get('lastEnd',{})
        if not current['active']and not current['preparing']and not current['invitation']and start and end and start['link']==end['link']and end['epoch']>=start['epoch']:raise AssertionError('Actual '+label+' ended before ready: '+end.get('reason',''))
        return current['active']and v['ready']and current['hasPicture']and v['frames']>=40 and v['linkDs'].get('radioWitness')and v['linkDs'].get('counter')==counters[role]
    views={f.role:f.wait(lambda v:ready_view(v,f.role),'real '+label+' wireless CPU picture',180)for f in(host,guest)}
    h,g=views['host'],views['guest'];start=link(h)['lastStart'];other=link(g)['lastStart']
    check(host.out,h['room']==g['room']and h['epoch']==g['epoch']and len(h['workers'])==2 and h['workerRunning']and not g['workers']and not g['workerRunning'],'host owns two separate true DS cores; partner receives only its own endpoint',dict(clients={r:audit(v)for r,v in views.items()}))
    check(host.out,start['host']and not other['host']and start['bridge']==pin['platforms']['windows-x86_64']['librarySha256']and start['single']==pin['baseSingleCoreCompatibility']['librarySha256']and start['port']!=other['port'],'actual bridge and independently proved ordinary-state format have separate identities',dict(host=start,guest=other))
    expected=[dict(id='top',x=0,y=0,width=256,height=192,nativeWidth=256,nativeHeight=192,touchable=False),dict(id='bottom',x=0,y=192,width=256,height=192,nativeWidth=256,nativeHeight=192,touchable=True)]
    check(host.out,all(v['width']==256 and v['height']==384 and v['linkDs']['surfaces']==expected for v in views.values()),'both actual native and relayed atlases retain two screens and only lower-screen touch permission')
    workers=[dict(pid=pid,created=environment.process_created(pid))for pid in h['workers']];check(host.out,all(w['created']is not None for w in workers),'both owned DS PID generations exist',workers)
    before={r:v['linkDs']['peerSequence']for r,v in views.items()}
    later={f.role:f.wait(lambda v:v['linkDs'].get('peerSequence',0)>before[f.role],'actual radio peer sequence advances '+f.role,60)for f in(host,guest)}
    check(host.out,all(later[r]['linkDs']['radioWitness']and later[r]['linkDs']['peerSequence']>before[r]for r in before),'each real ARM7 receives newly transmitted peer WiFi payload, beyond a static ready image',dict(before=before,after={r:v['linkDs']['peerSequence']for r,v in later.items()}))
    return views,workers


def background_upload(host,guest):
    current=host.see();identity=current['room'][5:];base=link(current)['lastStart'].get('frameBase',0)
    def advancing(v):return any(row['link']==identity and not row['ending']and row.get('maxFrameDuringUpload',row['frame'])>row['frame']+12 and row.get('uploadTicks',0)>0 for row in v['linkDs']['checkpoints'])
    observed=host.wait(advancing,'actual native frames continue while a captured automatic group uploads in the background',180)
    row=next(row for row in observed['linkDs']['checkpoints']if row['link']==identity and not row['ending']and row.get('maxFrameDuringUpload',row['frame'])>row['frame']+12)
    check(host.out,3600<=row['frame']-base<=3603 and row['captureNanos']>0 and row['peakQueued']<=6,'automatic group uses at least60 simulated seconds and real six-batch bounded flow',row)
    host.press(2);check(host.out,guest.see()['linkDs']['counter']==1,'real automatic upload does not route host A into the peer machine')
    complete=host.wait(lambda v:any(r['link']==identity and r['frame']==row['frame']and r['stage']=='complete'and not r['inFlight']for r in v['linkDs']['checkpoints']),'actual automatic immutable upload commits while gameplay remains active',180)
    final=next(r for r in complete['linkDs']['checkpoints']if r['link']==identity and r['frame']==row['frame'])
    check(host.out,complete['ready']and complete['workerRunning']and final['uploadedBytes']==final['totalBytes']and final['totalBytes']>32*1024*1024 and final['maxFrameDuringUpload']>final['frame']+12 and final['peakQueued']<=6,'full raw group receives actual ACK bytes while its own CPU and presentation advance',final)
    host.out['backgroundUpload']=dict(before=observed['linkDs'],completed=complete['linkDs'],actualFrameAtObservation=observed['frames'],actualFrameAfterCompletion=complete['frames'])


def closed_workers(d,workers):
    log=d.root/'host/logs/latest.log';end=time.monotonic()+30
    while time.monotonic()<end:
        gone=all((now:=environment.process_created(w['pid']))is None or abs(now-w['created'])>.1 for w in workers)
        if gone and log.is_file():
            with log.open('rb')as source:source.seek(max(0,log.stat().st_size-2*1024*1024));lines=source.read().decode('utf-8','replace').splitlines()
            matched=[line for line in lines if 'DS link worker closure pids='in line and 'naturalExit=true'in line and all(str(w['pid'])in line for w in workers)]
            if matched:return dict(workers=workers,naturalExit=True,record=matched[-1],log=str(log))
        time.sleep(.1)
    raise AssertionError('actual two PID generations and kernel-signaled native cleanup did not close normally')


def end_link(host,guest,workers,label):
    for f in(host,guest):f.do('polish_release_keys')
    host.open_link();began=time.monotonic();host.button('保存并结束连线')
    ended={f.role:f.wait(idle,'normal '+label+' two-endpoint checkpoint and link lease release',300)for f in(host,guest)}
    duration=time.monotonic()-began;closure=closed_workers(host.d,workers);host.out.setdefault('workerClosures',[]).append(closure);host.out.setdefault('saveDurations',[]).append(dict(label=label,seconds=duration,configuredWindow=8,partBytes=24576,scope='observed normal final checkpoint and worker closure, no artificial bandwidth limit'))
    check(host.out,all(link(v)['lastEnd'].get('reason')and not link(v)['active']for v in ended.values()),'both actual clients finish '+label+' only after the normal final checkpoint',dict(seconds=duration,closure=closure))
    return ended


def barrier(d,out,cards,machines,label,pin):
    evidence=d.command('server','linkds_verify_group');group=evidence.get('group',{});rows={}
    check(out,group.get('magic')=='444e4c31'and group.get('readBytes')==group.get('size')and group.get('readSha256')==group.get('hash')and group['size']>32*1024*1024,'actual independently bounded DNL1 disk payload passes full streamed length/SHA at '+label,group)
    barriers=group['barriers'];check(out,len(barriers)==2 and len(evidence['profiles'])==2,'two ordinary CPU generations and two native profile generations accompany the real group')
    for role,card in cards.items():
        selected=[row for row in evidence['saved']if row['machine']==machines[role]and row['content']==card['hash']];check(out,len(selected)==1,'one actual '+role+' machine/content checkpoint at '+label,selected);row=selected[0];rows[role]=row
        b=next(v for v in barriers if v['key']['machineUUID']==machines[role]and v['key']['contentSHA']==card['hash']);p=next(v for v in evidence['profiles']if v['machine']==machines[role])
        check(out,row['diskSha256']==row['hash']==b['singleSHA']and row['size']==row['diskBytes']and row['generation']==b['singleGeneration']and row['frame']==b['singleFrame']==group['frame']and row['fingerprint']==row['expectedFingerprint']and row['fingerprint']!=pin['baseSingleCoreCompatibility']['librarySha256']and p['generation']==b['profileGeneration']and p['hash']==b['profileSHA'],'actual '+role+' CPU and native-file barriers match exact committed generations at '+label,dict(state=row,barrier=b,profile=p))
    out.setdefault('barriers',[]).append(dict(label=label,evidence=evidence));return rows,group


def cleanup(d,out):
    try:
        for role in('host','guest'):
            f=DsFront(d,out,role);f.do('polish_release_keys');v=f.see()
            if link(v)['active']or link(v)['preparing']:f.open_link();f.button('保存并结束连线')
            elif link(v)['invitation']:f.open_link();f.button('取消邀请'if link(v)['invitation']['host']else'拒绝')
        for role in('host','guest'):
            f=DsFront(d,out,role);v=f.see()
            if v['room']and not v['room'].startswith('link/'):f.do('leave')
            f.wait(idle,'owned DS normal cleanup with all workers and leases released',300);f.close()
        out['finalClients']={r:audit(d.command(r,'observe'))for r in('host','guest')}
    except Exception as failure:out['cleanupFailure']=str(failure);out['scenarioComplete']=False


def run(d):
    path,out=record(d,'nds-link');out.update(driverSha256=sha(Path(__file__)),qaBuildSha256=sha(d.build_path));host=DsFront(d,out,'host');guest=DsFront(d,out,'guest')
    try:
        out['scope']='Two original independently issued NDS cards, real host-local wireless cores and peer-only AV/input, full queued DNL1 group cold restore, per-machine ordinary MXS1/profile restore. No commercial ROM/BIOS; silent diagnostic does not claim audio or physical OS input.'
        d.prepare();out['fixture']=d.command('server','linkds_fixture');product=Path(d.build['product']['path']);check(out,sha(product)==d.build['product']['sha256'],'exact current candidate product remains pinned')
        with zipfile.ZipFile(product)as jar:pin=json.loads(jar.read('META-INF/muxi/melondsds-link.json'))
        out['nativePin']=pin;diagnostics=d.build['ndsLinkDiagnostics'];out['diagnostics']=diagnostics
        for name in('left','right'):check(out,sha(Path(diagnostics[name]['path']))==diagnostics[name]['sha256'],'original no-vendor-BIOS '+name+' DS ROM matches its native baseline',diagnostics[name])
        for f in(host,guest):f.do('dock_view',dict(hideGui=False));f.settle(10);f.craft(2,9,23)
        left=host.bare_burn('link_nds_left',diagnostics['left'],4);right=guest.bare_burn('link_nds_right',diagnostics['right'],4);cards={'host':left,'guest':right};out['cards']=cards
        host.inv();host.install(left,2);guest.inv();guest.install(right,2)
        for f in(guest,host):f.do('slot',dict(slot=2));f.settle(3)
        check(out,not cache(host,right)and not cache(guest,left),'authors hold their own real card/cache only before explicit wireless consent')
        first=invite(host,guest);out['cancelledInvitation']=first;guest.native_picture('nds-link-invitation-consent')
        host.do('probe',dict(hash=right['hash']));denied=host.wait(lambda v:v['probe']!='pending','real pre-consent partner download decision');check(out,denied['probe']=='denied'and not cache(host,right),'pending invitation grants no partner ROM')
        guest.button('拒绝');cancelled={f.role:f.wait(idle,'declined pair leaves both unexecuted')for f in(host,guest)};check(out,all(not link(v)['lastStart']for v in cancelled.values()),'decline starts neither native core')
        invite(host,guest);transfer_before={f.role:f.see()for f in(host,guest)};guest.button('接受连线');running,workers=live(host,guest,pin,'fresh wireless',dict(host=0,guest=0));machines={f.role:at(f.see(),2)['machine']for f in(host,guest)};out['machines']=machines
        transfer_after={f.role:f.see()for f in(host,guest)};out['transferMetrics']={r:metric_delta(transfer_before[r],transfer_after[r])for r in transfer_before}
        check(out,out['transferMetrics']['host']['dataBytes']==diagnostics['right']['bytes']and out['transferMetrics']['guest']['dataBytes']==0 and not cache(guest,left),'accepted host really downloads only the missing right ROM; peer receives no host ROM body',out['transferMetrics'])
        host.press(1);check(out,guest.see()['linkDs']['counter']==0,'host A modifies only its own actual ARM9 input counter');guest.press(1);check(out,host.see()['linkDs']['counter']==1,'guest A returns through wire input to only its own actual CPU')
        for f in(host,guest):f.picture('nds-link-'+f.role+'-model')
        guest.do('dock_right_click');guest.wait(lambda v:v['polish']['presentation'].get('mode')=='screen','normal right click switches to pure dual-screen presentation');guest.picture('nds-link-guest-dual-screen')
        guest.do('linkqa_source');guest.do('linkds_pointer',dict(surface='bottom',x=.65,y=.4,pressed=True));pressed=guest.wait(lambda v:v['linkDs']['input']['pointer']['valid']and v['linkDs']['input']['pointer']['pressed'],'real lower-screen mouse callback is captured by Link input')
        port=link(pressed)['port'];relayed=host.wait(lambda v:len(v['linkDs']['stepInputs'])==2 and v['linkDs']['stepInputs'][port]['pointer']['valid']and v['linkDs']['stepInputs'][port]['pointer']['pressed'],'actual partner pointer travels through server Link step InputCodec');guest.do('linkds_pointer',dict(surface='bottom',x=.65,y=.4,pressed=False));host.wait(lambda v:not v['linkDs']['stepInputs'][port]['pointer']['pressed'],'actual pointer release follows the same route')
        check(out,relayed['linkDs']['stepInputs'][port]['pointer']['surface']=='bottom','touch metadata retains the actual lower surface ID; diagnostic itself has no touch game logic',dict(frontend=pressed['linkDs']['input'],actualStep=relayed['linkDs']['stepInputs']))
        host.do('dock_esc');host.do('review_view',dict(held=True));host.settle(5);host.picture('nds-link-world-dual-lcd');host.open_link();host.button('返回自己的游戏机')
        background_upload(host,guest)
        end_link(host,guest,workers,'first wireless');saved,first_group=barrier(d,out,cards,machines,'first final barrier',pin)
        before={f.role:f.see()for f in(host,guest)};invite(host,guest);guest.button('接受连线');cold,cold_workers=live(host,guest,pin,'cold group',dict(host=2,guest=1))
        check(out,link(cold['host'])['lastStart'].get('groupState',{}).get('hash')==first_group['hash']and link(cold['host'])['lastStart'].get('frameBase')==first_group['frame']and not {w['pid']for w in workers}&{w['pid']for w in cold_workers},'fresh two-worker pair cold-restores the exact DNL1 and its logical frame barrier',link(cold['host'])['lastStart'])
        check(out,all(metric_delta(before[f.role],f.see())['dataBytes']==0 for f in(host,guest)),'cold group recovery reuses verified ROM caches with zero new ROM body')
        host.press(3);host.picture('nds-link-cold-group-host');end_link(host,guest,cold_workers,'cold restored wireless');saved,last_group=barrier(d,out,cards,machines,'cold final barrier',pin)
        singles={}
        for f in(host,guest):
            f.do('cartridge_use',dict(hand='MAIN_HAND'));v=f.wait(lambda v:v['ready']and v['workerRunning']and v['room'].startswith('handheld/')and v['frames']>=30 and v['linkDs'].get('counter')==(3 if f.role=='host'else 1),'ordinary original single core restores '+f.role+' exact CPU input count',120);check(out,v['restoredStateHash']==saved[f.role]['hash'],'ordinary '+f.role+' loads its own exported MXS1 generation',dict(restored=v['restoredStateHash'],saved=saved[f.role]));singles[f.role]=v
        host.picture('nds-link-ordinary-machine-host');out['preparingFromSingles']=True;invite(host,guest);guest.button('接受连线');prepared,prepared_workers=live(host,guest,pin,'prepared ordinary pair',dict(host=3,guest=1))
        check(out,not link(prepared['host'])['lastStart'].get('groupState')and all(row.get('hash')for row in link(prepared['host'])['lastStart']['states']),'newer ordinary CPU generations invalidate the older wireless group and prepare both real machines independently',link(prepared['host'])['lastStart'])
        end_link(host,guest,prepared_workers,'prepared ordinary wireless');barrier(d,out,cards,machines,'prepared ordinary final barrier',pin)
        final={f.role:f.see()for f in(host,guest)};check(out,all(same_card(inserted(final[r],2),cards[r])and at(final[r],2)['machine']==machines[r]and cursor(final[r])['empty']for r in cards),'same issued whole cards and physical machines survive all wireless/ordinary transitions')
        out['scenarioComplete']=True
    except Exception as failure:out['failure']=str(failure);raise
    finally:cleanup(d,out);out['observationTimeouts']=d.observation_timeouts;path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
    return out


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--run',type=Path,required=True);p.add_argument('--build',type=Path,required=True);a=p.parse_args();v=run(Driver(a.run,a.build));print(json.dumps(dict(complete=v['scenarioComplete'],checks=len(v['checks']),images=len(v['screenshots']))))
