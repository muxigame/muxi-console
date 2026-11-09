"""Real JEI U/R/search and normal component-preserving crafting on an already-owned run. Never launches MC."""
from __future__ import annotations
import argparse
import json
from pathlib import Path
from drive import Driver, sha, read_json
from polish_review import record, check
from upgrade_review import Upgrade, inventory_slot
from link_review import LinkFront
from cartridge_review import cleanup, at, cursor, same_card, inserted


def ui(v): return v['recipe']['ui']


class Recipes(LinkFront):
    def key(self, key):
        self.do('recipe_key', dict(key=key, pressed=True))
        self.do('recipe_key', dict(key=key, pressed=False))

    def hover(self, slot):
        self.do('upgrade_hover', dict(slot=inventory_slot(slot)))
        return self.wait(lambda v: v['upgrade'].get('hoveredSlot') == inventory_slot(slot), 'actual rendered source slot hovered')

    def lookup(self, slot, key):
        self.inv(); self.hover(slot); self.key(key)
        return self.wait(lambda v: ui(v).get('recipesOpen') and ui(v).get('focusedRecipes'), 'JEI opens through actual U/R keyboard callback')

    def back(self):
        self.key(256)
        return self.wait(lambda v: v['screen'].endswith('InventoryScreen'), 'JEI restores its actual inventory parent')

    def snap(self, name):
        self.settle(4); before = self.see(); row = self.d.capture(self.role, name); after = self.see()
        row.update(role=self.role, screenBefore=before['screen'], screenAfter=after['screen'], jeiBefore=ui(before), jeiAfter=ui(after))
        self.out['screenshots'].append(row)


def finish_gameplay(f,slots):
    d,out=f.d,f.out
    v=f.lookup(2,82); recipes=[r for r in ui(v)['focusedRecipes'] if r.get('output',{}).get('model')==5 and r['id']=='muxi_console:jei/hardware/5']
    check(out, len(recipes)==1 and any(any(a['item']=='minecraft:purple_dye' for a in ingredient) for ingredient in recipes[0]['ingredients']), 'actual R on the real GBA shows its purple-dye recipe', recipes)
    f.snap('recipe-r-gba-purple'); f.back(); f.close()
    out['cards']={'gb':f.bare_burn('gb',d.build['gbDiagnosticRom'],7,True),'gba':f.bare_burn('gba',d.build['gbaDiagnosticRom'],6,True)}
    cards=out['cards']; f.inv(); f.install(cards['gb'],2); f.close()
    _,saved=f.run_card(2,'recipe-gb-original'); core=f.player(2)
    check(out, bool(core.get('machine')), 'ordinary first game allocated the original physical machine UUID')
    f.craft(2,slots['lime'],4); restored,_=f.run_card(2,'recipe-gb-recolored')
    check(out, f.player(2)['machine']==core['machine'] and restored['restoredStateHash']==saved['savedStateHash'], 'real compatible reshape retains the whole inserted card, machine UUID and exact earlier checkpoint')
    f.craft(2,slots['purple'],5); f.inv(); f.eject(2,cards['gb'],7); f.install(cards['gba'],2); f.close()
    f.do('slot',dict(slot=2)); started=f.wait(lambda v:v['ready'] and v['workerRunning'] and v['frames']>=60 and v['picture'].get('left',[0,0,0])[2]>150, 'split runtime starts the actual mGBA blue diagnostic')
    f.do('cartridge_use',dict(hand='MAIN_HAND')); f.wait(lambda v:v['screen'].endswith('GameScreen'), 'normal GBA fullscreen entry')
    f.do('upgrade_callback_source'); f.do('upgrade_key',dict(key=74,pressed=True)); f.settle(8); f.do('upgrade_key',dict(key=74,pressed=False))
    changed=f.wait(lambda v:v['picture'].get('left',[0,0,0])[0]>150 and v['picture']['left'][2]<50, 'actual mapped GBA A turns the true core texture red')
    check(out, changed['workers']==started['workers'] and changed['room']==started['room'], 'actual input and RGB response belong to the same live mGBA worker')
    f.picture('recipe-split-gba-input',started); f.leave('split GBA gameplay'); f.do('slot',dict(slot=8)); f.close()
    before=f.player(2); f.craft(2,slots['white'],3,accept=False)
    check(out, f.player(2)['nbt']==before['nbt'], 'incompatible reshape rejects without losing inserted GBA components or machine')
    out['worldEvidence']=d.command('server','upgrade_observe'); saves=[s for s in out['worldEvidence']['saved'] if s['machine']==core['machine']]
    check(out, {s['content'] for s in saves}=={c['hash'] for c in cards.values()} and all(s['hash']==s['diskSha256'] and s['size']==s['diskBytes'] for s in saves), 'both game namespaces remain under the same real machine with matching world checkpoint bytes', saves)
    final=f.see(); physical=[]
    for item in final['upgrade']['inventory']:
        if item.get('instance'): physical.append(item)
        if item.get('inserted',{}).get('instance'): physical.append(item['inserted'])
    check(out, cursor(final)['empty'] and f.player(2)['machine']==core['machine'] and all(sum(same_card(i,c) for i in physical)==1 for c in cards.values()), 'two normally issued full cards remain unique and machine identity is unchanged')
    out['scenarioComplete']=True


