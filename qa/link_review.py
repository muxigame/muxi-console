"""Hash-bound real GB cable invitations, per-machine picture/input/audio and save recovery. Never launches MC."""
from __future__ import annotations
import argparse
import json
import time
import zipfile
from pathlib import Path
from drive import Driver,read_json,sha,environment
from polish_review import record,check,reference
from cartridge_review import items,at,cursor,raw,inserted,same_card
from upgrade_review import Upgrade,upgrade_sources


def link(view):return view['linkReview']
def idle(view):return not view['room']and not view['workers']and not link(view)['active']and not link(view)['preparing']and not link(view)['invitation']
def audit(view):
    result={k:view[k]for k in('room','epoch','ready','frames','crc','workers','workerRunning')}
    result['linkReview']={k:v for k,v in link(view).items()if k not in('events','players','widgets')}
    result['linkReview']['eventsTail']=link(view)['events'][-6:]
    return result


class LinkFront(Upgrade):
    def open_link(self):
        self.do('linkqa_open');return self.wait(lambda v:v['screen'].endswith('LinkScreen'),'real link invitation screen')
    def button(self,text):return self.do('upgrade_button',dict(text=text))
    def native_picture(self,name):
        self.settle(3);before=self.see();row=self.d.capture(self.role,name);after=self.see()
        row.update(role=self.role,before=audit(before),after=audit(after),presentation=before['polish'].get('presentation'))
        self.out['screenshots'].append(row)
        if link(before)['active']:
            check(self.out,all(link(v)['active']and link(v)['ready']and link(v)['hasPicture']and v['frames']>0 for v in(before,after))and before['room']==after['room']and before['epoch']==after['epoch'],'same actual own-endpoint link presentation surrounds '+name,row)
        return row
    def bare_burn(self,rom,pin,destination,sentinel=False):
        before=self.see();check(self.out,at(before,destination)['empty'],'normal issued-card destination is empty',dict(role=self.role,slot=destination))
        blanks=sum(i.get('count',0)for i in items(before)if i.get('item')=='muxi_console:blank_cartridge')
        source=next(i['slot']for i in items(before)if i.get('item')=='muxi_console:blank_cartridge')
        self.writer();self.click_menu(self.player_menu_slot(source),0,'QUICK_MOVE')
        check(self.out,self.view()['slots'][0].get('item')=='muxi_console:blank_cartridge'and self.view()['slots'][0]['count']==1,'one actual author blank enters physical writer',self.view()['slots'][0])
        self.do('writer_drop',dict(rom=rom));self.do('writer_confirm');self.button('上传并刻录')
        issued=self.wait(lambda v:v['upgrade']['slots'][0].get('instance')and v['upgrade']['slots'][0].get('hash')==pin['sha256'],'normal rights-confirmed '+self.role+' '+rom+' issue')['upgrade']['slots'][0]
        self.click_menu(0);self.click_menu(self.player_menu_slot(destination));self.close();self.settle(2)
        if sentinel:
            self.d.command('server','upgrade_card_sentinel',dict(instance=issued['instance']))
            self.wait(lambda v:'qa_upgrade_card'in raw(v,issued['instance']).get('data',''),'normal issued host sentinel synchronizes')
        card=raw(self.see(),issued['instance'])
        check(self.out,card['hash']==pin['sha256']and card['count']==1 and sum(i.get('count',0)for i in items(self.see())if i.get('item')=='muxi_console:blank_cartridge')==blanks-1,'one real blank becomes one complete author-owned card',card)
        return card
    def press(self):
        before=self.see();self.do('linkqa_source');self.do('linkqa_key',dict(key=74,pressed=True));self.settle(6);self.do('linkqa_key',dict(key=74,pressed=False))
        return self.wait(lambda v:link(v)['ready']and v['crc']!=before['crc'],'own native CPU picture responds to real mapped A callback')
    def release(self):self.do('linkqa_key',dict(key=74,pressed=False));self.settle(4)


