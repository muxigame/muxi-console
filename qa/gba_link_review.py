"""Fresh real GBA serial-link MC acceptance; uses existing normal-frontend helpers and exact native baseline."""
import argparse,json,zipfile
from pathlib import Path
from drive import Driver,sha,environment
from polish_review import record,check
from cartridge_review import at,cursor,inserted,same_card
from link_review import LinkFront,link,idle,audit,invite,live,end_link,exports,cleanup,cache,metric_delta


class GbaFront(LinkFront):
    def press(self):
        # This ARM diagnostic paints a constant own-endpoint color once; KEYINPUT
        # is proved by its actual SRAM counter at the save barrier, not a CRC change.
        before=self.see();self.do('linkqa_source');self.do('linkqa_key',dict(key=74,pressed=True));self.settle(6)
        return self.wait(lambda v:link(v)['ready']and v['frames']>before['frames']and v['polish']['presentation']['buttons']&(1<<8),'actual mapped GBA A callback while CPU frames advance')

def run(d):
    path,out=record(d,'gba-link');out.update(driverSha256=sha(Path(__file__)),upgradeHelperSha256=sha(Path(__file__).with_name('upgrade_review.py')),qaBuildSha256=sha(d.build_path));host=GbaFront(d,out,'host');guest=GbaFront(d,out,'guest')
    try:
        d.prepare();out['scope']='Real two-author GBA multiplayer SIO route, consent, independent CPU/AV/input/PCM, combined cold group and ordinary per-machine restore; no manufacturer BIOS or commercial program'
        product=Path(d.build['product']['path']);check(out,sha(product)==d.build['product']['sha256'],'exact current product source JAR remains pinned')
        with zipfile.ZipFile(product)as jar:pin=json.loads(jar.read('META-INF/muxi/mgba-link.json'))
        out['nativePin']=pin;diagnostics=d.build['gbaLinkDiagnostics'];out['diagnostics']=diagnostics
        for name in('left','right'):check(out,sha(Path(diagnostics[name]['path']))==diagnostics[name]['sha256'],'exact original '+name+' serial ROM source is pinned',diagnostics[name])
        check(out,diagnostics['left']['sha256']!=diagnostics['right']['sha256'],'two independently issued ROM contents are distinct')
        baseline={f.role:f.see()for f in(host,guest)};check(out,all(idle(v)and cursor(v)['empty']for v in baseline.values()),'two actual idle players begin with only ordinary empty fixture hardware')
        out['fixture']=d.command('server','linkqa_fixture',dict(platform='gba'))
        for f in(host,guest):f.do('dock_view',dict(hideGui=False));f.craft(2,9,5)
        left=host.bare_burn('link_gba_left',diagnostics['left'],4);right=guest.bare_burn('link_gba_right',diagnostics['right'],4)
        cards={'host':left,'guest':right};out['cards']=cards
        host.inv();host.install(left,2);guest.inv();guest.install(right,2)
        for f in(guest,host):f.do('slot',dict(slot=2));f.settle(3)
        check(out,not cache(host,right)and not cache(guest,left),'each author lacks the other author ROM before consent')
        first=invite(host,guest);machine=at(host.see(),2)['machine'];out['cancelledInvitation']=first;guest.native_picture('gba-link-invitation-consent')
        host.do('probe',dict(hash=right['hash']));denied=host.wait(lambda v:v['probe']!='pending','pre-consent read gets a real server decision')
        check(out,denied['probe']=='denied'and not cache(host,right),'pending invitation grants no partner ROM bytes',dict(probe=denied['probe']))
        guest.button('拒绝');cancelled={f.role:f.wait(idle,'normal invitation cancellation leaves both fresh CPUs unstarted')for f in(host,guest)}
        check(out,all(not link(v)['lastStart']for v in cancelled.values())and not cache(host,right),'decline starts no worker, transfers no partner ROM and releases the invitation',{r:audit(v)for r,v in cancelled.items()})
        second=invite(host,guest);check(out,first['link']!=second['link'],'new consent cannot reuse the cancelled invitation identity');transfer_before={f.role:f.see()for f in(host,guest)};guest.button('接受连线')
        running,worker=live(host,guest,pin,'first cable');check(out,all(v['width']==240 and v['height']==160 for v in running.values()),'both actual GBA CPUs render240x160 through GBA route');machines={f.role:at(f.see(),2)['machine']for f in(host,guest)};out['machines']=machines
        check(out,machines['host']==machine and machines['guest']!=machine,'product allocates the second real physical identity without changing the first',machines)
        check(out,any(i['sha256']==right['hash']and i['bytes']==right['bytes']for i in cache(host,right))and not cache(guest,left),'accepted host actually fetches the missing partner ROM while peer receives no host ROM')
        transfer_after={f.role:f.see()for f in(host,guest)};out['transferMetrics']={r:dict(before=transfer_before[r]['mediaMetrics'],after=transfer_after[r]['mediaMetrics'],romChunkDelta=metric_delta(transfer_before[r],transfer_after[r]))for r in transfer_before}
        check(out,out['transferMetrics']['host']['romChunkDelta']['dataBytes']==diagnostics['right']['bytes']and out['transferMetrics']['host']['romChunkDelta']['packets']>0 and out['transferMetrics']['guest']['romChunkDelta']['dataBytes']==0,'actual authorized partner download is exactly the original ROM body while peer receives zero ROM body',out['transferMetrics'])
        guest_before=guest.see()['crc'];host.press();host.native_picture('gba-link-host-own-machine');host.release();check(out,guest.see()['crc']==guest_before,'peer picture stays independent while host A is proved at the SRAM barrier')
        host_before=host.see()['crc'];guest.press();guest.native_picture('gba-link-guest-own-machine');guest.release();check(out,host.see()['crc']==host_before,'host picture stays independent while guest A is proved at the SRAM barrier')
        sound=guest.wait(lambda v:link(v)['receivedAudioBytes']>0 and link(v)['receivedAudioNonzero']>0 and link(v)['receivedAudioCrossings']>0 and link(v)['receivedAudioSeconds']>0,'real non-silent own-endpoint PCM reaches the peer')
        frequency=link(sound)['receivedAudioCrossings']/link(sound)['receivedAudioSeconds']
        check(out,not sound['workers']and link(sound)['receivedAudioUnknownRatePackets']==0 and 420<frequency<650,'peer receives its approximately 512 Hz own diagnostic tone without loading a core',dict(bytes=link(sound)['receivedAudioBytes'],nonzero=link(sound)['receivedAudioNonzero'],crossings=link(sound)['receivedAudioCrossings'],actualMinRate=link(sound)['receivedAudioMinRate'],actualMaxRate=link(sound)['receivedAudioMaxRate'],actualSeconds=link(sound)['receivedAudioSeconds'],estimatedFrequency=frequency))
        end_link(host,guest,worker,'first cable');saved,evidence=exports(d,out,cards,machines,dict(host=1,guest=1),'first final barrier',pin)
        check(out,'group'in evidence and {(k['machine'],k['content'])for k in evidence['group']['keys']}=={(machines[r],cards[r]['hash'])for r in cards},'combined cable barrier is separate and names both physical machine/content keys',evidence.get('group'))
        first_group=evidence['group']['hash']
        host.do('probe',dict(hash=right['hash']));revoked=host.wait(lambda v:v['probe']!='pending','post-end server rechecks partner permission');check(out,revoked['probe']=='denied','ended link no longer grants partner ROM access even with its cache present')
        cold_before={f.role:f.see()for f in(host,guest)};invite(host,guest);guest.button('接受连线');cold,worker2=live(host,guest,pin,'cold group')
        cold_delta={f.role:metric_delta(cold_before[f.role],f.see())for f in(host,guest)};check(out,all(row['dataBytes']==0 for row in cold_delta.values()),'cold worker group recovery reuses both verified caches with zero new ROM body',cold_delta)
        check(out,link(cold['host'])['lastStart'].get('groupState',{}).get('hash')==first_group and worker2['pid']!=worker['pid'],'fresh actual worker restores the exact committed cable group',link(cold['host'])['lastStart'])
        host.native_picture('gba-link-cold-group-host');host.press();host.release();end_link(host,guest,worker2,'cold-restored cable')
        saved,evidence=exports(d,out,cards,machines,dict(host=2,guest=1),'cold restored final barrier',pin)
        # Both ordinary workers must load their own exported MXS1, then normal leave checkpoints prepare a new pair.
        singles={}
        for f in(host,guest):
            f.do('cartridge_use',dict(hand='MAIN_HAND'));v=f.wait(lambda v:v['ready']and v['workerRunning']and v['room'].startswith('handheld/')and v['frames']>=30,'original single core restores '+f.role+' machine export')
            check(out,v['restoredStateHash']==saved[f.role]['hash'],'normal single restores the original '+f.role+' machine key, not partner progress',v['restoredStateHash']);singles[f.role]=v
        host.picture('gba-link-single-machine-restored-host',singles['host']);guest.picture('gba-link-single-machine-restored-guest',singles['guest'])
        out['preparingFromSingles']=True;invite(host,guest);guest.button('接受连线');prepared,worker3=live(host,guest,pin,'normal single save/leave preparation')
        check(out,not link(prepared['host'])['lastStart'].get('groupState')and len(link(prepared['host'])['lastStart']['states'])==2,'normal newer single checkpoints invalidate older group and each endpoint prepares independently',link(prepared['host'])['lastStart'])
        end_link(host,guest,worker3,'prepared-from-singles cable');exports(d,out,cards,machines,dict(host=2,guest=1),'prepared-from-singles final barrier',pin)
        finals={f.role:f.see()for f in(host,guest)}
        check(out,all(same_card(inserted(finals[r],2),cards[r])and at(finals[r],2)['machine']==machines[r]and cursor(finals[r])['empty']for r in cards),'both normally issued GBA whole-card stacks and physical machine identities survive every mode',{r:{'machine':at(v,2),'cursor':cursor(v)}for r,v in finals.items()})
        out['scenarioComplete']=True
    except Exception as failure:out['failure']=str(failure);raise
    finally:cleanup(d,out);out['observationTimeouts']=d.observation_timeouts;path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
    return out


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--run',type=Path,required=True);p.add_argument('--build',type=Path,required=True);a=p.parse_args();v=run(Driver(a.run,a.build));print(json.dumps(dict(complete=v['scenarioComplete'],checks=len(v['checks']),images=len(v['screenshots']))))
