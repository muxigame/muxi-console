"""Continue only the original unfocused-input QA failure, using its issued card and committed machine."""
from __future__ import annotations
import argparse,json
from pathlib import Path
from drive import Driver,read_json,sha
from polish_review import record,check
from cartridge_review import cleanup
from link_review import LinkFront
from media_review import received
from browser_media_review import finish

SOURCE='acfd02af1b2a419ca6e15c85fc23af4251b813ac02dbaa1edfe220dab763cc85'


def run(d):
    path,out=record(d,'browser-media-continuation')
    out.update(driverSha256=sha(Path(__file__)),finishDriverSha256=sha(Path(__file__).with_name('browser_media_review.py')),qaBuildSha256=sha(d.build_path))
    host,guest=(LinkFront(d,out,role)for role in ('host','guest'))
    try:
        source_path=d.channel/'browser-media-result.json';old=read_json(source_path)
        if (sha(source_path)!=SOURCE or old['runId']!=d.marker['runId'] or old['productSha256']!=d.build['product']['sha256']
                or old['scenarioComplete'] or len(old['checks'])!=7 or not all(c['passed']for c in old['checks'])
                or len(old.get('gpuSamples',[]))!=2 or old['screenshots'] or not old.get('failure','').startswith('real CPU picture changes after A input:') or old.get('cleanupFailure')):
            raise ValueError('Only the exact seven-check unfocused-input failure may continue')
        old_driver=Path(d.build_path).parent/'browser_media_review.executed.py'
        if sha(old_driver)!=old['driverSha256']:raise ValueError('Original executed driver bytes were not preserved')
        out['sourceFailure']={'path':str(source_path),'sha256':SOURCE,'executedDriver':str(old_driver),'driverSha256':sha(old_driver)}
        out['dependencies']=d.browser_dependencies();h,g=host.see(),guest.see();server=d.command('server','observe')
        if h['room']or h['workers']or g['room']or g['workers']or server['rooms']or server['pendingUploads']:
            raise ValueError('Original cleanup must leave no live worker, room or pending burn')
        d.assert_observer(g)
        card=old['card'];fixture=server['browser'];target={k:fixture[k]for k in ('position','side')}
        if not fixture['prepared']or not fixture['faceCreated']or fixture['bindings']!=0 or fixture['url']!=old['fixture']['url'] or not fixture['hostMayChange']:
            raise ValueError('Original browser face/owner/URL differs')
        device=next(v for v in server['devices']if v['position']==(10<<38|8<<12|90))
        if device.get('card',{}).get('instance')!=card['instance']or device['card']['hash']!=card['hash']or not device['powered']or device['ports']!=1:
            raise ValueError('Original card must remain installed in the same powered one-port console')
        saved=old['finalClients']['host']['savedStateHash']
        check(out,any(v['hash']==saved and v['machine']==device['identity']and v['content']==card['hash']for v in server['states']),'same actual latest machine save exists after normal first cleanup')
        out.update(card=card,fixture=fixture,sourceDevice=device)
        position=lambda role,x,z:d.command('server','position',dict(player=d.marker['clientNames'][role],x=x,z=z))
        position('host',10.5,11.5);host.do('slot',dict(slot=8));host.do('use_block',dict(x=10))
        host.wait(lambda v:v['screen'].endswith('DeviceScreen'),'same original occupied console panel')
        host.do('button',dict(text='P1'))
        active=host.wait(lambda v:v['ready']and v['workerRunning']and v['frames']>=60,'normal source recovery from its committed real checkpoint',120)
        check(out,active['restoredStateHash']==saved and received(active,'rom_chunk')==received(h,'rom_chunk'),'original machine checkpoint restores from its verified ROM cache without a new burn/download')
        position('host',13,11.5);position('guest',13.5,13.5);host.do('browser_select',target)
        d.wait_for('server',lambda v:v['browser']['bindings']==1,'normal rebind of original actual browser')
        out['gpuSamples']=[d.gpu_sample(role,target)for role in ('host','guest')]
        finish(d,out,host,guest,fixture,target,position,g)
    except Exception as error:out['failure']=str(error);raise
    finally:
        cleanup(d,out);out['observationTimeouts']=d.observation_timeouts
        path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
    return out


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--run',type=Path,required=True);parser.add_argument('--build',type=Path,required=True);args=parser.parse_args()
    result=run(Driver(args.run,args.build));print(json.dumps(dict(complete=result['scenarioComplete'],checks=len(result['checks']),images=len(result['screenshots']))))
