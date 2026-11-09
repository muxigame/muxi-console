"""Actual ordinary host-clock, callback-to-pixel latency and native-profile save seams. Never launches Minecraft."""
from __future__ import annotations
import argparse,json,time
from pathlib import Path
from drive import Driver,read_json,sha
from polish_review import record,check,reference
from cartridge_review import at,raw,cleanup
from upgrade_review import Upgrade,inventory_slot
from link_review import LinkFront
from shared_drive import seat,matched_batches


def metrics(v):return v['latency']['metrics']


def quiet_wait(d,seconds):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        if (d.root/'stop-request.json').exists():raise RuntimeError('Unified runner requested normal stop')
        time.sleep(min(.5,max(0,end-time.monotonic())))


def clock(v):
    m=v.get('latency',{}).get('metrics',{})
    return v['ready']and v['workerRunning']and m.get('hostClock')and m.get('clockStarted')and m.get('leaseValid')and not m.get('hold')


def event_rows(v,operation):return [r for r in v['latency']['events']if r['operation']==operation]


def saved(d,key):
    world=d.command('server','machine_observe')
    return next(s for s in world['saved']if s['machine']==key['machine']and s['content']==key['content'])


def evidence(v):
    return {k:v[k]for k in ('room','epoch','ready','workerRunning','workers','frames','savedStateHash','savedStates','restoredStateHash','machine','picture','latency')}


def capture(f,name):
    before=f.see();row=f.d.capture(f.role,name);after=f.see()
    check(f.out,before['room']==after['room']and before['epoch']==after['epoch']and before['ready']and after['ready'],'same real session surrounds '+name)
    row.update(role=f.role,presentation=before['polish'].get('presentation'),before={k:before[k]for k in ('room','epoch','workers','frames')},after={k:after[k]for k in ('room','epoch','workers','frames')})
    f.out['screenshots'].append(row)


def stop(d,out):
    try:
        for role in ('host','guest'):d.command(role,'latency_stop')
    except Exception as error:out['latencyCleanupFailure']=str(error);out['scenarioComplete']=False
    cleanup(d,out)


def finish_result(d,path,out,inputs):
    stop(d,out);out['observationTimeouts']=d.observation_timeouts
    out['driverDrift']=[str(p)for p,h in inputs.items()if sha(p)!=h]
    if out['driverDrift']or out.get('latencyCleanupFailure'):out['scenarioComplete']=False
    path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')


def source_inputs():
    return {Path(__file__).resolve().with_name(name):sha(Path(__file__).resolve().with_name(name))for name in ('latency_review.py','drive.py','polish_review.py','cartridge_review.py','upgrade_review.py','link_review.py','shared_drive.py')}


def check_ack_continuation(out,v,label,min_frames=15):
    committed={r['body'].get('saveTransaction')for r in event_rows(v,'state_committed')if r['body'].get('room')==v['room']and r['body'].get('epoch')==v['epoch']}
    rows=[r for r in event_rows(v,'save_paused')if r['body'].get('room')==v['room']and r['body'].get('epoch')==v['epoch']and r['body'].get('saveTransaction')in committed and r.get('deliveredAtNanos',0)-r['receivedAtNanos']>=500_000_000 and not r['body'].get('holdToken')]
    check(out,bool(rows),label+' observes an actual delayed background save authorization',rows)
    check(out,any(r.get('coreFrameAtDelivery',0)-r.get('coreFrameAtArrival',0)>=min_frames and not r.get('holdAtDelivery',True)for r in rows),label+' resumes real native frames before the withheld background save ACK is delivered',rows)


def final_hold(out,v,state,label):
    matches=[r for r in event_rows(v,'state_committed')if r['body'].get('hash')==state['hash']]
    check(out,len(matches)==1,label+' final committed frame and disk SHA match the real current state',state)
    tx=matches[0]['body']['saveTransaction']
    pauses=[r for r in event_rows(v,'save_paused')if r['body'].get('saveTransaction')==tx]
    holds=[r for r in event_rows(v,'checkpoint_request')if r['body'].get('hold')and r['body'].get('holdToken')]
    check(out,len(pauses)==1 and pauses[0]['body']['frame']==state['frame']==pauses[0].get('coreFrameAtArrival')and pauses[0].get('holdAtArrival')is True and any(r['body']['holdToken']==pauses[0]['body'].get('holdToken')for r in holds),label+' final save binds the held actual native frame to the server token',dict(pauses=pauses,holds=holds))