def run(d):
    path, out = record(d, 'recipe'); f = Recipes(d, out)
    out.update(qaBuildSha256=sha(d.build_path), runtime=d.build['runtimeArtifact'], jei=d.build['jeiArtifact'], driverSha256=sha(__file__))
    try:
        d.prepare(); out['fixture'] = d.command('server', 'upgrade_prepare'); slots = out['fixture']['fixtures']
        f.do('dock_view', dict(hideGui=False)); f.settle(10)
        initial = f.wait(lambda v: ui(v).get('runtimeAvailable') and v['gameMode']=='survival', 'JEI runtime, survival player and actual world are ready')
        check(out, initial['recipe']['muxi_console_runtime']['loaded'] and initial['recipe']['jei']['loaded'], 'client loads independent runtime and optional real JEI')
        plan = read_json(d.build['modelReviewPlan']['path'])['stations']
        families = {True: {r['cmd'] for r in plan if r['form_factor'] in ('handheld','viewer')}, False: {r['cmd'] for r in plan if r['form_factor'] not in ('handheld','viewer')}}
        source = f.player(2)
        check(out, source['model']==0 and 'Original named upgrade core' in source['nbt'], 'U targets the original named, component-bearing core rather than an anonymous example')
        for handheld, slot, name in ((True,2,'portable'),(False,slots['wiiCore'],'desktop')):
            v = f.lookup(slot,85); recipes = [r for r in ui(v)['focusedRecipes'] if r['id'].startswith('muxi_console:jei/hardware/')]
            models = {r['output']['model'] for r in recipes}
            check(out, models==families[handheld] and len(recipes)==len(models), 'actual U GUI lists every '+name+' variant once', dict(models=sorted(models), count=len(recipes)))
            if handheld: check(out, all(r['firstIngredientAcceptsRealSlot2'] for r in recipes), 'every portable display predicate accepts the actual named source components')
            f.snap('recipe-u-'+name); f.back()
        check(out, len(families[True])==12 and len(families[False])==13, 'two real core lookups cover the existing twenty-five models')
        for text, model in (('gba',5),('nds',23),('3ds',24),('wii',25)):
            f.do('recipe_search', dict(text=text))
            v=f.wait(lambda v: ui(v)['filter']==text and any(r['model']==model for r in ui(v)['filteredHardware']), 'actual JEI ingredient filter returns '+text)
            check(out, any(r['model']==model for r in ui(v)['visibleHardware']), 'the searched '+text+' variant is present on the visible overlay page', ui(v)['visibleHardware'])
            if text=='gba': f.snap('recipe-search-gba')
        f.do('recipe_search', dict(text='')); f.close()
        # Attempt the real JEI plus widget, without bypassing its transfer handler or crafting packets.
        f.lookup(2,85)
        for attempt in range(12):
            if any(r.get('output',{}).get('model')==5 for r in ui(f.see()).get('visibleRecipes',[])): break
            f.do('recipe_next'); f.settle(2)
        else: raise AssertionError('GBA display never appeared on a normal JEI page')
        f.do('recipe_transfer', dict(model=5)); f.settle(6)
        v=f.see(); out['plusAttempt']={k:ui(v).get(k) for k in ('lastInput','recipesOpen','visibleRecipes')}
        if ui(v)['recipesOpen']: f.back()
        v=f.see(); transferred=v['upgrade']['slots'][0].get('model')==5
        out['plusAttempt'].update(actualCraftingPreview=transferred, slots=v['upgrade']['slots'][:5])
        if transferred and v['upgrade']['slots'][0]['withoutModel']==source['withoutModel']:
            check(out, v['upgrade']['slots'][0]['withoutModel']==source['withoutModel'], 'JEI plus moved the real named core and purple dye without replacing its components')
            f.click_menu(0); f.click_menu(inventory_slot(2)); f.close()
            check(out, f.player(2)['model']==5 and f.player(2)['withoutModel']==source['withoutModel'], 'normal result pickup after JEI transfer preserves the complete named source')
        elif transferred:
            grid=v['upgrade']['slots'];empty=[slot for slot in (slots['ndsCore'],slots['n3dsCore']) if v['upgrade']['inventory'][slot]['empty']]
            check(out, len(empty)==1 and grid[0]['withoutModel']==grid[1]['withoutModel'] and grid[1]['item']=='muxi_console:handheld' and grid[1]['model']==0 and grid[2]['item']=='minecraft:purple_dye' and f.player(2)['nbt']==source['nbt'], 'JEI plus legally selects another actual core and preserves that source; named source remains untouched')
            f.click_menu(1);f.click_menu(inventory_slot(empty[0]));f.click_menu(2);f.click_menu(inventory_slot(slots['purple']));f.close();f.craft(2,slots['purple'],5)
        else:
            # Unsupported JEI transfer is recorded separately; manual crafting is not labeled plus success.
            check(out, all(v['upgrade']['slots'][i]['empty'] for i in range(1,5)) and v['upgrade']['cursor']['empty'] and f.player(2)['nbt']==source['nbt'], 'unsuccessful plus attempt leaves source, cursor and crafting grid intact')
            f.close(); f.craft(2,slots['purple'],5)
        finish_gameplay(f,slots)
    except Exception as error:
        out['failure']=str(error); raise
    finally:
        cleanup(d,out); out['observationTimeouts']=d.observation_timeouts
        path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
    return out