def cache(front,card):return front.do('cartridge_cache',dict(hash=card['hash']))['cacheCheck']['files']


def received_rom(view):
    return view['mediaMetrics']['operations'].get('rom_chunk',dict(packets=0,dataBytes=0,metadataBytes=0,wireBytes=0))


def metric_delta(before,after):
    if before['mediaMetrics']['startedAtNanos']!=after['mediaMetrics']['startedAtNanos']:raise AssertionError('actual receive metrics epoch changed')
    return {key:received_rom(after)[key]-received_rom(before)[key]for key in('packets','dataBytes','metadataBytes','wireBytes')}



def invite(host,guest):
    # Opening the guest's real menu first allocates its physical machine through the product, not a fixture.
    guest.open_link();host.open_link();name=host.d.marker['clientNames']['guest']
    view=host.wait(lambda v:any(p['name']==name and not p['busy']for p in link(v)['players']),'compatible actual peer appears after both menus opened')
    peer=next(p for p in link(view)['players']if p['name']==name)
    text=peer['name']+' · '+peer['platform'].upper()+' · 邀请'
    check(host.out,any(w['text']==text and w['active']for w in link(view)['widgets']),'invite action targets the observed live native widget',dict(role='host',text=text))
    host.button(text);received=guest.wait(lambda v:bool(link(v)['invitation'])and not link(v)['invitation']['host'],'real peer receives explicit-consent invitation')
    own=host.wait(lambda v:bool(link(v)['invitation'])and link(v)['invitation']['host'],'inviter sees its same pending invitation')
    same=link(received)['invitation']['link']==link(own)['invitation']['link']
    check(host.out,same and (host.out.get('preparingFromSingles')or not own['room']),'both native invitations identify the same server-authorized pair')
    return link(received)['invitation']


def worker_closed(d,pid,created):
    end=time.monotonic()+20;line=f'Link worker closure pid={pid} exit=0 forced=false nativeTempCleaned=true';log=d.root/'host/logs/latest.log'
    while time.monotonic()<end:
        current=environment.process_created(pid)
        if current is None or abs(current-created)>.1:
            if log.is_file():
                with log.open('rb')as source:source.seek(max(0,log.stat().st_size-2*1024*1024));text=source.read().decode('utf-8','replace')
                if line in text:return {'pid':pid,'created':created,'exited':True,'exitCode':0,'forced':False,'nativeTempCleaned':True,'log':str(log),'record':line}
        time.sleep(.1)
    raise AssertionError('actual link PID generation and logged normal native cleanup did not close')


def live(host,guest,pin,round_name):
    states={f.role:f.wait(lambda v:link(v)['active']and link(v)['ready']and link(v)['hasPicture']and v['frames']>=40,'actual '+round_name+' own-endpoint picture')for f in(host,guest)}
    h,g=states['host'],states['guest'];start=link(h)['lastStart'];other=link(g)['lastStart']
    check(host.out,h['room'].startswith('link/')and h['room']==g['room']and h['epoch']==g['epoch']and len(h['workers'])==1 and h['workerRunning']and not g['workers']and not g['workerRunning'],'one real host cable worker supplies two distinct physical machine endpoints',{r:audit(v)for r,v in states.items()})
    check(host.out,start['host']and not other['host']and start['bridge']==pin['platforms']['windows-x86_64']['librarySha256']and start['single']==pin['baseSingleCoreCompatibility']['librarySha256']and start['link']==other['link']and start['port']!=other['port'],'actual start distinguishes bridge identity, single format fingerprint and each endpoint port',dict(host=start,guest=other))
    check(host.out,h['crc']!=g['crc'],'two distinct diagnostic machines produce their own pictures',dict(host=h['crc'],guest=g['crc']))
    pid=h['workers'][0];created=environment.process_created(pid)
    check(host.out,created is not None,'actual host LinkWorker PID generation exists',dict(pid=pid,created=created))
    return states,{'pid':pid,'created':created}


