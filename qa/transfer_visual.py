"""Only supplement the completed same-run resumable-transfer picture using its original card/save."""
import argparse,json
from pathlib import Path
from drive import Driver,read_json,sha
from polish_review import record,check
from cartridge_review import Front,cleanup,inserted,same_card

def run(d):
    path,out=record(d,'resumable-transfer-visual');f=Front(d,out,'guest')
    source=d.channel/'resumable-transfer-continuation-result.json';prior=read_json(source)
    if not prior['scenarioComplete']or prior.get('cleanupFailure')or prior['runId']!=d.marker['runId']or prior['productSha256']!=d.build['product']['sha256']or prior['completedLoading']['resumeSeconds']<=60:raise ValueError('Require the completed exact-candidate slow transfer')
    out['source']={'path':str(source),'sha256':sha(source),'scope':'Only a new clean gameplay image; original transfer, cards and result are not replayed'}
    try:
        before=f.see()
        if before['room']or before['workers']or not same_card(inserted(before,7),prior['originalCard']):raise ValueError('Original idle guest machine and exact complete original card required')
        f.do('dock_view',{'hideGui':False});f.do('slot',{'slot':7})
        active=f.wait(lambda v:v['ready']and v['workerRunning']and v['frames']>=30 and v['restoredStateHash']==prior['finalSaveHash'],'same original guest machine restores the completed transfer save')
        f.do('cartridge_use',{'hand':'MAIN_HAND'});f.wait(lambda v:v['screen'].endswith('GameScreen'),'normal right click shows the same real emulator')
        f.pic('transfer-verified-resume-game',active)
        finished=f.leave('supplemental verified-cache picture');f.close();f.do('slot',{'slot':8})
        check(out,finished['downloadsGranted']==before['downloadsGranted'],'supplemental restored screenshot requests no more ROM chunks')
        out['finalSaveHash']=finished['savedStateHash'];out['scenarioComplete']=True
    except Exception as error:out['failure']=str(error);raise
    finally:cleanup(d,out);path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
    return out

if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--run',type=Path,required=True);p.add_argument('--build',type=Path,required=True);a=p.parse_args();v=run(Driver(a.run,a.build));print(json.dumps({'complete':v['scenarioComplete'],'images':len(v['screenshots'])}))
