"""Three original programs: real writer, screen callbacks, native files and cold worker restore. Never launches Minecraft."""
from __future__ import annotations
import argparse,json,time
from pathlib import Path
from drive import Driver,sha
from polish_review import record,check
from cartridge_review import at,raw,items,cleanup
from upgrade_review import Upgrade,inventory_slot


def run(d,platforms=('nds','n3ds','wii')):
    platforms=tuple(platforms)
    if not platforms or len(set(platforms))!=len(platforms) or any(p not in ('nds','n3ds','wii')for p in platforms):raise ValueError('Choose each requested original expansion platform once')
    programs=d.build.get('expansionDiagnostics',{}).get('programs',{})
    if set(programs)!={'nds','n3ds','wii'}:raise ValueError('Build QA with the pinned three original expansion diagnostics')
    path,out=record(d,'expansion'if platforms==('nds','n3ds','wii')else'expansion-'+'-'.join(platforms));f=Upgrade(d,out);out.update(cards={},platforms={},selectedPlatforms=list(platforms),startupObservations={},scope='Actual normal callbacks through NativeControls/InputCodec/core, original no-BIOS programs, local server profile/state commit and cold worker restart; physical sensors and commercial disc images are not tested.')
    inputs={Path(__file__).resolve().with_name(name):None for name in('expansion_review.py','drive.py','polish_review.py','cartridge_review.py','upgrade_review.py')};inputs={p:sha(p)for p in inputs};out['driverInputs']={str(p):digest for p,digest in inputs.items()}
    def world():return d.command('server','expansion_observe')
    def ready(platform,restored=None):
        phase='cold'if restored is not None else'first'
        observed=[];out['startupObservations'][platform+'-'+phase]=observed
        def actual(v):
            session=v['expansion']['lastSession'];progress=v['cartridge']['progress']
            if session.get('platform')==platform and progress.get('card')==session.get('instance'):
                sample=dict(platform=session['platform'],extension=session.get('extension'),stage=progress['stage'],text=progress['text'],loading=progress['loading'],screen=v['screen'],room=v['room'],epoch=v['epoch'],frames=v['frames'])
                if not observed or any(sample[k]!=observed[-1][k]for k in('stage','text','screen')):observed.append(sample)
                if platform=='wii'and progress['stage']=='starting'and v['screen'].endswith('GameScreen')and not any(row.get('startupPhase')==phase for row in out['screenshots']):
                    row=d.capture('host','expansion-wii-'+phase+'-starting');after=f.see()
                    row.update(role='host',startupPhase=phase,actualSession=session,progressBefore=progress,progressAfter=after['cartridge']['progress'],presentation=after['polish'].get('presentation'))
                    out['screenshots'].append(row)
            return v['ready']and v['workerRunning']and v['frames']>=30 and session.get('platform')==platform and(restored is None or v['restoredStateHash']==restored)
        value=f.wait(actual,'actual '+platform+' worker and expected restore',60)
        if platform=='wii':check(out,bool(observed)and all('gba'not in s['text'].lower()for s in observed),'actual Wii session progress contains no other platform label',observed)
        return value
    def focus(platform):
        v=f.see()
        if not v['screen'].endswith('GameScreen'):f.do('cartridge_use',dict(hand='MAIN_HAND'))
        f.wait(lambda v:v['screen'].endswith('GameScreen'),'normal fullscreen entry')
        f.do('expansion_source');return f.wait(lambda v:v['polish'].get('presentation',{}).get('screens'),'actual rendered AV surfaces')
    def key(bit=None,extra=None,pressed=True):f.do('expansion_key',dict(pressed=pressed,**({'bit':bit}if bit is not None else{'extra':extra})))
    def pointer(platform,pressed=True):f.do('expansion_pointer',dict(surface='primary'if platform=='wii'else'bottom',x=.35,y=.6,pressed=pressed))
    def capture(platform,label):
        f.settle(4);before=f.see();row=d.capture('host','expansion-'+platform+'-'+label);after=f.see();fields=('room','epoch','workers','ready','workerRunning','frames','crc')
        row.update(role='host',before={k:before[k]for k in fields},after={k:after[k]for k in fields},expansionBefore=before['expansion'],expansionAfter=after['expansion'],presentation=before['polish'].get('presentation'))
        out['screenshots'].append(row)
        check(out,all(v['ready']and v['workerRunning']and v['room']==before['room']and v['epoch']==before['epoch']and v['workers']==before['workers']for v in(before,after)),'same actual '+platform+' worker surrounds '+label)
        flow=after['expansion'].get('frameFlow',{})
        if after['expansion']['lastSession'].get('frameFlow')is True:check(out,flow.get('enabled')is True and flow.get('acknowledgedBatches',0)>0 and 0<flow.get('peakQueued',0)<=8,'actual '+platform+' batch completion ACKs keep the negotiated window bounded',flow)
    def burn(platform,destination):
        f.writer();f.click_menu(f.player_menu_slot(0),0,'QUICK_MOVE');check(out,f.view()['slots'][0].get('item')=='muxi_console:blank_cartridge'and f.view()['slots'][0]['count']==1,'one physical blank is in the writer for '+platform)
        f.do('expansion_drop',dict(platform=platform));f.do('writer_confirm');f.do('button',dict(text='上传并刻录'))
        issued=f.wait(lambda v:v['upgrade']['slots'][0].get('instance')and v['upgrade']['slots'][0].get('hash')==programs[platform]['sha256'],'normal rights-confirmed '+platform+' burn')['upgrade']['slots'][0]
        f.click_menu(0);f.click_menu(f.player_menu_slot(destination));f.close()
        v=f.wait(lambda v:any(i.get('instance')==issued['instance']for i in items(v)),'issued original card reaches inventory');card=raw(v,issued['instance']);out['cards'][platform]=card
        check(out,card['platform']==platform and card['hash']==programs[platform]['sha256']and card['count']==1,'actual issued '+platform+' card matches the pinned original program');return card
    def save(platform):
        before=f.see();owner=before['machine'];world();f.do('polish_release_keys')
        ended=f.leave(platform+' checkpoint');f.do('slot',dict(slot=8));f.close();w=world();state=next(s for s in w['saved']if s['machine']==owner['machine']and s['content']==owner['content']);profile=w['machines'][platform]['profile']
        check(out,state['hash']==ended['savedStateHash']==state['diskSha256']and state['size']==state['diskBytes']>0,'world disk stores the actual '+platform+' state transport SHA',state)
        check(out,state.get('profileGeneration')==profile['generation']and profile['size']>0,'state barrier binds the confirmed '+platform+' native profile generation',profile)
        return owner,state,profile,ended['expansion'].get('nativeDiagnosticFile')
    def handheld_start(platform,fixture_slot,card):
        f.inv();f.do('upgrade_click',dict(slot=inventory_slot(fixture_slot),button=2,type='SWAP'));f.wait(lambda v:at(v,2).get('model')==(23 if platform=='nds'else 24),'normal hotbar swap preserves the real empty machine');f.install(card,2);f.close();f.do('slot',dict(slot=2));return ready(platform)
    def wii_panel(join,capture_panel=False):
        f.do('slot',dict(slot=8));f.do('use_block',dict(x=5,y=90,z=6));f.wait(lambda v:v['screen'].endswith('DeviceScreen'),'actual Wii management menu')
        if capture_panel:f.picture('expansion-wii-management')
        if join:
            if not world()['wiiDevice']['powered']:f.do('button',dict(text='启动 / 停止'));f.settle(3)
            f.do('button',dict(text='P1 加入'))
    try:
        d.prepare();out['fixture']=d.command('server','expansion_prepare');f.do('dock_view',dict(hideGui=False));f.settle(12)
        if 'wii'in platforms:
            f.do('linkqa_open');f.wait(lambda v:v['screen'].endswith('LinkScreen'),'normal product link-list screen without starting a session');f.picture('expansion-native-link-list');f.close()
        for platform in platforms:
            index=('nds','n3ds','wii').index(platform)
            card=burn(platform,3+index)
            if platform!='wii':started=handheld_start(platform,9+index,card)
            else:
                f.inv();f.do('upgrade_click',dict(slot=inventory_slot(11),button=8,type='SWAP'));f.close();f.do('slot',dict(slot=8));d.command('server','position',dict(player=d.marker['clientNames']['host'],x=5.5,z=9.5));f.settle(4);f.do('expansion_place')
                f.wait(lambda v:at(v,8)['empty'],'normal desktop placement consumes the one real hardware item');check(out,world()['wiiDevice']['model']==25,'actually placed Wii model retains its hardware identity')
                f.do('select_card',dict(instance=card['instance']));f.do('use_block',dict(x=5,y=90,z=6));f.close()
                controller=at(f.see(),1);check(out,controller.get('item')=='muxi_console:controller'and controller['count']>=1 and world()['wiiDevice']['ports']==0,'placed Wii has no port until an existing physical controller is connected')
                f.do('slot',dict(slot=1));f.do('use_block',dict(x=5,y=90,z=6));f.wait(lambda v:at(v,1).get('count',0)==controller['count']-1,'normal controller use consumes one physical controller')
                check(out,world()['wiiDevice']['ports']==1,'normal controller connection exposes the real Wii P1 port')
                wii_panel(True,True);started=ready(platform)
            v=focus(platform);surface_ids={p['id']for p in v['expansion']['surfaces']};check(out,surface_ids==({'primary'}if platform=='wii'else{'top','bottom'}),'actual '+platform+' AV surface layout',v['expansion']['surfaces'])
            pointer(platform)
            if platform=='n3ds':key(extra='TILT_RIGHT')
            if platform=='n3ds':key(extra='BLOW')
            f.settle(14);key(bit=8)
            v=f.wait(lambda v:v['expansion']['input']['buttons']&(1<<8)and(platform=='wii'or v['expansion'].get('centreRGB',{}).get('top',[255,0,0])[1]>180),'ordinary configured A binding reaches '+platform+' game')
            if platform!='wii':
                f.wait(lambda v:(lambda c:c[0]>180 and c[1]>150 and c[2]<60)(v['expansion'].get('centreRGB',{}).get('bottom',[0,0,255])),'actual bottom-screen touch changes '+platform+' pixels')
                check(out,v['expansion']['input']['pointer']['surface']=='bottom'and v['expansion']['input']['pointer']['pressed'],'rendered bottom-screen callback maps to actual '+platform+' input')
            if platform=='n3ds':
                v=f.wait(lambda v:len(v['expansion'].get('nativeDiagnosticFile',{}).get('words',[]))==6 and v['expansion']['nativeDiagnosticFile']['words'][5]>0 and v['expansion']['nativeDiagnosticFile']['words'][3]!=0,'3DS application consumes real virtual tilt and blow PCM')
                check(out,v['expansion']['nativeDiagnosticFile']['words'][0]==0x4d584433,'3DS original native save contains actual application sensor samples',v['expansion']['nativeDiagnosticFile'])
            if platform=='wii':
                v=f.wait(lambda v:len(v['expansion'].get('nativeDiagnosticFile',{}).get('words',[]))==16 and v['expansion']['nativeDiagnosticFile']['words'][3]&8,'Wii application consumes actual A')
                check(out,v['expansion']['nativeDiagnosticFile']['words'][0]==0x4d585749 and v['expansion']['nativeDiagnosticFile']['words'][6]==1,'Wii original native save records actual pointer IR',v['expansion']['nativeDiagnosticFile'])
                out['wiiIRSample']=v['expansion']['nativeDiagnosticFile']
            capture(platform,'screen'if platform=='wii'else'model')
            if platform!='wii':
                f.do('polish_mouse',dict(button=1,pressed=True));f.do('polish_mouse',dict(button=1,pressed=False));f.wait(lambda v:v['polish'].get('presentation',{}).get('mode')=='screen','RMB changes only presentation');pointer(platform);capture(platform,'screen')
            key(bit=8,pressed=False);pointer(platform,False)
            if platform=='wii':
                f.settle(4);key(extra='TILT_RIGHT');f.settle(14);key(bit=8)
                v=f.wait(lambda v:len(v['expansion'].get('nativeDiagnosticFile',{}).get('words',[]))==16 and v['expansion']['nativeDiagnosticFile']['words'][2]>out['wiiIRSample']['words'][2]and any(v['expansion']['nativeDiagnosticFile']['words'][i]for i in(9,10)),'Wii application consumes separately controlled virtual accelerometer')
                check(out,v['expansion']['nativeDiagnosticFile']['words'][3]&8,'second ordinary A press records actual Wii motion sample',v['expansion']['nativeDiagnosticFile']);key(bit=8,pressed=False)
            if platform!='nds':key(extra='TILT_RIGHT',pressed=False)
            if platform=='n3ds':key(extra='BLOW',pressed=False)
            f.settle(4);owner,state,profile,native_file=save(platform);out['platforms'][platform]=dict(owner=owner,state=state,profile=profile,nativeFile=native_file)
            downloads=f.see()['expansion']['profileReadBytes']
            if platform=='wii':wii_panel(True)
            else:f.do('slot',dict(slot=2))
            restored=ready(platform,state['hash']);check(out,restored['machine']==owner and restored['expansion']['lastSession']['profile']['generation']==profile['generation']and restored['expansion']['profileReadBytes']-downloads>=profile['size'],'cold '+platform+' worker downloads the actual machine profile then restores its bound state')
            if native_file:check(out,restored['expansion'].get('nativeDiagnosticFile',{}).get('sha256')==native_file['sha256'],'cold '+platform+' boot retains the actual application native-file bytes')
            focus(platform);capture(platform,'cold');f.leave(platform+' cold cleanup');f.do('slot',dict(slot=8));f.close()
        check(out,len(out['cards'])==len(platforms)and len({c['instance']for c in out['cards'].values()})==len(platforms),'every selected original platform card was issued once by actual normal burns')
        out['scenarioComplete']=True
    except Exception as error:out['failure']=str(error);raise
    finally:
        cleanup(d,out);out['observationTimeouts']=d.observation_timeouts;out['driverDrift']=[str(p)for p,digest in inputs.items()if sha(p)!=digest]
        if out['driverDrift']:out['scenarioComplete']=False
        path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
    return out


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--run',type=Path,required=True);parser.add_argument('--build',type=Path,required=True);parser.add_argument('--platforms',nargs='+',choices=('nds','n3ds','wii'),default=('nds','n3ds','wii'),help='Only selected original programs; default retains all three');args=parser.parse_args();result=run(Driver(args.run,args.build),args.platforms);print(json.dumps(dict(complete=result['scenarioComplete'],checks=len(result['checks']),images=len(result['screenshots']))))