def no_jei(d):
    path,out=record(d,'recipe-without-jei')
    out.update(qaBuildSha256=sha(d.build_path), runtime=d.build['runtimeArtifact'], driverSha256=sha(__file__), scope='One real client connected; main-only server, no JEI on either side. No authored ROM or new fixture.')
    try:
        v=d.command('host','observe'); server=d.command('server','observe')
        check(out, v['connected'] and v['recipe']['muxi_console']['loaded'] and v['recipe']['muxi_console_runtime']['loaded'] and not v['recipe']['jei']['loaded'], 'real client connects with split runtime while JEI is absent', v['recipe'])
        check(out, not v['room'] and not v['workers'] and v['forcedWorkerExits']==v['failedWorkerExits']==0, 'optional integration boot runs no emulator and reports no failed native worker')
        out['server']=server; out['scenarioComplete']=True
    except Exception as error: out['failure']=str(error); raise
    finally: path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
    return out


def continue_plus(d, expected):
    source=d.channel/'recipe-result.json'; original=read_json(source)
    observed=d.build_path.parent/'recipe-plus-failure-observation.json'; witness=read_json(observed)
    if expected!='aee9f0c1fae11a80ce25c829096b9d3c9879d0df036e2aaa6f8924631badecfa' or sha(source)!=expected or sha(observed)!='f329e8f25aa3db1729a8d319de219f6b42b0bb33cc1a1398297f8bbee5cb4783':
        raise ValueError('Only the exact retained alternate-core transfer partial may continue')
    if original['runId']!=d.marker['runId'] or original['productSha256']!=d.build['product']['sha256'] or original['qaBuildSha256']!=sha(d.build_path) or original['scenarioComplete'] or original.get('cleanupFailure') or len(original['checks'])!=11 or not all(c['passed'] for c in original['checks'][:10]) or original['checks'][-1]['passed'] or len(original['screenshots'])!=3 or original.get('failure')!='JEI plus moved the real named core and purple dye without replacing its components':
        raise ValueError('Original actual failure scope differs')
    if witness['runId']!=d.marker['runId'] or witness['pid']!=d.marker['processes']['host']['pid'] or witness['productSha256']!=d.build['product']['sha256'] or witness['qaSha256']!=d.build['artifacts']['client']['sha256'] or not witness['ok']:
        raise ValueError('Actual original post-cleanup observation identity differs')
    path,out=record(d,'recipe-continuation'); f=Recipes(d,out); slots=original['fixture']['fixtures']
    out.update(source=dict(path=str(source),sha256=expected,passed=10,images=3,scenarioComplete=False), originalObservation=dict(path=str(observed),sha256=sha(observed)),fixture=original['fixture'],qaBuildSha256=sha(d.build_path),runtime=d.build['runtimeArtifact'],jei=d.build['jeiArtifact'],driverSha256=sha(__file__),scope='Keep original false; its actual JEI plus transferred another valid anonymous core. Restore only those exact grid inputs with normal clicks, then use the untouched original named core. No U/search replay, no previous burn/game action existed.')
    try:
        current=f.see(); old=witness['result']; v=current['upgrade']; prev=old['upgrade']
        check(out, not current['room'] and not current['workers'] and not d.command('guest','observe')['workers'] and current['screen'].endswith('InventoryScreen') and v['cursor']['empty'] and all(v['slots'][i].get('nbt')==prev['slots'][i].get('nbt') for i in range(5)) and v['inventory'][2]['nbt']==prev['inventory'][2]['nbt'], 'adopt the exact original live grid, complete named source and empty native lifecycle')
        check(out, original['plusAttempt']['actualCraftingPreview'] and v['slots'][0]['withoutModel']==v['slots'][1]['withoutModel'] and v['slots'][1]['item']=='muxi_console:handheld' and v['slots'][1]['model']==0 and v['slots'][2]['item']=='minecraft:purple_dye', 'actual JEI plus used another legal anonymous core without dropping its components', original['plusAttempt'])
        targets=[slots['ndsCore'],slots['n3dsCore']]; empty=[slot for slot in targets if v['inventory'][slot]['empty']]
        check(out,len(empty)==1,'one actual empty anonymous-core fixture slot is the precise return destination',empty)
        f.click_menu(1);f.click_menu(inventory_slot(empty[0]));f.click_menu(2);f.click_menu(inventory_slot(slots['purple']))
        v=f.view();check(out,all(v['slots'][i]['empty'] for i in range(5)) and v['cursor']['empty'] and v['inventory'][2]['nbt']==prev['inventory'][2]['nbt'] and v['inventory'][empty[0]]['nbt']==prev['slots'][1]['nbt'],'ordinary clicks return the actual anonymous core and dye; original named core stays byte-identical')
        f.close();f.craft(2,slots['purple'],5);finish_gameplay(f,slots)
    except Exception as error:out['failure']=str(error);raise
    finally:
        cleanup(d,out);out['observationTimeouts']=d.observation_timeouts;path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
    return out


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run',type=Path,required=True); parser.add_argument('--build',type=Path,required=True)
    parser.add_argument('--phase',choices=('jei','no-jei'),required=True); parser.add_argument('--execute',action='store_true')
    parser.add_argument('--driver-sha256',help='Optional exact frozen source identity; mismatches refuse all commands')
    parser.add_argument('--continue-source-sha256',help='Only the exact preserved alternate-core JEI transfer partial')
    args=parser.parse_args()
    if args.driver_sha256 and sha(__file__)!=args.driver_sha256: raise ValueError('Frozen driver source differs')
    d=Driver(args.run,args.build,allow_single=args.phase=='no-jei',expect_jei=args.phase=='jei')
    if not d.build.get('recipeReview'): raise ValueError('Explicit recipe-review QA is required')
    if not args.execute: print(json.dumps(dict(verified=True,runId=d.marker['runId'],phase=args.phase,driverSha256=sha(__file__)))); raise SystemExit(0)
    result=continue_plus(d,args.continue_source_sha256) if args.continue_source_sha256 else run(d) if args.phase=='jei' else no_jei(d)
    print(json.dumps(dict(complete=result['scenarioComplete'],checks=len(result['checks']),images=len(result['screenshots']))))