class LatencyFront(LinkFront):
    def burn_realtime(self,destination):
        pin=self.d.build['latency'];self.writer();self.click_menu(self.player_menu_slot(0),0,'QUICK_MOVE')
        check(self.out,self.view()['slots'][0].get('item')=='muxi_console:blank_cartridge'and self.view()['slots'][0]['count']==1,'one physical blank enters the actual realtime writer')
        self.do('latency_choose');self.do('writer_confirm');self.button('上传并刻录')
        card=self.wait(lambda v:v['upgrade']['slots'][0].get('instance')and v['upgrade']['slots'][0].get('hash')==pin['sha256'],'actual rights-confirmed realtime cartridge')['upgrade']['slots'][0]
        self.click_menu(0);self.click_menu(self.player_menu_slot(destination));self.close();self.settle(3)
        actual=raw(self.see(),card['instance']);check(self.out,actual['hash']==pin['sha256']and actual['count']==1,'normal writer issues exactly the original realtime GBA content',actual);return actual
    def focus(self):
        v=self.see()
        if not v['screen'].endswith('GameScreen'):self.do('cartridge_use',dict(hand='MAIN_HAND'))
        return self.wait(lambda v:v['screen'].endswith('GameScreen'),'normal full-screen input focus')
    def wait_measurement(self,seconds):
        # QaOwner deliberately hashes the actual loaded JAR on every command. Keep
        # those main-thread integrity checks outside the locally timed window.
        quiet_wait(self.d,seconds+.35)
        deadline=time.monotonic()+10
        while time.monotonic()<deadline:
            v=self.see()
            if v['latency'].get('complete'):return v
            time.sleep(.65)
        raise TimeoutError('Bounded actual callback measurement did not finish')


def measure(f,label,seconds,pulses):
    initial=f.see();f.do('latency_begin',dict(seconds=seconds,pulses=pulses));v=f.wait_measurement(seconds);r=v['latency'];before=r['before'];after=metrics(v)
    check(f.out,r['complete']and not r['failure']and not r['timerFailure'],label+' completes actual GUI callbacks and guest pixel counts',r)
    check(f.out,len(r['taps'])==pulses and all(t['upNanos']>t['downNanos']>0 and t['appliedNanos']>=t['downNanos']and t['upAppliedNanos']>=t['upNanos']and t['firstObservedNanos']>=t['downNanos']for t in r['taps']),label+' every actual down/up edge reaches a native frame and its visible guest counter')
    seconds_actual=(after['runningNanos']-before['runningNanos'])/1e9;fps=(after['coreFrame']-before['coreFrame'])/seconds_actual
    check(f.out,abs(fps-after['coreFps'])/after['coreFps']<.025,label+' native frames track real elapsed time instead of twenty server ticks',dict(seconds=seconds_actual,fps=fps,expected=after['coreFps']))
    check(f.out,v['mediaMetrics']['operations'].get('frames',{}).get('packets',0)==initial['mediaMetrics']['operations'].get('frames',{}).get('packets',0),label+' uses no legacy server frame-token packets')
    check(f.out,after['localInput']['droppedTransitions']==before['localInput']['droppedTransitions']and after['localInput']['queued']==0 and after['peakPendingCoreTasks']<=1 and after['peakPendingPictures']<=1,label+' input, native-frame and presentation queues remain bounded without dropped transitions')
    check(f.out,r['callbackToApplied']['p95Ms']<80 and r['callbackToApplied']['maxMs']<150 and r['callbackToPresentedGuestPixels']['p95Ms']<120,label+' measured callback-to-core and callback-to-visible-pixel latency stays bounded',dict(applied=r['callbackToApplied'],presented=r['callbackToPresentedGuestPixels']))
    check(f.out,r['presentationCadence']['p95Ms']<40 and r['presentationCadence']['p99Ms']<67 and r['presentationCadence']['maxMs']<150,label+' actual distinct guest pictures avoid the old twenty-Hz burst cadence',r['presentationCadence'])
    audio_before,audio=before['audio'],after['audio'];audio_scope=dict(before=audio_before,after=audio,physicalListeningTested=False)
    if audio['outputAvailable']:
        check(f.out,audio['playedFrames']>audio_before['playedFrames']and audio['writtenFrames']>audio_before['writtenFrames'],label+' actual audio device line positions advance',audio_scope)
        check(f.out,audio['droppedFrames']==audio_before['droppedFrames']and audio['underruns']==audio_before['underruns'],label+' no actual audio queue drop or device underrun during the measurement',audio_scope)
    else:f.out.setdefault('unverified',[]).append(label+': audio output unavailable; source PCM/device playback are distinct scopes')
    return evidence(v)


