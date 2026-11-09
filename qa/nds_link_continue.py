"""Strict same-candidate continuation after a short QA A pulse overlapped a real large autosave pause."""
from __future__ import annotations
import argparse,json,time,zipfile
from pathlib import Path
from drive import Driver,read_json,sha
from polish_review import record,check
from cartridge_review import at,cursor,inserted,same_card
from link_review import link,idle,invite,metric_delta
from nds_link_review import DsFront,live,end_link,barrier,cleanup

SOURCE='8c993574c619629b7d3885d228ddc369ffd8352352c212fc8c34019a23e4049a'

class HeldFront(DsFront):
    def press(self,expected):
        before=self.see();began=time.monotonic();self.do('linkqa_source');self.do('linkqa_key',dict(key=74,pressed=True))
        try:
            down=self.wait(lambda v:v['linkDs']['input']['buttons']&(1<<8),'normal NDS A callback is actually captured')
            after=self.wait(lambda v:v['ready']and v['frames']>before['frames']and v['linkDs'].get('counter')==expected,'held A reaches its own actual ARM9 CPU after any active upload pause',180)
            self.out.setdefault('inputEvidence',[]).append(dict(role=self.role,expected=expected,seconds=time.monotonic()-began,beforeFrame=before['frames'],afterFrame=after['frames'],sampled=down['linkDs']['input'],cpuCounter=after['linkDs']['counter']))
            return after
        finally:self.do('linkqa_key',dict(key=74,pressed=False));self.settle(4)

