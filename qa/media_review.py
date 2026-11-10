"""Real ordinary NES shared seats and passive physical-screen audio/video, no parallel guest core."""
from __future__ import annotations
import argparse,json
from pathlib import Path
from drive import Driver
from polish_review import record,check
from cartridge_review import cleanup
from link_review import LinkFront
from shared_drive import matched_batches,seat


def received(view,operation,field='dataBytes'):
    return view['mediaMetrics']['operations'].get(operation,{}).get(field,0)


def delta(before,after,operations):
    a,b=before['mediaMetrics'],after['mediaMetrics']
    if a['startedAtNanos']!=b['startedAtNanos']:raise ValueError('Receive counter belongs to another client generation')
    seconds=(b['measuredAtNanos']-a['measuredAtNanos'])/1e9
    if not 1<=seconds<=120:raise ValueError('Expected a bounded real media observation window')
    rows={}
    for op in operations:
        fields={k:received(after,op,k)-received(before,op,k)for k in ('packets','dataBytes','metadataBytes','wireBytes','nonzeroPcmSamples')}
        if any(v<0 for v in fields.values()):raise ValueError('Receive counters moved backwards')
        rows[op]={**fields,'wireMegabitsPerSecond':fields['wireBytes']*8/seconds/1_000_000}
    return {'seconds':seconds,'scope':b['scope'],'operations':rows,'totalWireMegabitsPerSecond':sum(row['wireBytes']for row in rows.values())*8/seconds/1_000_000}


def observe_window(front,operations):
    before=front.see();front.settle(220);after=front.see()
    return before,after,delta(before,after,operations)


def same_picture(left,right):
    return all(left.get('picture',{}).get(k)==right.get('picture',{}).get(k)for k in ('left','right'))


def run(d):
    path,out=record(d,'ordinary-media');host=LinkFront(d,out,'host');guest=LinkFront(d,out,'guest')
    block={'x':8,'z':20};position=lambda role:d.command('server','shared_position',{'role':role,'platform':'nes'})
    try:
        if 'mediaDiagnosticRom'not in d.build:raise ValueError('Build QA with the exact candidate audible-media probe')
        d.prepare();out['fixture']=d.command('server','media_prepare')
        before_burn=host.see();card=host.bare_burn('media_nes',d.build['mediaDiagnosticRom'],7)
        after_burn=host.see();out['card']=card
        # The real writer seeds the author's verified cache; starting must not send it back.
        host_rom_before=received(after_burn,'rom_chunk')
        position('host');position('guest')
        host.do('slot',{'slot':7});host.do('use_block',block)
        host.do('slot',{'slot':1});host.do('use_block',block);host.do('use_block',block)
        host.do('slot',{'slot':8});host.do('use_block',block)
        host.wait(lambda v:v['screen'].endswith('DeviceScreen'),'physical NES device controls')
        host.do('button',{'text':'开机'});host.do('button',{'text':'连接屏幕'});host.do('button',{'text':'P1'})
        active=host.wait(lambda v:seat(v,True)and v['workerRunning']and v['frames']>=90,'audible original NES runs in one real host worker',120)
        check(out,received(active,'rom_chunk')==host_rom_before,'author starts its normally burned card with zero ROM download body')
        observer=guest.see();guest.do('slot',{'slot':8});guest.do('use_block',block)
        guest.wait(lambda v:v['screen'].endswith('DeviceScreen'),'guest opens actual physical NES console')
        guest.do('button',{'text':'P2'})
        remote=guest.wait(lambda v:seat(v,False)and v['shared'].get('receivedVideo',0)>=4 and v['shared'].get('receivedAudio',0)>=4,'P2 receives real NES pixels and PCM',120)
        source=host.wait(lambda v:seat(v,True)and v['workerRunning']and v['room']==remote['room']and v['epoch']==remote['epoch']and v['frames']>=30,'host resumes the exact shared epoch',120)
        check(out,not remote['workers']and not remote['workerRunning']and received(remote,'rom_chunk')==received(observer,'rom_chunk')and remote['downloadsGranted']==observer['downloadsGranted'],'ordinary NES P2 receives no ROM and creates no emulator process')
        out['matchedSharedBatches']=matched_batches(d,'host','guest',True)
        baseline=guest.see()['picture'];host.do('input_source',{'buttons':256})
        p1=guest.wait(lambda v:v['picture'].get('left')!=baseline.get('left')and v['picture'].get('right')==baseline.get('right'),'P1 changes only the real left visual region')
        host.do('input_source',{'buttons':0});guest.do('input_source',{'buttons':256})
        p2=guest.wait(lambda v:v['picture'].get('left')==p1['picture'].get('left')and v['picture'].get('right')!=p1['picture'].get('right'),'P2 changes only the real right visual region')
        guest.do('input_source',{'buttons':0});out['independentControllerPixels']={'before':baseline,'p1':p1['picture'],'p2':p2['picture']}
        start,end,rate=observe_window(guest,('shared_frame','shared_audio','rom_chunk'))
        check(out,rate['operations']['shared_frame']['packets']>=10 and rate['operations']['shared_audio']['nonzeroPcmSamples']>1000 and rate['operations']['rom_chunk']['dataBytes']==0,'stable shared NES transports actual nonzero original audio and video without ROM bytes',rate)
        out['sharedRate']=rate;guest.native_picture('media-nes-p2-shared')
        old_epoch=end['epoch'];guest.do('leave');guest.wait(lambda v:not v['room']and not v['workers'],'P2 normally leaves without stopping a guest worker')
        source=host.wait(lambda v:seat(v,True)and v['epoch']>old_epoch and v['workerRunning']and v['frames']>=60,'host normally checkpoints and resumes after P2 leaves',120)
        passive=guest.wait(lambda v:not v['room']and not v['workers']and v['browser']['receivedFrames']>=4 and received(v,'browser_audio','nonzeroPcmSamples')>1000,'now-passive player receives the actual linked physical screen and sound',120)
        start,end,rate=observe_window(guest,('browser_frame','browser_audio','rom_chunk'))
        check(out,not end['room']and not end['workers']and rate['operations']['browser_frame']['packets']>=10 and rate['operations']['browser_audio']['nonzeroPcmSamples']>1000 and rate['operations']['rom_chunk']['dataBytes']==0,'physical screen observer receives only original live video/audio, no ROM or parallel core',rate)
        out['physicalScreenRate']=rate;d.command('server','shared_camera',{'role':'guest','platform':'nes'});guest.do('review_view',{'held':False});guest.settle(10);guest.native_picture('media-nes-passive-physical-screen')
        closed=host.leave('ordinary NES host final checkpoint');out['finalSaveHash']=closed['savedStateHash']
        check(out,guest.see()['normalWorkerExits']==observer['normalWorkerExits'] and guest.see()['forcedWorkerExits']==guest.see()['failedWorkerExits']==0,'guest remains core-free throughout shared play and passive viewing')
        out['scenarioComplete']=True
    except Exception as error:out['failure']=str(error);raise
    finally:cleanup(d,out);out['observationTimeouts']=d.observation_timeouts;path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
    return out


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--run',type=Path,required=True);parser.add_argument('--build',type=Path,required=True);args=parser.parse_args()
    result=run(Driver(args.run,args.build));print(json.dumps({'complete':result['scenarioComplete'],'checks':len(result['checks']),'images':len(result['screenshots'])}))