def gba_loaded(d,out,f,card):
    started=f.wait(clock,'actual ordinary GBA clock_start and valid authorization lease');f.focus()
    check(out,any(e['body'].get('room')==started['room']and e['body'].get('epoch')==started['epoch']for e in event_rows(started,'clock_start')),'actual server clock_start belongs to the occupied machine epoch')
    f.do('latency_delay_saves',dict(milliseconds=600));out['normal']=measure(f,'normal realtime',20,60);capture(f,'latency-gba-short-taps')
    f.do('latency_delay_leases',dict(milliseconds=250));out['delayedLease']=measure(f,'delayed lease delivery',12,30)
    check(out,out['delayedLease']['latency']['delayedLeases']>out['normal']['latency']['delayedLeases'],'actual lease packets were delayed while local frame rate remained native')
    check_ack_continuation(out,f.see(),'GBA');f.do('latency_stop');before=f.see();owner=before['machine'];core=at(before,2)
    f.close();f.do('slot',dict(slot=8));ended=f.wait(lambda v:not v['room']and not v['workers'],'normal held-item selection change saves and closes',60)
    check(out,ended['normalWorkerExits']==before['normalWorkerExits']+1 and ended['forcedWorkerExits']==ended['failedWorkerExits']==0,'normal item stow closes the exact worker without forced or failed exit')
    state=saved(d,owner);check(out,state['hash']==state['diskSha256']==ended['savedStateHash']and state['frame']>=metrics(before)['coreFrame'],'actual held-machine final state is retained under its world path',state);final_hold(out,ended,state,'GBA stow')
    f.do('slot',dict(slot=2));restored=f.wait(lambda v:clock(v)and v['restoredStateHash']==state['hash'],'same machine cold worker restores the final exact state')
    f.focus();check(out,at(restored,2)['machine']==core['machine']and at(restored,2)['inserted']['nbt']==card['nbt']and restored['latency']['guest'][1]>=90,'cold same-machine restore retains the real guest short-tap counter and complete cartridge')
    capture(f,'latency-gba-cold');f.do('latency_stop');closed=f.leave('GBA cold final cleanup');f.do('slot',dict(slot=8));f.close();out['saved']=saved(d,owner);out['scenarioComplete']=True


def gba(d):
    path,out=record(d,'latency-gba');f=LatencyFront(d,out);inputs=source_inputs();out['driverInputs']={str(p):h for p,h in inputs.items()}
    out['measurementScope']='Normal GUI callbacks to native-frame input trace and RenderFrameEvent.Post observation of the actual uploaded guest texture; no physical keyboard, monitor scanout or subjective listening claim.'
    try:
        if 'latency'not in d.build:raise ValueError('Exact opt-in latency QA required')
        d.prepare();out['fixture']=d.command('server','hardware_prepare');d.command('server','hardware_mode',dict(mode='survival'));f.wait(lambda v:v['gameMode']=='survival','actual survival inventory mode');f.do('dock_view',dict(hideGui=False));f.settle(10)
        card=f.burn_realtime(3);out['card']=card;f.inv();f.install(card,2);f.close();f.do('slot',dict(slot=2))
        gba_loaded(d,out,f,card)
    except Exception as error:out['failure']=str(error);raise
    finally:finish_result(d,path,out,inputs)
    return out