def run(d):
    source=d.channel/'nds-link-result.json';previous=read_json(source)
    if sha(source)!=SOURCE or previous['scenarioComplete']or len(previous['checks'])!=28 or not all(c['passed']for c in previous['checks'])or len(previous['screenshots'])!=1 or previous.get('cleanupFailure')or not previous.get('failure','').startswith('actual own ARM9 A-edge counter reaches 1:')or previous['runId']!=d.marker['runId']or previous['productSha256']!=d.build['product']['sha256']:raise ValueError('Exact preserved native short-pulse failure and current same run/product are required')
    path,out=record(d,'nds-link-continuation');out.update(source=dict(path=str(source),sha256=SOURCE,scenarioComplete=False,passed=28,images=1),driverSha256=sha(Path(__file__)),helperSha256=sha(Path(__file__).with_name('nds_link_review.py')),cards=previous['cards'],machines=previous['machines'],scope='Same real cards/machines and latest actually committed DNL1; hold real A until CPU observation before release. No prepare/burn/state injection. This proves function, never calls autosave freezing smooth.')
    host=HeldFront(d,out,'host');guest=HeldFront(d,out,'guest');cards=out['cards'];machines=out['machines']
    try:
        with zipfile.ZipFile(d.build['product']['path'])as jar:pin=json.loads(jar.read('META-INF/muxi/melondsds-link.json'))
        out['nativePin']=pin
        baseline={f.role:f.see()for f in(host,guest)};check(out,all(idle(v)and cursor(v)['empty']and same_card(inserted(v,2),cards[r])and at(v,2)['machine']==machines[r]for r,v in baseline.items()),'same original two complete issued cards and machines are idle after the retained false cleanup')
        _,original_group=barrier(d,out,cards,machines,'retained actual cleanup group',pin)
        invite(host,guest);guest.button('接受连线');running,workers=live(host,guest,pin,'retained cleanup group',dict(host=0,guest=0));check(out,link(running['host'])['lastStart'].get('groupState',{}).get('hash')==original_group['hash'],'fresh actual pair imports the original false cleanup checkpoint, without fabricating its counters')
        host.press(1);check(out,guest.see()['linkDs']['counter']==0,'host held A increments only its own native CPU');guest.press(1);check(out,host.see()['linkDs']['counter']==1,'partner held A increments only its own native CPU')
        for f in(host,guest):f.picture('nds-link-'+f.role+'-model')
        guest.do('dock_right_click');guest.wait(lambda v:v['polish']['presentation'].get('mode')=='screen','ordinary right-click dual-screen mode');guest.picture('nds-link-guest-dual-screen');guest.do('linkqa_source');guest.do('linkds_pointer',dict(surface='bottom',x=.65,y=.4,pressed=True));pressed=guest.wait(lambda v:v['linkDs']['input']['pointer']['valid']and v['linkDs']['input']['pointer']['pressed'],'actual lower-screen pointer is captured');port=link(pressed)['port'];relayed=host.wait(lambda v:len(v['linkDs']['stepInputs'])==2 and v['linkDs']['stepInputs'][port]['pointer']['pressed'],'actual peer touch enters server input codec',90);guest.do('linkds_pointer',dict(surface='bottom',x=.65,y=.4,pressed=False));host.wait(lambda v:not v['linkDs']['stepInputs'][port]['pointer']['pressed'],'actual pointer release reaches the pair',90)
        check(out,relayed['linkDs']['stepInputs'][port]['pointer']['surface']=='bottom','real GUI/source/Link-codec path retains lower-screen touch; this radio diagnostic itself has no touch game logic',dict(frontend=pressed['linkDs']['input'],actualStep=relayed['linkDs']['stepInputs']))
        host.do('dock_esc');host.do('review_view',dict(held=True));host.settle(5);host.picture('nds-link-world-dual-lcd');host.open_link();host.button('返回自己的游戏机')
        end_link(host,guest,workers,'continued held-input wireless');saved,group=barrier(d,out,cards,machines,'continued held-input final barrier',pin)
        before={f.role:f.see()for f in(host,guest)};invite(host,guest);guest.button('接受连线');cold,cold_workers=live(host,guest,pin,'actual cold group',dict(host=1,guest=1));check(out,link(cold['host'])['lastStart'].get('groupState',{}).get('hash')==group['hash']and link(cold['host'])['lastStart'].get('frameBase')==group['frame'],'new native pair cold-restores the exact input-bearing DNL1 and logical barrier')
        check(out,all(metric_delta(before[f.role],f.see())['dataBytes']==0 for f in(host,guest)),'cold pair receives no ROM body after verified original caches');host.press(2);host.picture('nds-link-cold-group-host');end_link(host,guest,cold_workers,'cold restored wireless');saved,_=barrier(d,out,cards,machines,'cold final barrier',pin)
        for f in(host,guest):
            f.do('cartridge_use',dict(hand='MAIN_HAND'));v=f.wait(lambda v:v['ready']and v['workerRunning']and v['room'].startswith('handheld/')and v['frames']>=30 and v['linkDs'].get('counter')==(2 if f.role=='host'else 1),'original ordinary '+f.role+' worker restores its CPU counter and profile',120);check(out,v['restoredStateHash']==saved[f.role]['hash'],'ordinary '+f.role+' imports its own exact exported MXS1 generation',dict(restored=v['restoredStateHash'],saved=saved[f.role]))
        host.picture('nds-link-ordinary-machine-host');out['preparingFromSingles']=True;invite(host,guest);guest.button('接受连线');prepared,last_workers=live(host,guest,pin,'prepared ordinary pair',dict(host=2,guest=1));check(out,not link(prepared['host'])['lastStart'].get('groupState')and all(s.get('hash')for s in link(prepared['host'])['lastStart']['states']),'new ordinary generations invalidate the older group and both real saved CPU states prepare independently')
        end_link(host,guest,last_workers,'prepared ordinary wireless');barrier(d,out,cards,machines,'prepared ordinary final barrier',pin)
        finals={f.role:f.see()for f in(host,guest)};check(out,all(same_card(inserted(finals[r],2),cards[r])and at(finals[r],2)['machine']==machines[r]and cursor(finals[r])['empty']for r in cards),'same complete original cards and physical identities survive the entire strict continuation');out['scenarioComplete']=True
    except Exception as failure:out['failure']=str(failure);raise
    finally:cleanup(d,out);out['observationTimeouts']=d.observation_timeouts;path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
    return out

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--run',type=Path,required=True);p.add_argument('--build',type=Path,required=True);a=p.parse_args();r=run(Driver(a.run,a.build));print(json.dumps(dict(complete=r['scenarioComplete'],checks=len(r['checks']),images=len(r['screenshots']))))
