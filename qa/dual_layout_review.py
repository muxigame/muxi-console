"""Native independent NDS screen arrangement, real touch and Minecraft overlays; never launches MC."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
from PIL import Image
from drive import Driver,sha
from polish_review import record,check
from cartridge_review import at,raw,items,cleanup
from upgrade_review import inventory_slot
from rotation_review import RotationFront,layout


def colour(v,name='bottom',touched=False):
    r,g,b=v['expansion'].get('centreRGB',{}).get(name,[0,0,0])
    return r>180 and g>150 and b<60 if touched else r<60 and g<60 and b>180


class DualFront(RotationFront):
    def pure(self):
        if self.presentation()['mode']!='screen':self.toggle_mode('screen')
        return self.see()
    def toggle_mode(self,mode):
        self.do('polish_mouse',dict(button=1,pressed=True));self.do('polish_mouse',dict(button=1,pressed=False))
        return self.wait(lambda v:v['rotation']['presentation']['mode']==mode,'ordinary right-click enters '+mode+' presentation')
    def arrange(self,value):
        self.pure()
        if self.presentation()['arrangement']!=value:self.do('rotation_layout_button')
        return self.wait(lambda v:v['rotation']['presentation'].get('arrangement')==value,'actual visible layout button selects '+value)
    def picture_sources(self,name,touched=False):
        self.image(name);row=self.out['screenshots'][-1];p=row['rotation']['presentation'];samples={}
        with Image.open(row['path'])as original:
            image=original.convert('RGB')
            for surface in p['screens']:
                x,y,w,h=surface['rect'];point=(round((x+w/2)*image.width/p['surfaceWidth']),round((y+h/2)*image.height/p['surfaceHeight']));samples[surface['id']]=image.getpixel(point)
        red=lambda c:c[0]>180 and c[1]<60 and c[2]<60
        bottom=samples.get('bottom',[0,0,0]);ok=bottom[0]>180 and bottom[1]>150 and bottom[2]<60 if touched else bottom[0]<60 and bottom[1]<60 and bottom[2]>180
        check(self.out,red(samples.get('top',[0,0,0]))and ok,'actual Minecraft PNG shows independent top red and bottom '+('yellow'if touched else'blue')+' in '+name,samples)
        row['sourceCentrePixels']=samples


def independent(out,v):
    layout(out,v);p=v['rotation']['presentation'];surfaces={s['id']:s for s in p['screens']}
    top=surfaces['top']['virtualRect'];bottom=surfaces['bottom']['virtualRect']
    check(out,p['mode']=='screen'and p['arrangement']=='side_by_side'and top[0]+top[2]<bottom[0]and abs(top[1]-bottom[1])<1 and abs(top[3]-bottom[3])<1,'top stays left and bottom stays right on the unrotated layout canvas',p)


def run(d):
    programs=d.build.get('expansionDiagnostics',{}).get('programs',{})
    if 'nds'not in programs:raise ValueError('Use the pinned original expansion diagnostic build')
    path,out=record(d,'dual-layout');f=DualFront(d,out);guest=DualFront(d,out,'guest')
    out.update(driverSha256=sha(__file__),scope='Original authored NDS CPU, independent source rectangles, ordinary GUI touch and native MC chat/debug; no physical-input, commercial-game or FPS claim')
    try:
        d.prepare();out['fixture']=d.command('server','expansion_prepare');f.do('dock_view',dict(hideGui=False));f.settle(10)
        f.writer();f.click_menu(f.player_menu_slot(0),0,'QUICK_MOVE');f.do('expansion_drop',dict(platform='nds'));f.do('writer_confirm');f.do('button',dict(text='上传并刻录'))
        issued=f.wait(lambda v:v['upgrade']['slots'][0].get('instance')and v['upgrade']['slots'][0].get('hash')==programs['nds']['sha256'],'normal rights-confirmed original NDS cartridge')['upgrade']['slots'][0]
        f.click_menu(0);f.click_menu(f.player_menu_slot(3));f.close()
        placed=f.wait(lambda v:any(i.get('instance')==issued['instance']for i in items(v)),'normal output pickup reaches the player inventory');card=raw(placed,issued['instance']);out['card']=card
        check(out,card['platform']=='nds'and card['count']==1 and card['hash']==programs['nds']['sha256'],'one real blank becomes one original NDS cartridge')
        f.inv();f.do('upgrade_click',dict(slot=inventory_slot(9),button=2,type='SWAP'));f.wait(lambda v:at(v,2).get('model')==23,'ordinary inventory swap selects the empty NDS machine');f.install(card,2);f.close();f.do('slot',dict(slot=2))
        started=f.wait(lambda v:v['ready']and v['workerRunning']and v['frames']>=40 and v['expansion']['lastSession'].get('platform')=='nds','actual original NDS worker starts')
        f.do('cartridge_use',dict(hand='MAIN_HAND'));f.wait(lambda v:v['screen'].endswith('GameScreen')and v['rotation']['presentation'].get('screens'),'normal NDS fullscreen entry');f.do('expansion_source');f.turn(0);f.pure();f.arrange('stacked')
        stacked=f.see();layout(out,stacked);f.wait(lambda v:colour(v),'initial original NDS bottom is blue');f.picture_sources('dual-layout-stacked-0')
        f.arrange('side_by_side');side=f.see();independent(out,side)
        check(out,side['rotation']['presentation']['rotation']==0,'rearranging the two screens does not rotate their contents')
        f.picture_sources('dual-layout-side-0')
        # Wrong screen and the real gap are never bottom-screen touch coordinates.
        for target in('top','gap'):
            f.do('rotation_untouchable',dict(target=target,pressed=True));v=f.wait(lambda v:not v['expansion']['input']['pointer']['pressed']and colour(v),'ordinary '+target+' click stays outside the touch surface')
            check(out,not v['expansion']['input']['pointer']['valid'],'ordinary '+target+' click never becomes a valid bottom-screen pointer',v['expansion']['input']['pointer'])
            f.do('rotation_untouchable',dict(target=target,pressed=False))
        for q in range(4):
            f.turn(q);before=f.see();independent(out,before);f.touch(True)
            hit=f.wait(lambda v:(lambda p:p['pressed']and p['valid']and p['surface']=='bottom'and abs(p['x']+19660)<=2 and abs(p['y']-13107)<=2)(v['expansion']['input']['pointer']),'actual side-by-side q'+str(q)+' inverse touch reaches original NDS bottom (.2,.7)')
            f.wait(lambda v:colour(v,touched=True),'actual NDS CPU paints side-by-side q'+str(q)+' touch yellow')
            check(out,True,'actual core consumes independent side-by-side q'+str(q)+' touch',hit['expansion']['input']['pointer'])
            if q==1:f.picture_sources('dual-layout-side-90-touch',touched=True)
            f.touch(False);released=f.wait(lambda v:not v['expansion']['input']['pointer']['pressed']and colour(v),'actual NDS CPU releases side-by-side q'+str(q)+' and restores blue')
            check(out,True,'native bottom returns blue before the next direction',released['expansion']['centreRGB']);f.same(before,'side layout rotation q'+str(q)+' retains the current native worker')
        f.turn(0);f.toggle_mode('model');model=f.see();p=model['rotation']['presentation']
        check(out,p['arrangement']=='stacked'and p['preferredArrangement']=='side_by_side'and p['rotation']==0,'returning to the physical model keeps real device screens in their original slots and retains the pure-screen preference',p)
        f.picture_sources('dual-layout-physical-model');f.toggle_mode('screen');f.wait(lambda v:v['rotation']['presentation']['arrangement']=='side_by_side','returning to pure mode restores independent side layout')
        f.arrange('stacked');restored=f.see();layout(out,restored)
        check(out,restored['rotation']['presentation']['rotation']==0,'switching back to stacked layout leaves rotation independent')
        f.arrange('side_by_side');f.turn(3);prefs=f.see()['rotation']['preferences']
        check(out,prefs.get('schema')==1 and prefs.get('key')=='nds/'+card['hash']and prefs.get('savedRotation')==3 and prefs.get('savedArrangement')=='side_by_side','the actual local config retains rotation and layout independently',prefs)
        game=f.see();hud=game['rotation']['hud'];f.key(292);f.wait(lambda v:v['rotation']['hud']['debugVisible']and v['rotation']['hud']['debugRenderFrames']>hud['debugRenderFrames'],'real F3 information remains above rearranged screens');f.key(292);f.wait(lambda v:not v['rotation']['hud']['debugVisible'],'F3 returns to its original state')
        f.open_chat(84,'t');f.wait(lambda v:v['rotation']['nativeChatScreen']and v['rotation'].get('chatInput')=='','native T chat opens without its opening character')
        marker='MUXI-ROTATION-QA-'+d.marker['runId'][:16];f.do('rotation_chat_text',dict(text=marker));f.wait(lambda v:v['rotation'].get('chatInput')==marker,'ordinary marker text reaches native chat');f.image('dual-layout-minecraft-chat');f.key(257)
        back=f.wait(lambda v:v['rotation']['gameScreen']and v['rotation']['gameScreenIdentity']==game['rotation']['gameScreenIdentity'],'native Enter returns to the same layout screen');guest.wait(lambda v:any(marker in s for s in v['rotation']['chatMarkers']),'second actual client receives native chat')
        check(out,back['rotation']['presentation']['arrangement']=='side_by_side'and back['rotation']['presentation']['rotation']==3,'chat returns without changing the independent layout or rotation')
        f.key(256);f.wait(lambda v:not v['screen']and v['room']==game['room'],'ordinary Escape exits presentation without closing the game');f.do('cartridge_use',dict(hand='MAIN_HAND'));f.wait(lambda v:v['rotation']['gameScreen'],'normal fullscreen reopening');f.pure();reopened=f.see()
        check(out,reopened['rotation']['presentation']['rotation']==3 and reopened['rotation']['presentation']['arrangement']=='side_by_side','new fullscreen presentation restores both stored game display choices')
        f.same(started,'the complete rearrangement path keeps the original NDS worker and advances frames')
        ended=f.leave('dual-layout NDS final save');f.do('slot',dict(slot=8));f.close();world=d.command('server','expansion_observe');out['worldEvidence']=world
        saved=[s for s in world['saved']if s['machine']==started['machine']['machine']and s['content']==card['hash']]
        check(out,len(saved)==1 and saved[0]['hash']==ended['savedStateHash']==saved[0]['diskSha256']and saved[0]['size']==saved[0]['diskBytes'],'normal final exit commits the original machine/game checkpoint to actual world bytes',saved)
        out['scenarioComplete']=True
    except Exception as failure:out['failure']=str(failure);raise
    finally:
        cleanup(d,out);out['observationTimeouts']=d.observation_timeouts;path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
    return out


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--run',required=True,type=Path);p.add_argument('--build',required=True,type=Path);a=p.parse_args();v=run(Driver(a.run,a.build));print(json.dumps(dict(complete=v['scenarioComplete'],checks=len(v['checks']),images=len(v['screenshots']))))