def remote(d):
    path,out=record(d,'latency-remote');h=LatencyFront(d,out);g=LatencyFront(d,out,'guest');inputs=source_inputs()
    try:
        reference(d,out,'latency-gba');out['fixture']=d.command('server','media_prepare');card=h.bare_burn('media_nes',d.build['latency']['nes'],7);out['card']=card
        for role in ('host','guest'):d.command('server','shared_position',dict(role=role,platform='nes'))
        block=dict(x=8,z=20);h.do('slot',dict(slot=7));h.do('use_block',block);h.do('slot',dict(slot=1));h.do('use_block',block);h.do('use_block',block)
        h.do('slot',dict(slot=8));h.do('use_block',block);h.wait(lambda v:v['screen'].endswith('DeviceScreen'),'actual NES device menu');h.button('启动 / 停止');h.button('连接附近屏幕');h.button('P1 加入')
        first=h.wait(lambda v:clock(v)and seat(v,True),'first real NES host clock');guest_before=g.see();g.do('slot',dict(slot=8));g.do('use_block',block);g.wait(lambda v:v['screen'].endswith('DeviceScreen'),'actual peer NES device menu');g.button('P2 加入')
        peer=g.wait(lambda v:seat(v,False)and v['shared'].get('receivedVideo',0)>3,'P2 receives actual shared NES pixels')
        host=h.wait(lambda v:clock(v)and seat(v,True)and v['epoch']==peer['epoch']and v['epoch']>first['epoch'],'join resets authorization and dispatches a new epoch clock_start')
        check(out,not peer['workers']and not peer['workerRunning']and peer['downloadsGranted']==guest_before['downloadsGranted'],'real remote control seat uses neither ROM nor parallel core')
        check(out,any(e['body'].get('epoch')==host['epoch']for e in event_rows(host,'clock_start')),'join epoch has a real newly received clock_start')
        out['matchedBatches']=matched_batches(d,'host','guest');initial_right=peer['picture']['right'];g.focus();baseline=metrics(h.see())['remoteInputs'][1]
        g.do('latency_remote_taps',dict(seconds=10,pulses=18));peer=g.wait_measurement(10);host=h.see();trace=[a for a in metrics(host)['remoteInputs'][1]['applied']if a['sequence']>baseline['sequence']]
        check(out,peer['latency']['complete']and not peer['latency']['failure']and len(peer['latency']['taps'])==18,'remote user normal 10/20/30ms callback schedule completes',peer['latency'])
        check(out,sum(bool(a['buttons']&256)for a in trace)==18 and sum(a['buttons']==0 for a in trace)==18 and all(a['sequence']<b['sequence']and a['frame']<b['frame']for a,b in zip(trace,trace[1:])),'each distinct remote short press and release is consumed once in actual host native-frame order',trace)
        check(out,metrics(host)['remoteInputs'][1]['droppedTransitions']==baseline['droppedTransitions']and metrics(host)['remoteInputs'][1]['queued']==0 and peer['picture']['right']!=initial_right,'remote edges do not accumulate or drop and change the actual second-port game pixels')
        out['remoteActual']=dict(guest=evidence(peer),host=evidence(host));capture(g,'latency-nes-remote-short-taps');g.do('latency_stop');old=host['epoch'];g.do('leave');g.wait(lambda v:not v['room']and not v['workers'],'peer normally releases its seat');g.close()
        resumed=h.wait(lambda v:clock(v)and v['epoch']>old,'remote departure creates a fresh normally resumed host clock')
        check(out,any(e['body'].get('epoch')==resumed['epoch']for e in event_rows(resumed,'clock_start'))and metrics(resumed)['remoteInputs'][1]['queued']==0 and not any(a['buttons']for a in metrics(resumed)['remoteInputs'][1]['applied']),'departure reauthorizes the new epoch and retires all old peer presses')
        h.do('latency_stop');h.leave('NES final normal save');h.close();out['scenarioComplete']=True
    except Exception as error:out['failure']=str(error);raise
    finally:finish_result(d,path,out,inputs)
    return out


