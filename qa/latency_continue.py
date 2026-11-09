"""Continue only the exact pre-input creative-inventory QA failure; never burns or prepares again."""
from __future__ import annotations
import argparse,json
from pathlib import Path
from drive import Driver,read_json,sha
from polish_review import record,check
from cartridge_review import at,raw,cursor
from latency_review import LatencyFront,gba_loaded,finish_result,source_inputs

SOURCE_SHA='a3b296caef7f916cbc1ff2e96bfca0a4690d34b3c3bd0a3f422b055db7890d69'


def run(d,expected):
    source=d.channel/'latency-gba-result.json';base=read_json(source)
    if expected!=SOURCE_SHA or sha(source)!=expected or base['scenarioComplete']or len(base['checks'])!=2 or not all(c['passed']for c in base['checks'])or base['screenshots']or base.get('cleanupFailure')or base.get('latencyCleanupFailure')or base.get('failure')!='java.lang.IllegalStateException: java.lang.IllegalStateException: Actual vanilla inventory screen required' or base['runId']!=d.marker['runId']or base['productSha256']!=d.build['product']['sha256']or any(v['workers']or v['normalWorkerExits']or v['forcedWorkerExits']or v['failedWorkerExits']for v in base['finalClients'].values()):raise ValueError('Only the exact preserved pre-worker fixture failure can continue')
    path,out=record(d,'latency-gba-continuation');f=LatencyFront(d,out);inputs=source_inputs();inputs[Path(__file__).resolve()]=sha(Path(__file__).resolve())
    out.update(source=dict(path=str(source),sha256=expected,passed=2,images=0,scenarioComplete=False),card=base['card'],scope='Same actual run, candidate, physical issued card and empty GBA. Change the fixture to normal survival inventory; no prepare, burn, identity or input replay.')
    try:
        v=f.see();other=d.command('guest','observe');card=out['card']
        check(out,all(not q['room']and not q['workers']and cursor(q)['empty']for q in (v,other))and raw(v,card['instance'])['nbt']==card['nbt']and raw(v,card['instance'])['slot']==3 and at(v,2).get('model')==5 and at(v,2).get('inserted',{}).get('empty')is True and not at(v,2).get('machine'),'adopt the actual unchanged issued card and idle original shell with no worker/cursor')
        f.close();d.command('server','hardware_mode',dict(mode='survival'));f.wait(lambda q:q['gameMode']=='survival','normal server survival mode reaches the client');f.inv()
        check(out,f.see()['screen']=='net.minecraft.client.gui.screens.inventory.InventoryScreen','actual survival inventory is the vanilla item-transfer screen')
        f.install(card,2);f.close();f.do('slot',dict(slot=2));gba_loaded(d,out,f,card)
    except Exception as error:out['failure']=str(error);raise
    finally:finish_result(d,path,out,inputs)
    return out


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--run',type=Path,required=True);p.add_argument('--build',type=Path,required=True);p.add_argument('--source-sha256',required=True);a=p.parse_args();v=run(Driver(a.run,a.build),a.source_sha256);print(json.dumps(dict(complete=v['scenarioComplete'],checks=len(v['checks']),images=len(v['screenshots']))))