def end_link(host,guest,worker,label):
    for front in(host,guest):front.do('polish_release_keys')
    host.open_link();host.button('保存并结束连线')
    ended={f.role:f.wait(idle,'normal '+label+' endpoint save/leave and lock release',60)for f in(host,guest)}
    closure=worker_closed(host.d,worker['pid'],worker['created']);host.out.setdefault('workerClosures',[]).append(closure)
    check(host.out,all(link(v)['lastEnd'].get('reason')and not link(v)['active']for v in ended.values()),'both clients observe the normal '+label+' end after final barrier',dict(clients={r:audit(v)for r,v in ended.items()},closure=closure))
    return ended


def exports(d,out,cards,machines,counters,label,pin):
    evidence=d.command('server','linkqa_observe');rows={}
    for role,card in cards.items():
        selected=[row for row in evidence['exports']if row['machine']==machines[role]and row['content']==card['hash']]
        check(out,len(selected)==1,'one actual '+role+' machine/content export at '+label,selected)
        row=selected[0];want=[0xa7,counters[role],1,0x3c]if role=='host'else[0x3c,counters[role],1,0xa7]
        check(out,row['sram']==want and row['diskSha256']==row['hash']and row['diskBytes']==row['size']and row['fingerprint']==pin['baseSingleCoreCompatibility']['librarySha256'],'actual '+role+' MXS1 keeps its serial/input/SRAM under the original machine key at '+label,row);rows[role]=row
    out.setdefault('barriers',[]).append(dict(label=label,evidence=evidence));return rows,evidence


def cleanup(d,out):
    try:
        for role in('host','guest'):
            f=LinkFront(d,out,role);f.do('polish_release_keys');v=f.see()
            if link(v)['active']or link(v)['preparing']:
                f.open_link();f.button('保存并结束连线')
            elif link(v)['invitation']:
                f.open_link();f.button('取消邀请'if link(v)['invitation']['host']else'拒绝')
        for role in('host','guest'):
            f=LinkFront(d,out,role);v=f.see()
            if v['room']and not v['room'].startswith('link/'):f.do('leave')
            f.wait(idle,'scoped real invitation/link/ordinary cleanup',60);f.close()
        out['finalClients']={role:audit(d.command(role,'observe'))for role in('host','guest')}
    except Exception as failure:out['cleanupFailure']=str(failure);out['scenarioComplete']=False


