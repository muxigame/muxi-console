"""Wii desk slot panel, built-in seat, audio and linked big-screen rotation sync (own wall + relayed copy). Never launches Minecraft."""
from __future__ import annotations
import argparse,json,time
from pathlib import Path
from drive import Driver,sha
from polish_review import record,check
from cartridge_review import at,raw,items,cleanup
from upgrade_review import Upgrade,inventory_slot

DESK=dict(x=5,y=90,z=6)
SCREEN_FLOOR=dict(x=7,y=89,z=5)


def run(d):
    programs=d.build.get('expansionDiagnostics',{}).get('programs',{})
    if 'wii' not in programs:raise ValueError('Build QA with the pinned original Wii diagnostic')
    path,out=record(d,'wall');f=Upgrade(d,out);g=Upgrade(d,out,'guest')
    out.update(scope='Original Wii diagnostic on a real placed desk: vanilla disc slot panel, built-in controller seat without a controller item, runtime PCM delivery, linked world screen following the game-window rotation for the playing client and for a nearby non-controller through the server relay. Commercial discs, speakers and physical pads are not tested.')
    inputs={p:sha(p)for p in(Path(__file__).resolve().with_name(n)for n in('wall_review.py','drive.py','polish_review.py','cartridge_review.py','upgrade_review.py'))};out['driverInputs']={str(p):v for p,v in inputs.items()}
    def world():return d.command('server','expansion_observe')
    def wall(v):return v['expansion'].get('wall',{})
    def panel():
        f.do('slot',dict(slot=8));f.do('use_block',DESK);return f.wait(lambda v:v['screen'].endswith('DeviceScreen'),'right-click opens the Wii console slot panel')
    try:
        d.prepare();out['fixture']=d.command('server','expansion_prepare');f.do('dock_view',dict(hideGui=False));f.settle(12)
        # Burn the original Wii program through the ordinary writer.
        f.writer();f.click_menu(f.player_menu_slot(0),0,'QUICK_MOVE')
        f.do('expansion_drop',dict(platform='wii'));f.do('writer_confirm');f.do('button',dict(text='上传并刻录'))
        issued=f.wait(lambda v:v['upgrade']['slots'][0].get('instance')and v['upgrade']['slots'][0].get('hash')==programs['wii']['sha256'],'normal rights-confirmed Wii burn')['upgrade']['slots'][0]
        f.click_menu(0);f.click_menu(f.player_menu_slot(5));f.close()
        v=f.wait(lambda v:any(i.get('instance')==issued['instance']for i in items(v)),'issued disc reaches inventory');card=raw(v,issued['instance']);out['card']=card
        # Place the Wii desk.
        f.inv();f.do('upgrade_click',dict(slot=inventory_slot(11),button=8,type='SWAP'));f.close();f.do('slot',dict(slot=8))
        d.command('server','position',dict(player=d.marker['clientNames']['host'],x=5.5,z=9.5));f.settle(4);f.do('expansion_place')
        f.wait(lambda v:at(v,8)['empty'],'desk placement consumes the hardware item');check(out,world()['wiiDevice']['model']==25,'placed desk is the Wii model')
        check(out,world()['wiiDevice']['ports']==1,'Wii desk offers one built-in controller seat before any controller item is connected',world()['wiiDevice'])
        # Disc goes in through the vanilla slot panel (shift-click from inventory).
        v=panel();f.picture('wall-wii-panel-empty')
        src=next(i for i in v['upgrade']['slots'][1:]if i.get('instance')==card['instance'])
        check(out,v['upgrade']['slots'][0].get('empty'),'the console slot starts empty',v['upgrade']['slots'][0])
        f.click_menu(src['index'],0,'QUICK_MOVE')
        v=f.wait(lambda v:v['upgrade']['slots'][0].get('instance')==card['instance'],'shift-click moves the disc into the console slot')
        check(out,not any(i.get('instance')==card['instance']for i in v['upgrade']['slots'][1:]),'the one disc left the inventory')
        # Screen: real item placement next to the desk, then link from the panel.
        f.close();d.command('server','expansion_screen_supply');f.inv();f.do('upgrade_click',dict(slot=inventory_slot(12),button=8,type='SWAP'));f.close();f.do('slot',dict(slot=8))
        f.do('dock_place',SCREEN_FLOOR);deadline=time.monotonic()+30
        while not world()['screens']:
            if time.monotonic()>deadline:raise TimeoutError('ordinary screen placement')
            time.sleep(.2)
        v=panel()
        if not world()['wiiDevice']['powered']:f.do('button',dict(text='开机'));f.settle(3)
        check(out,world()['wiiDevice']['powered'],'power button turns the desk on with a disc and no controller item')
        f.do('button',dict(text='连接屏幕'));f.settle(6)
        screens=world()['screens'];check(out,any(s['source']=='5, 90, 6'for s in screens),'link button connects the nearby screen to the Wii desk',screens)
        f.picture('wall-wii-panel-ready')
        f.do('button',dict(text='P1'))
        v=f.wait(lambda v:v['ready']and v['workerRunning']and v['frames']>=30 and v['expansion']['lastSession'].get('platform')=='wii','built-in seat P1 starts the real Wii worker',90)
        out['session']={k:v[k]for k in('room','epoch','workers')}
        if not v['screen'].endswith('GameScreen'):f.do('cartridge_use',dict(hand='MAIN_HAND'))
        f.wait(lambda v:v['screen'].endswith('GameScreen'),'fullscreen entry');f.do('expansion_source')
        # Audio: Dolphin pushes PCM from its own thread; the runtime must deliver it.
        f.settle(20);a=f.see()['expansion']['audio'];f.settle(40);b=f.see()['expansion']['audio']
        frames=b['coreFrame']-a['coreFrame'];per=(b['offeredFrames']-a['offeredFrames'])/max(1,frames)
        out['audio']=dict(before=a,after=b,stereoFramesPerCoreFrame=per)
        check(out,frames>0 and per>=400,'Wii worker hands roughly a frame of 48 kHz PCM to the speaker per core frame',out['audio'])
        # Guest stands by the screen as a non-controller viewer.
        d.command('server','position',dict(player=d.marker['clientNames']['guest'],x=7.5,z=9.5));g.settle(10)
        base=g.wait(lambda v:wall(v).get('relayWidth',0)>0 and wall(v)['relayFrames']>0,'nearby non-controller receives the relayed big-screen picture',60)
        bw=wall(base);check(out,bw['relayWidth']>bw['relayHeight'],'unrotated relay is landscape',bw)
        h=f.see();check(out,not wall(h)['distinct'],'unrotated: the world screen shows the plain game picture',wall(h))
        row=d.capture('guest','wall-guest-view-0');row.update(role='guest',wall=bw);out['screenshots'].append(row)
        # One rotation click.
        f.do('rotation_button');h=f.wait(lambda v:v['rotation']['presentation'].get('rotation')==1,'game window rotates a quarter turn')
        h=f.wait(lambda v:wall(v).get('distinct')and 1 in wall(v).get('matchedTurns',[]),'host world screen texture follows the 90° rotation')
        hw=wall(h);check(out,hw['wallWidth']==hw['pictureHeight']and hw['wallHeight']==hw['pictureWidth']and hw['matchedTurns']==[1]and hw['sampledColours']>1,'every sampled wall pixel lands where the clockwise game-window rotation draws it',hw)
        gv=g.wait(lambda v:wall(v).get('relayHeight',0)>wall(v).get('relayWidth',0)and wall(v)['relayFrames']>bw['relayFrames'],'relayed picture for the nearby player turns portrait')
        check(out,True,'nearby player sees the rotated picture on the big screen',wall(gv))
        row=d.capture('guest','wall-guest-view-90');row.update(role='guest',wall=wall(gv));out['screenshots'].append(row)
        # Back to the original orientation.
        for q in(2,3,0):f.do('rotation_button');f.wait(lambda v,q=q:v['rotation']['presentation'].get('rotation')==q,'rotation advances to '+str(q*90))
        h=f.wait(lambda v:not wall(v).get('distinct'),'host world screen returns to the plain picture')
        gv=g.wait(lambda v:wall(v).get('relayWidth',0)>wall(v).get('relayHeight',0),'relay returns to landscape')
        check(out,True,'rotation round trip restores both the wall and the relay',dict(host=wall(h),guest=wall(gv)))
        after=f.see();check(out,all(after[k]==out['session'][k]for k in('room','epoch','workers'))and after['ready'],'the same Wii worker ran through every rotation')
        # Save and take the disc back out through the slot.
        f.do('close_screen');panel();f.do('button',dict(text='保存并离开游戏'))
        f.wait(lambda v:not v['room']and not v['workers'],'save and leave closes the Wii worker',90)
        f.click_menu(0,0,'QUICK_MOVE')
        v=f.wait(lambda v:any(i.get('instance')==card['instance']for i in items(v)),'disc returns to the inventory after saving')
        check(out,raw(v,card['instance'])['hash']==card['hash'],'the same complete disc comes back')
        f.close()
        out['scenarioComplete']=True
    except Exception as error:out['failure']=str(error);raise
    finally:
        cleanup(d,out);out['observationTimeouts']=d.observation_timeouts;out['driverDrift']=[str(p)for p,digest in inputs.items()if sha(p)!=digest]
        if out['driverDrift']:out['scenarioComplete']=False
        path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
    return out


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--run',type=Path,required=True);parser.add_argument('--build',type=Path,required=True);args=parser.parse_args()
    result=run(Driver(args.run,args.build));print(json.dumps(dict(complete=result['scenarioComplete'],checks=len(result['checks']),images=len(result['screenshots']))))
