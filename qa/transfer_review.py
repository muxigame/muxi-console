"""Actual delayed MC ROM delivery, item-switch resumption, author cache and duplicate burning."""
from __future__ import annotations
import argparse,json,time
from pathlib import Path
from drive import Driver,read_json,sha
from polish_review import record,check
from cartridge_review import at,items,raw,same_card,cleanup
from link_review import LinkFront
from media_review import received


class TransferFront(LinkFront):
    def wait(self,predicate,label,timeout=300):return self.d.wait_for(self.role,predicate,label,timeout)


def progress(view,card):
    p=view['cartridge']['progress']
    return p if p.get('card')==card['instance']else {}


def read_committed_prefix(d,card):
    root=d.root/'guest/muxi-console/roms';meta=root/(card['hash']+'.meta');partial=root/(card['hash']+'.part')
    fields=meta.read_text(encoding='ascii').splitlines()
    if len(fields)!=4 or fields[0]!='MXROM1'or fields[1]!=card['hash']or int(fields[2])!=card['bytes']:raise ValueError('Real paused download journal identity differs')
    offset=int(fields[3])
    if not 0<offset<=partial.stat().st_size<card['bytes']:raise ValueError('Expected a genuine incomplete committed prefix')
    return {'offset':offset,'partialBytes':partial.stat().st_size,'total':card['bytes']}