def nds(d):
    path,out=record(d,'latency-nds');f=LatencyFront(d,out);inputs=source_inputs()
    try:
        reference(d,out,'latency-remote');out['fixture']=d.command('server','expansion_prepare');pin=d.build['latency']['nds']
        f.writer();f.click_menu(f.player_menu_slot(0),0,'QUICK_MOVE');f.do('expansion_drop',dict(platform='nds'));f.do('writer_confirm');f.button('上传并刻录')
        card=f.wait(lambda v:v['upgrade']['slots'][0].get('instance')and v['upgrade']['slots'][0].get('hash')==pin['sha256'],'normal original NDS burn')['upgrade']['slots'][0];f.click_menu(0);f.click_menu(f.player_menu_slot(5));f.close();out['card']=raw(f.see(),card['instance'])
        f.inv();f.do('upgrade_click',dict(slot=inventory_slot(9),button=2,type='SWAP'));f.wait(lambda v:at(v,2).get('model')==23,'normal NDS shell swap preserves the earlier GBA in inventory');f.install(out['card'],2);f.close();f.do('slot',dict(slot=2));started=f.wait(clock,'actual NDS host-clock worker');owner=started['machine'];f.focus();f.do('expansion_source');f.do('latency_delay_saves',dict(milliseconds=600))
        f.do('expansion_key',dict(bit=8,pressed=True));f.settle(5);f.do('expansion_key',dict(bit=8,pressed=False))
        # Native-files periodic capture is sixty emulated seconds. Wait for its actual commit, not an invented timer.
        quiet_wait(d,66)
        deadline=time.monotonic()+20;committed=None
        while time.monotonic()<deadline:
            v=f.see()
            if v['savedStates']>started['savedStates']and event_rows(v,'profile_committed'):committed=v;break
            time.sleep(.8)
        check(out,committed is not None,'ordinary NDS background capture commits actual native-profile and CPU state')
        # Native capture/packing is allowed to pause. Any real frame before ACK delivery
        # proves it did not wait for the network round trip; capture cost is not throughput.
        check_ack_continuation(out,committed,'NDS',min_frames=1);world=d.command('server','expansion_observe');state=next(s for s in world['saved']if s['machine']==owner['machine']and s['content']==owner['content']);profile=world['machines']['nds']['profile']
        check(out,state.get('profileGeneration')==profile['generation']and state['hash']==state['diskSha256']and state['frame']>=3000,'NDS background state is bound to its real committed native-file generation',dict(state=state,profile=profile));out['background']=dict(state=state,profile=profile,client=evidence(committed));capture(f,'latency-nds-background-save')
        f.do('latency_stop');closed=f.leave('NDS final native-profile save');f.do('slot',dict(slot=8));f.close();world=d.command('server','expansion_observe');state=next(s for s in world['saved']if s['machine']==owner['machine']and s['content']==owner['content']);profile=world['machines']['nds']['profile'];final_hold(out,closed,state,'NDS leave')
        check(out,state['hash']==closed['savedStateHash']and state['profileGeneration']==profile['generation'],'NDS final held capture commits both profile and CPU state before close')
        read_before=closed['expansion']['profileReadBytes'];f.do('slot',dict(slot=2));cold=f.wait(lambda v:clock(v)and v['restoredStateHash']==state['hash'],'same NDS machine restores the exact final CPU checkpoint');f.focus()
        check(out,cold['machine']==owner and cold['expansion']['lastSession']['profile']['generation']==profile['generation']and cold['expansion']['profileReadBytes']-read_before>=profile['size'],'NDS cold worker reads the correct native profile before the bound CPU state',dict(state=state,profile=profile,client=evidence(cold)));capture(f,'latency-nds-cold')
        f.do('latency_stop');f.leave('NDS cold cleanup');f.do('slot',dict(slot=8));f.close();out['scenarioComplete']=True
    except Exception as error:out['failure']=str(error);raise
    finally:finish_result(d,path,out,inputs)
    return out


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--run',type=Path,required=True);p.add_argument('--build',type=Path,required=True);p.add_argument('--phase',choices=('all','gba','remote','nds'),default='all');a=p.parse_args();d=Driver(a.run,a.build)
    for name,action in (('gba',gba),('remote',remote),('nds',nds)):
        if a.phase in ('all',name):
            result=action(d);print(json.dumps(dict(phase=name,complete=result['scenarioComplete'],checks=len(result['checks']),images=len(result['screenshots']))),flush=True)