def run(d):
    path,out=record(d,'link');out.update(driverSha256=sha(Path(__file__)),upgradeHelperSha256=sha(Path(__file__).with_name('upgrade_review.py')),qaBuildSha256=sha(d.build_path));host=LinkFront(d,out,'host');guest=LinkFront(d,out,'guest')
    try:
        previous=upgrade_sources(d,out);out['scope']='pinned-candidate real two-author GB cable, consent/cancel, isolated input/audio, group cold restore and ordinary machine recovery; not a shared-controller session'
        product=Path(d.build['product']['path']);check(out,sha(product)==d.build['product']['sha256'],'exact current product source JAR remains pinned')
        with zipfile.ZipFile(product)as jar:pin=json.loads(jar.read('META-INF/muxi/sameboy-link.json'))
        out['nativePin']=pin;diagnostics=d.build['linkDiagnostics'];out['diagnostics']=diagnostics
        for name in('left','right'):check(out,sha(Path(diagnostics[name]['path']))==diagnostics[name]['sha256'],'exact original '+name+' serial ROM source is pinned',diagnostics[name])
        check(out,diagnostics['left']['sha256']!=diagnostics['right']['sha256'],'two independently issued ROM contents are distinct')
        baseline={f.role:f.see()for f in(host,guest)};check(out,all(idle(v)and cursor(v)['empty']for v in baseline.values()),'adopt same two idle real players without preparing or replacing inventory')
        original=previous['cards']['gb'];machine=at(baseline['host'],2)['machine'];check(out,same_card(inserted(baseline['host'],2),original),'original regular GB card is still in its original upgraded machine',at(baseline['host'],2))
        out['fixture']=d.command('server','linkqa_fixture');guest.craft(2,9,3)
        left=host.bare_burn('link_left',diagnostics['left'],4,True);right=guest.bare_burn('link_right',diagnostics['right'],4)
        cards={'host':left,'guest':right};out['cards']=cards
        host.inv();check(out,at(host.see(),7)['empty'],'original regular card has an empty explicit return slot');host.eject(2,original,7);host.install(left,2)
        guest.inv();guest.install(right,2)
        for f in(guest,host):f.do('slot',dict(slot=2));f.settle(3)
        check(out,not cache(host,right)and not cache(guest,left),'each author lacks the other author ROM before consent')
        first=invite(host,guest);out['cancelledInvitation']=first;guest.native_picture('link-invitation-consent')
        host.do('probe',dict(hash=right['hash']));denied=host.wait(lambda v:v['probe']!='pending','pre-consent read gets a real server decision')
        check(out,denied['probe']=='denied'and not cache(host,right),'pending invitation grants no partner ROM bytes',dict(probe=denied['probe']))
        guest.button('拒绝');cancelled={f.role:f.wait(idle,'normal invitation cancellation leaves both fresh CPUs unstarted')for f in(host,guest)}
        check(out,all(not link(v)['lastStart']for v in cancelled.values())and not cache(host,right),'decline starts no worker, transfers no partner ROM and releases the invitation',{r:audit(v)for r,v in cancelled.items()})
        second=invite(host,guest);check(out,first['link']!=second['link'],'new consent cannot reuse the cancelled invitation identity');transfer_before={f.role:f.see()for f in(host,guest)};guest.button('接受连线')
        running,worker=live(host,guest,pin,'first cable');machines={f.role:at(f.see(),2)['machine']for f in(host,guest)};out['machines']=machines
        check(out,machines['host']==machine and machines['guest']!=machine,'product allocates the second real physical identity without changing the first',machines)
        check(out,any(i['sha256']==right['hash']and i['bytes']==right['bytes']for i in cache(host,right))and not cache(guest,left),'accepted host actually fetches the missing partner ROM while peer receives no host ROM')
        transfer_after={f.role:f.see()for f in(host,guest)};out['transferMetrics']={r:dict(before=transfer_before[r]['mediaMetrics'],after=transfer_after[r]['mediaMetrics'],romChunkDelta=metric_delta(transfer_before[r],transfer_after[r]))for r in transfer_before}
        check(out,out['transferMetrics']['host']['romChunkDelta']['dataBytes']==diagnostics['right']['bytes']and out['transferMetrics']['host']['romChunkDelta']['packets']>0 and out['transferMetrics']['guest']['romChunkDelta']['dataBytes']==0,'actual authorized partner download is exactly the original ROM body while peer receives zero ROM body',out['transferMetrics'])
        guest_before=guest.see()['crc'];host.press();host.native_picture('link-host-own-machine');host.release();check(out,guest.see()['crc']==guest_before,'host A changes only its own actual machine image')
        host_before=host.see()['crc'];guest.press();guest.native_picture('link-guest-own-machine');guest.release();check(out,host.see()['crc']==host_before,'guest A changes only its own actual machine image')
        sound=guest.wait(lambda v:link(v)['receivedAudioBytes']>0 and link(v)['receivedAudioNonzero']>0 and link(v)['receivedAudioCrossings']>0,'real non-silent own-endpoint PCM reaches the peer')
        frequency=link(sound)['receivedAudioCrossings']*48000/(link(sound)['receivedAudioBytes']/4)
        check(out,not sound['workers']and 850<frequency<1200,'peer receives its approximately 1024 Hz own diagnostic tone without loading a core',dict(bytes=link(sound)['receivedAudioBytes'],nonzero=link(sound)['receivedAudioNonzero'],crossings=link(sound)['receivedAudioCrossings'],estimatedFrequency=frequency))
        end_link(host,guest,worker,'first cable');saved,evidence=exports(d,out,cards,machines,dict(host=1,guest=1),'first final barrier',pin)
        check(out,'group'in evidence and {(k['machine'],k['content'])for k in evidence['group']['keys']}=={(machines[r],cards[r]['hash'])for r in cards},'combined cable barrier is separate and names both physical machine/content keys',evidence.get('group'))
        first_group=evidence['group']['hash']
        host.do('probe',dict(hash=right['hash']));revoked=host.wait(lambda v:v['probe']!='pending','post-end server rechecks partner permission');check(out,revoked['probe']=='denied','ended link no longer grants partner ROM access even with its cache present')
        cold_before={f.role:f.see()for f in(host,guest)};invite(host,guest);guest.button('接受连线');cold,worker2=live(host,guest,pin,'cold group')
        cold_delta={f.role:metric_delta(cold_before[f.role],f.see())for f in(host,guest)};check(out,all(row['dataBytes']==0 for row in cold_delta.values()),'cold worker group recovery reuses both verified caches with zero new ROM body',cold_delta)
        check(out,link(cold['host'])['lastStart'].get('groupState',{}).get('hash')==first_group and worker2['pid']!=worker['pid'],'fresh actual worker restores the exact committed cable group',link(cold['host'])['lastStart'])
        host.native_picture('link-cold-group-host');host.press();host.release();end_link(host,guest,worker2,'cold-restored cable')
        saved,evidence=exports(d,out,cards,machines,dict(host=2,guest=1),'cold restored final barrier',pin)
        # Both ordinary workers must load their own exported MXS1, then normal leave checkpoints prepare a new pair.
        singles={}
        for f in(host,guest):
            f.do('cartridge_use',dict(hand='MAIN_HAND'));v=f.wait(lambda v:v['ready']and v['workerRunning']and v['room'].startswith('handheld/')and v['frames']>=30,'original single core restores '+f.role+' machine export')
            check(out,v['restoredStateHash']==saved[f.role]['hash'],'normal single restores the original '+f.role+' machine key, not partner progress',v['restoredStateHash']);singles[f.role]=v
        host.picture('link-single-machine-restored-host',singles['host']);guest.picture('link-single-machine-restored-guest',singles['guest'])
        out['preparingFromSingles']=True;invite(host,guest);guest.button('接受连线');prepared,worker3=live(host,guest,pin,'normal single save/leave preparation')
        check(out,not link(prepared['host'])['lastStart'].get('groupState')and len(link(prepared['host'])['lastStart']['states'])==2,'normal newer single checkpoints invalidate older group and each endpoint prepares independently',link(prepared['host'])['lastStart'])
        end_link(host,guest,worker3,'prepared-from-singles cable');exports(d,out,cards,machines,dict(host=2,guest=1),'prepared-from-singles final barrier',pin)
        finals={f.role:f.see()for f in(host,guest)}
        check(out,all(same_card(inserted(finals[r],2),cards[r])and at(finals[r],2)['machine']==machines[r]and cursor(finals[r])['empty']for r in cards)and same_card(raw(finals['host'],original['instance']),original),'all real author cards/full stacks and both physical machine identities survive every mode',{r:{'machine':at(v,2),'cursor':cursor(v)}for r,v in finals.items()})
        out['scenarioComplete']=True
    except Exception as failure:out['failure']=str(failure);raise
    finally:cleanup(d,out);out['observationTimeouts']=d.observation_timeouts;path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
    return out


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--run',type=Path,required=True);parser.add_argument('--build',type=Path,required=True);args=parser.parse_args();result=run(Driver(args.run,args.build));print(json.dumps({'complete':result['scenarioComplete'],'checks':len(result['checks']),'images':len(result['screenshots'])}))
