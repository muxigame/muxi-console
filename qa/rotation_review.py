"""Four-way NDS presentation, real touch callbacks and native Minecraft chat/debug. Never launches MC."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
from drive import Driver,sha
from polish_review import record,check
from cartridge_review import at,raw,items,cleanup
from upgrade_review import Upgrade,inventory_slot


class RotationFront(Upgrade):
    def presentation(self):return self.see()['rotation']['presentation']
    def key(self,key):
        self.do('rotation_native_key',dict(key=key,pressed=True))
        self.do('rotation_native_key',dict(key=key,pressed=False))
        return self.settle(2)
    def open_chat(self,key,character):
        self.do('rotation_native_key',dict(key=key,pressed=True))
        self.do('rotation_opening_character',dict(codepoint=ord(character)))
        self.do('rotation_native_key',dict(key=key,pressed=False))
        return self.settle(2)
    def rotate(self):
        q=self.presentation()['rotation'];self.do('rotation_button')
        return self.wait(lambda v:v['rotation']['presentation'].get('rotation')==(q+1)%4,'actual visible rotation button advances one quarter turn')
    def turn(self,q):
        for _ in range(4):
            if self.presentation()['rotation']==q:return self.see()
            self.rotate()
        raise AssertionError('Four actual rotation clicks must reach a requested quarter turn')
    def touch(self,pressed=True):
        return self.do('rotation_pointer',dict(surface='bottom',x=.2,y=.7,pressed=pressed))
    def same(self,before,label):
        after=self.see()
        check(self.out,after['ready']and after['workerRunning']and all(after[k]==before[k]for k in('room','epoch','workers'))and after['frames']>before['frames'],label,dict(before={k:before[k]for k in('room','epoch','workers','frames')},after={k:after[k]for k in('room','epoch','workers','frames')}))
        return after
    def image(self,name):
        self.picture(name,self.see())
        self.out['screenshots'][-1]['rotation']=self.see()['rotation']


def layout(out,v):
    p=v['rotation']['presentation'];q=p['rotation'];surfaces=p.get('screens',[])
    check(out,{s['id']for s in surfaces}=={'top','bottom'},'both actual NDS surfaces remain visible after rotation',dict(mode=p['mode'],rotation=q,surfaces=surfaces))
    for s in surfaces:
        x,y,w,h=s['rect'];ratio=s['nativeWidth']/s['nativeHeight'];ratio=1/ratio if q%2 else ratio
        check(out,w>0 and h>0 and abs(w/h-ratio)<.015 and x>=-1 and y>=-1 and x+w<=p['surfaceWidth']+1 and y+h<=p['surfaceHeight']+1,'quarter-turn preserves '+s['id']+' aspect and viewport bounds',dict(mode=p['mode'],rotation=q,rect=s['rect'],nativeAspect=ratio))
    if p['mode']=='screen':
        x,y,w,h=p['contentRect'];check(out,abs(w-(p['surfaceWidth']-6))<3 or abs(h-(p['surfaceHeight']-6))<3,'pure-screen view fits at least one viewport edge without stretching',p)
    check(out,p.get('showWorld')is True,'focused presentation keeps the Minecraft world visible',dict(mode=p['mode'],rotation=q))


def run(d):
    programs=d.build.get('expansionDiagnostics',{}).get('programs',{})
    if 'nds'not in programs:raise ValueError('Use the pinned original expansion diagnostic build')
    path,out=record(d,'rotation');f=RotationFront(d,out);guest=RotationFront(d,out,'guest')
    out.update(driverSha256=sha(__file__),scope='Actual authored NDS CPU, ordinary rotation/touch/input callbacks and native Minecraft chat/debug; no physical input, commercial game or general FPS claim')
    try:
        d.prepare();out['fixture']=d.command('server','expansion_prepare');f.do('dock_view',dict(hideGui=False));f.settle(10)
        f.writer();f.click_menu(f.player_menu_slot(0),0,'QUICK_MOVE');f.do('expansion_drop',dict(platform='nds'));f.do('writer_confirm');f.do('button',dict(text='上传并刻录'))
        issued=f.wait(lambda v:v['upgrade']['slots'][0].get('instance')and v['upgrade']['slots'][0].get('hash')==programs['nds']['sha256'],'normal rights-confirmed original NDS cartridge')['upgrade']['slots'][0]
        f.click_menu(0);f.click_menu(f.player_menu_slot(3));f.close()
        placed=f.wait(lambda v:any(i.get('instance')==issued['instance']for i in items(v)),'normal output pickup reaches the real player inventory');card=raw(placed,issued['instance']);out['card']=card
        check(out,card['platform']=='nds'and card['count']==1 and card['hash']==programs['nds']['sha256'],'one real blank becomes one original NDS cartridge')
        f.inv();f.do('upgrade_click',dict(slot=inventory_slot(9),button=2,type='SWAP'));f.wait(lambda v:at(v,2).get('model')==23,'ordinary inventory swap selects the empty NDS machine');f.install(card,2);f.close();f.do('slot',dict(slot=2))
        started=f.wait(lambda v:v['ready']and v['workerRunning']and v['frames']>=40 and v['expansion']['lastSession'].get('platform')=='nds','actual original NDS worker starts')
        f.do('cartridge_use',dict(hand='MAIN_HAND'));f.wait(lambda v:v['screen'].endswith('GameScreen')and v['rotation']['presentation'].get('screens'),'normal NDS model fullscreen')
        f.do('expansion_source');f.turn(0);out['initialGameScreenIdentity']=f.see()['rotation']['gameScreenIdentity'];f.image('rotation-model-0')
        for mode in('model','screen'):
            if mode=='screen':
                f.do('polish_mouse',dict(button=1,pressed=True));f.do('polish_mouse',dict(button=1,pressed=False));f.wait(lambda v:v['rotation']['presentation']['mode']=='screen','ordinary right-click switches only the presentation')
            for q in range(4):
                f.turn(q);before=f.see();layout(out,before);f.touch(True)
                pointed=f.wait(lambda v:(lambda p:p['pressed']and p['surface']=='bottom'and abs(p['x']+19660)<=2 and abs(p['y']-13107)<=2)(v['expansion']['input']['pointer']),'actual rotated touch maps back to the same original NDS bottom position')
                check(out,pointed['expansion']['input']['pointer']['valid'],'actual '+mode+' q'+str(q)+' pointer remains valid at the source position')
                consumed=f.wait(lambda v:(lambda c:c[0]>180 and c[1]>150 and c[2]<60)(v['expansion'].get('centreRGB',{}).get('bottom',[0,0,255])),'actual NDS CPU consumes '+mode+' q'+str(q)+' touch and paints the bottom screen yellow')
                check(out,True,'original NDS framebuffer confirms '+mode+' q'+str(q)+' touch consumption',consumed['expansion']['centreRGB'])
                f.same(before,'rotating/touching '+mode+' q'+str(q)+' preserves the current worker and advances the native CPU')
                if(mode,q)in(('model',1),('screen',1),('screen',3)):f.image('rotation-'+mode+'-'+str(q*90))
                f.touch(False)
                released=f.wait(lambda v:not v['expansion']['input']['pointer']['pressed']and(lambda c:c[0]<60 and c[1]<60 and c[2]>180)(v['expansion'].get('centreRGB',{}).get('bottom',[255,255,0])),'actual NDS CPU releases '+mode+' q'+str(q)+' touch and restores blue before the next angle')
                check(out,True,'original NDS framebuffer clears '+mode+' q'+str(q)+' touch before the next angle',released['expansion']['centreRGB'])
        # The same exact game screen remains the parent of native Minecraft chat.
        f.turn(3);before=f.see();hud=before['rotation'].get('hud',{});out['hudBefore']=hud
        prefs=before['rotation'].get('preferences',{});check(out,prefs.get('schema')==1 and prefs.get('key')=='nds/'+card['hash']and prefs.get('savedRotation')==3,'the actual local preference file records this game content and quarter turn',prefs)
        if not hud.get('debugVisible',False):f.key(292)
        shown=f.wait(lambda v:v['rotation'].get('hud',{}).get('debugVisible')and v['rotation']['hud'].get('debugRenderFrames',0)>hud.get('debugRenderFrames',0),'F3 toggles the real debug overlay above the console')
        f.image('rotation-minecraft-f3');f.settle(20);after=f.see();out['hudAfter']=after['rotation']['hud']
        check(out,after['rotation']['hud']['levelRenderFrames']>hud.get('levelRenderFrames',0)and after['rotation']['hud']['renderFrames']>hud.get('renderFrames',0),'the Minecraft world continues rendering behind fullscreen; opaque coverage is not a render-skip optimization',dict(before=hud,after=after['rotation']['hud']))
        f.key(292);f.wait(lambda v:not v['rotation']['hud']['debugVisible'],'F3 returns to the original off state')
        f.do('expansion_key',dict(bit=8,pressed=True));f.wait(lambda v:v['expansion']['input']['buttons']&(1<<8),'ordinary A key is genuinely held before opening chat')
        game=f.see();identity=game['rotation']['gameScreenIdentity'];check(out,game['rotation']['chatBindingKey']==84 and game['rotation']['commandBindingKey']==47,'the minimal profile uses actual default T and / bindings')
        f.open_chat(84,'t');opened=f.wait(lambda v:v['rotation']['nativeChatScreen']and v['rotation']['hud']['chatFocusedRenderFrames']>0 and v['expansion']['input']['buttons']==0,'actual Minecraft chat opens and clears the game held key')
        check(out,opened['rotation'].get('chatInput')=='','actual T press/character/release opens an empty native chat input',opened['rotation'])
        f.key(256);f.wait(lambda v:v['rotation']['gameScreen']and v['rotation']['gameScreenIdentity']==identity,'Escape returns from the native T opening-character check')
        f.open_chat(47,'/');command=f.wait(lambda v:v['rotation']['nativeChatScreen'],'actual / press/character/release opens native command input')
        check(out,command['rotation'].get('chatInput')=='/','actual command opening character leaves exactly one slash',command['rotation'])
        f.key(256);f.wait(lambda v:v['rotation']['gameScreen']and v['rotation']['gameScreenIdentity']==identity,'Escape returns from native command input')
        f.open_chat(84,'t');f.wait(lambda v:v['rotation']['nativeChatScreen']and v['rotation'].get('chatInput')=='','T opening character remains suppressed before ordinary native message typing')
        marker='MUXI-ROTATION-QA-'+d.marker['runId'][:16];f.do('rotation_chat_text',dict(text=marker));f.wait(lambda v:v['rotation'].get('chatInput')==marker,'native ChatScreen EditBox receives real typed characters');f.image('rotation-minecraft-chat')
        f.key(257);back=f.wait(lambda v:v['rotation']['gameScreen']and v['rotation']['gameScreenIdentity']==identity,'Enter sends chat and restores the same exact console screen')
        guest.wait(lambda v:any(marker in s for s in v['rotation']['chatMarkers']),'second actual client receives the native Minecraft chat marker')
        check(out,all(back[k]==game[k]for k in('room','epoch','workers'))and back['rotation']['presentation']['rotation']==3,'chat sends without restarting the game or changing its orientation')
        f.key(back['rotation']['chatBindingKey']);f.wait(lambda v:v['rotation']['nativeChatScreen'],'native chat opens a second time');f.key(256);f.wait(lambda v:v['rotation']['gameScreen']and v['rotation']['gameScreenIdentity']==identity,'Escape cancels native chat and returns to the original console')
        f.do('expansion_key',dict(bit=8,pressed=False));f.key(256);f.wait(lambda v:not v['screen']and v['room']==game['room'],'Escape exits fullscreen while keeping the game session');f.do('cartridge_use',dict(hand='MAIN_HAND'))
        reopened=f.wait(lambda v:v['rotation']['gameScreen']and v['rotation']['presentation']['rotation']==3,'reopening fullscreen restores the remembered game orientation')
        check(out,reopened['workers']==game['workers']and reopened['epoch']==game['epoch'],'orientation persistence does not restart the native game')
        f.same(started,'the complete rotation/chat/debug path retains the original NDS worker')
        ended=f.leave('rotation NDS final save');f.do('slot',dict(slot=8));f.close();world=d.command('server','expansion_observe');out['worldEvidence']=world
        saved=[s for s in world['saved']if s['machine']==started['machine']['machine']and s['content']==card['hash']]
        check(out,len(saved)==1 and saved[0]['hash']==ended['savedStateHash']==saved[0]['diskSha256']and saved[0]['size']==saved[0]['diskBytes'],'normal exit commits the original machine/game checkpoint to actual world bytes',saved)
        out['scenarioComplete']=True
    except Exception as failure:out['failure']=str(failure);raise
    finally:
        cleanup(d,out);out['observationTimeouts']=d.observation_timeouts;path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
    return out


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--run',required=True,type=Path);p.add_argument('--build',required=True,type=Path);a=p.parse_args();v=run(Driver(a.run,a.build));print(json.dumps(dict(complete=v['scenarioComplete'],checks=len(v['checks']),images=len(v['screenshots']))))