def run(d,continue_empty_fixture=False):
    path,out=record(d,'resumable-transfer-continuation'if continue_empty_fixture else'resumable-transfer');host=TransferFront(d,out,'host');guest=TransferFront(d,out,'guest')
    try:
        pin=d.build['largeGbaDiagnosticRom']
        if pin['bytes']!=32*1024*1024:raise ValueError('Use the verified original 32MiB FF-padded GBA fixture')
        if continue_empty_fixture:
            original_path=d.channel/'resumable-transfer-result.json';prior=read_json(original_path)
            if sha(original_path)!='7a17b0a431d8146f1c143eabbbbb5e9e2c1c99d1ea09a5f85526518dab314da0'or prior['runId']!=d.marker['runId']or prior['productSha256']!=d.build['product']['sha256']or prior.get('scenarioComplete')or prior.get('cleanupFailure')or prior.get('screenshots')or len(prior['checks'])!=1 or prior['checks'][0]['passed']or prior.get('failure')!='normal issued-card destination is empty':raise ValueError('Only the exact pre-burn empty-slot fixture failure may continue')
            server=d.command('server','observe')
            if server['rooms']or server['pendingUploads']or server['contentBytes']or any(v['room']or v['workers']or any(i.get('instance')for i in items(v))for v in(host.see(),guest.see())):raise ValueError('Continuation requires the original idle fixture with zero issued cards or content')
            out['fixture']=prior['fixture'];out['priorFailure']={'path':str(original_path),'sha256':sha(original_path),'scope':'Original empty fixture only; no cartridge, upload, gameplay or transfer was executed'}
        else:
            d.prepare();d.command('server','dock_prepare');out['fixture']=d.command('server','cartridge_prepare')
        host.do('review_view',{'held':False});guest.do('review_view',{'held':False})
        occupied={i['slot']for i in items(host.see())};free=[i for i in range(9,36)if i not in occupied]
        if len(free)<2:raise ValueError('Two observed empty main-inventory slots are required')
        original_slot,duplicate_slot=free[:2]
        original=host.bare_burn('large_gba',pin,original_slot);out['originalCard']=original
        before=host.see();host.inv();host.install(original,7);host.close();host.do('slot',{'slot':7})
        author=host.wait(lambda v:v['ready']and v['workerRunning']and v['frames']>=30,'author starts its own original file from the preseeded cache')
        check(out,received(author,'rom_chunk')==received(before,'rom_chunk')and author['downloadsGranted']==before['downloadsGranted'],'author starts a 32MiB normally burned cartridge with zero download body')
        author_done=host.leave('author original-file play');host.do('slot',{'slot':8});host.inv();host.eject(7,original,original_slot);host.close()
        before=host.see();disk_before=d.command('server','observe')['contentBytes']
        duplicate=host.bare_burn('large_gba',pin,duplicate_slot);after=host.see();disk_after=d.command('server','observe')['contentBytes'];out['duplicateCard']=duplicate
        acks=received(after,'upload_ack','packets')-received(before,'upload_ack','packets')
        check(out,duplicate['hash']==original['hash']and duplicate['instance']!=original['instance']and disk_before==disk_after==pin['bytes']and acks==1,'duplicate burn has one complete upload acknowledgement and one immutable world object, but two independent physical card IDs',{'uploadAcknowledgements':acks,'contentBytesBefore':disk_before,'contentBytesAfter':disk_after})
        # Move the complete original stack through the actual drop entity and normal player pickup.
        guest.camera(22.5,22.5,180,10)
        host.camera(5.5,12.5,0,0);host.do('select_card',{'instance':original['instance']});host.do('drop');host.camera(10.5,11.5);host.settle(45)
        drop=next(i for i in d.command('server','observe')['dock']['drops']if i.get('instance')==original['instance']);out['actualDrop']=drop
        guest.camera(drop['x'],drop['z'],180,10)
        got=guest.wait(lambda v:any(i.get('instance')==original['instance']for i in items(v)),'second real network player picks up the complete original cartridge')
        check(out,same_card(raw(got,original['instance']),original)and not any(i.get('instance')==original['instance']for i in items(host.see())),'normal physical transfer keeps the complete original card and removes it from author')
        guest.do('slot',{'slot':8});guest.inv();guest.install(original,7);guest.close();guest.camera()
        idle=guest.see();check(out,not idle['room']and not idle['workers']and received(idle,'rom_chunk')==0,'a stowed loaded machine does not preload the transferred ROM')
        guest.do('transfer_delay',{'hash':original['hash'],'millis':100});out['latencyFixture']={'millisecondsPerRealReply':100,'syntheticRomBytes':False,'syntheticProgress':False}
        began=time.monotonic();guest.do('slot',{'slot':7})
        loading=guest.wait(lambda v:progress(v,original).get('stage')=='downloading'and progress(v,original).get('bytes',0)>=2*1024*1024,'real partial download reaches a measurable prefix')
        old_epoch=loading['epoch'];out['firstLoading']={k:loading[k]for k in ('room','epoch','workers','downloadsGranted')};out['firstLoading']['progress']=progress(loading,original)
        guest.do('slot',{'slot':8});guest.wait(lambda v:not v['room']and not v['workers'],'switching to an empty item cancels only the loading session');guest.settle(8)
        prefix=read_committed_prefix(d,original);out['committedPrefix']=prefix
        check(out,prefix['offset']>=2*1024*1024,'item switch leaves a real committed resumable prefix instead of deleting it',prefix)
        before=guest.see();resume_at=time.monotonic();guest.do('slot',{'slot':7})
        resumed=guest.wait(lambda v:v['epoch']>old_epoch and progress(v,original).get('stage')=='downloading'and progress(v,original).get('bytes',0)>=prefix['offset'],'new room resumes the committed content prefix')
        check(out,progress(resumed,original)['bytes']>=prefix['offset']and not resumed['workers'],'resumption advances from preserved bytes before any emulator starts',{'oldEpoch':old_epoch,'newEpoch':resumed['epoch'],'offset':prefix['offset'],'progress':progress(resumed,original)})
        live=guest.wait(lambda v:v['ready']and v['workerRunning']and v['frames']>=30,'slow real GBA download survives the former sixty-second cutoff',600)
        elapsed=time.monotonic()-resume_at;body=received(live,'rom_chunk')-received(before,'rom_chunk');out['completedLoading']={'resumeSeconds':elapsed,'wholeTestSeconds':time.monotonic()-began,'receivedBodyBytesAfterResume':body,'expectedRemaining':pin['bytes']-prefix['offset'],'progress':progress(live,original)}
        check(out,elapsed>60 and 0<body<=pin['bytes']-prefix['offset']+2*24576,'real delayed transfer exceeds sixty seconds and downloads only the remaining prefix tail',out['completedLoading'])
        check(out,live['restoredStateHash']=='','borrowed card does not import the author machine progress')
        cache=guest.do('cartridge_cache',{'hash':original['hash']})['cacheCheck']['files'];valid=[i for i in cache if i['name'].endswith('.gba')]
        check(out,len(valid)==1 and valid[0]['bytes']==pin['bytes']and valid[0]['sha256']==pin['sha256']and not any(i['name'].endswith(('.part','.meta'))for i in cache),'actual complete guest cache matches the original SHA and retires its partial/journal; lock is retained',cache)
        guest.pic('transfer-game-after-resume',live);saved=guest.leave('guest slow transfer completed game');guest.do('slot',{'slot':8});guest.settle(3)
        before=guest.see();guest.do('slot',{'slot':7});warm=guest.wait(lambda v:v['ready']and v['workerRunning']and v['frames']>=30 and v['restoredStateHash']==saved['savedStateHash'],'same guest machine restores its own state from the verified cache')
        check(out,received(warm,'rom_chunk')==received(before,'rom_chunk')and warm['downloadsGranted']==before['downloadsGranted'],'verified restart uses zero extra ROM body')
        closed=guest.leave('guest cached restart');guest.do('slot',{'slot':8});out['finalSaveHash']=closed['savedStateHash'];out['authorSaveHash']=author_done['savedStateHash']
        out['scenarioComplete']=True
    except Exception as error:out['failure']=str(error);raise
    finally:cleanup(d,out);out['observationTimeouts']=d.observation_timeouts;path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
    return out


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--run',type=Path,required=True);parser.add_argument('--build',type=Path,required=True);parser.add_argument('--continue-empty-fixture',action='store_true');args=parser.parse_args()
    result=run(Driver(args.run,args.build),args.continue_empty_fixture);print(json.dumps({'complete':result['scenarioComplete'],'checks':len(result['checks']),'images':len(result['screenshots'])}))
