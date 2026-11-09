"""Real dock slot, hand-to-dock save and shared-container preservation. Never launches Minecraft."""
from __future__ import annotations

import argparse
import json
from pathlib import Path
from drive import Driver, sha
from polish_review import record, check
from cartridge_review import cleanup
from link_review import LinkFront
from upgrade_review import inventory_slot


def dockslot(slot):
    if 0 <= slot <= 8:
        return slot + 28
    if 9 <= slot <= 35:
        return slot - 8
    raise ValueError('Dock exposes the normal player inventory and hotbar only')


class DockFront(LinkFront):
    def open_dock(self):
        self.do('use_block', dict(x=12, y=90, z=6))
        return self.wait(lambda v: v['screen'].endswith('DockScreen') and v['upgrade']['menu'].endswith('DockMenu'), 'actual server-opened dock container')

    def cell(self):
        return self.view()['slots'][0]

    def dock_context(self):
        return self.view()['dock']

    def equals_machine(self, expected):
        return self.cell().get('nbt') == expected['nbt'] and self.cell().get('count') == 1


def run(d):
    path, out = record(d, 'dock-container')
    out.update(driverSha256=sha(__file__), qaBuildSha256=sha(d.build_path), runtime=d.build.get('runtimeArtifact'),
               scope='Native server menu packets, authored GBA core, two real clients and complete item components; no physical keyboard/gamepad claim')
    f, guest = DockFront(d, out), DockFront(d, out, 'guest')
    try:
        d.prepare()
        out['fixture'] = d.command('server', 'upgrade_prepare')
        slots = out['fixture']['fixtures']
        d.command('server', 'dockslot_prepare')
        f.do('dock_view', dict(hideGui=False)); guest.do('dock_view', dict(hideGui=False))
        f.wait(lambda v: v['gameMode'] == 'survival', 'actual survival inventory')
        d.command('server', 'position', dict(player=d.marker['clientNames']['host'], x=12.5, z=9.5))
        f.do('slot', dict(slot=5)); f.do('dock_place', dict(x=12, y=89, z=6))
        f.settle(5)
        placed = d.command('server', 'dockslot_observe')
        check(out, placed.get('device', {}).get('kind') == 'dock' and not placed['device']['docked'], 'normal item placement creates the empty dock')
        # Existing helpers issue an original ROM only through the rights-confirmed writer and real slot.
        f.do('slot', dict(slot=8)); f.craft(2, slots['purple'], 5)
        card = f.bare_burn('gba', d.build['gbaDiagnosticRom'], 7, True)
        out['card'] = card
        f.inv(); f.install(card, 2); f.close(); f.do('slot', dict(slot=2))
        started = f.wait(lambda v: v['ready'] and v['workerRunning'] and v['frames'] >= 60, 'holding the real loaded handheld automatically starts GBA')
        f.do('cartridge_use', dict(hand='MAIN_HAND'))
        f.wait(lambda v: v['screen'].endswith('GameScreen'), 'normal handheld fullscreen')
        f.do('upgrade_callback_source'); f.do('upgrade_key', dict(key=74, pressed=True)); f.settle(8); f.do('upgrade_key', dict(key=74, pressed=False))
        f.wait(lambda v: v['picture'].get('left', [0, 0, 0])[0] > 150 and v['picture']['left'][2] < 50, 'real mapped A changes the authored GBA picture to red')
        f.do('dock_esc')
        d.command('server', 'position', dict(player=d.marker['clientNames']['host'], x=12.5, z=9.5))
        f.open_dock()
        idle = f.wait(lambda v: not v['room'] and not v['workers'] and v['screen'].endswith('DockScreen'), 'opening the dock normally saves and closes the still-held machine')
        core = f.player(2); out['machine'] = core
        first_save = idle['savedStateHash']; out['firstSavedStateHash'] = first_save
        check(out, core.get('machine') and core.get('inserted', {}).get('nbt') == card['nbt'] and first_save, 'normal game allocated a machine identity and saved with the original complete inserted card')
        check(out, idle['normalWorkerExits'] == started['normalWorkerExits'] + 1 and idle['forcedWorkerExits'] == idle['failedWorkerExits'] == 0, 'opening the slot UI closes its old native worker naturally')
        f.settle(25)
        check(out, not f.see()['workers'] and f.cell()['empty'] and f.player(2)['nbt'] == core['nbt'], 'still-selected handheld does not restart while the empty dock menu is open')
        f.picture('dock-container-empty')
        # Wrong item rejection uses actual pickup packets; neither fixture nor cursor is rewritten.
        f.click_menu(dockslot(3)); stone = f.view()['cursor']
        f.click_menu(0, 1)
        check(out, f.cell()['empty'] and f.view()['cursor']['nbt'] == stone['nbt'], 'dock refuses an ordinary stone stack without loss or split')
        f.click_menu(dockslot(3))
        f.click_menu(dockslot(2), 0, 'QUICK_MOVE')
        f.wait(lambda v: v['upgrade']['slots'][0].get('nbt') == core['nbt'] and v['upgrade']['inventory'][2]['empty'], 'shift-click installs the original whole handheld directly from inventory')
        # Right click must pick up the whole machine, not invoke its secondary internal-card action.
        f.click_menu(0, 1)
        check(out, f.cell()['empty'] and f.view()['cursor']['nbt'] == core['nbt'] and f.view()['cursor']['inserted']['nbt'] == card['nbt'], 'right-click takes the complete machine and keeps its inserted cartridge intact')
        f.click_menu(0, 1)
        check(out, f.equals_machine(core) and f.view()['cursor']['empty'], 'right-click puts the complete machine back in its physical slot')
        d.command('server', 'position', dict(player=d.marker['clientNames']['guest'], x=13.5, z=9.5))
        guest.do('slot', dict(slot=8)); guest.open_dock()
        guest.wait(lambda v: v['upgrade']['slots'][0].get('nbt') == core['nbt'], 'second actual player observes the same shared dock slot')
        # A hotbar number key swaps the whole machine through the ordinary container path.
        f.click_menu(0, 2, 'SWAP')
        f.wait(lambda v: v['upgrade']['slots'][0]['empty'] and v['upgrade']['inventory'][2].get('nbt') == core['nbt'], 'number-key swap retrieves the complete docked machine')
        guest.wait(lambda v: v['upgrade']['slots'][0]['empty'], 'shared slot removal reaches the other real menu')
        f.click_menu(0, 2, 'SWAP')
        guest.wait(lambda v: v['upgrade']['slots'][0].get('nbt') == core['nbt'], 'number-key installation synchronizes back to the second menu')
        f.click_menu(0, 1)
        guest.wait(lambda v: v['upgrade']['slots'][0]['empty'], 'one player holding the machine empties every shared view')
        guest.click_menu(0, 1)
        check(out, guest.view()['cursor']['empty'] and f.view()['cursor']['nbt'] == core['nbt'], 'competing pickup of the now-empty slot cannot duplicate a whole machine')
        f.click_menu(0, 1)
        f.picture('dock-container-installed')
        f.close(); guest.close()
        persisted = d.command('server', 'dockslot_observe')
        check(out, persisted['machineNbt'] == core['nbt'] and persisted['nbtRoundTrip'], 'closing every viewer keeps the entire original machine in the block and its serialized data')
        out['closedMenuEvidence'] = persisted
        f.open_dock(); guest.open_dock()
        f.wait(lambda v: v['upgrade']['slots'][0].get('nbt') == core['nbt'], 'reopening reads the same persisted machine rather than a temporary menu copy')
        texts = [w['text'] for w in f.view()['widgets']]
        check(out, not any('取出' in text for text in texts), 'native dock UI has no separate eject-machine button', texts)
        f.button('开机'); f.wait(lambda v: v['upgrade']['dock']['powered'], 'native power widget reflects authoritative dock state')
        f.button('P1')
        docked = f.wait(lambda v: v['ready'] and v['workerRunning'] and v['frames'] >= 60 and v['restoredStateHash'] == first_save, 'the dock restores the exact handheld machine checkpoint')
        check(out, docked['picture'].get('left', [0, 0, 0])[0] > 150 and docked['picture']['left'][2] < 50, 'docked machine resumes the red GBA state from handheld play')
        f.picture('dock-container-resumed-game', docked)
        guest.wait(lambda v: v['upgrade']['dock'].get('locked', False), 'other viewer receives the running/save slot lock')
        guest.click_menu(0, 1); guest.click_menu(0, 0, 'QUICK_MOVE')
        check(out, guest.equals_machine(core) and guest.view()['cursor']['empty'], 'right-click and shift-click cannot remove a running or saving machine')
        f.do('dock_esc')
        f.inv()
        inventory = f.view()
        check(out, inventory['menu'].endswith('InventoryMenu') and len(inventory['slots']) == 46, 'Esc after docked fullscreen returns to the real player inventory menu')
        ordinary = f.player(3)
        check(out, ordinary.get('item') == 'minecraft:stone' and ordinary.get('count') == 3 and inventory['cursor']['empty'], 'original harmless stone stack remains intact before an ordinary inventory move')
        f.click_menu(inventory_slot(3))
        check(out, f.view()['cursor'].get('nbt') == ordinary['nbt'] and f.player(3)['empty'], 'ordinary inventory pickup after docked play uses the player menu rather than the old dock slots')
        f.click_menu(inventory_slot(3))
        check(out, f.view()['cursor']['empty'] and f.player(3).get('nbt') == ordinary['nbt'], 'ordinary inventory placement restores the same complete stone stack')
        f.close(); f.open_dock()
        f.wait(lambda v: any(w['text'] == '保存并离开游戏' and w['active'] for w in v['upgrade']['widgets']), 'same dock menu exposes the active normal save-and-leave action')
        f.button('保存并离开游戏')
        final = f.wait(lambda v: not v['room'] and not v['workers'] and v['screen'].endswith('DockScreen'), 'normal dock save completes without losing its container')
        guest.wait(lambda v: not v['upgrade']['dock'].get('locked', True), 'slot unlocks only after the server session finishes saving')
        guest.click_menu(0, 1)
        check(out, guest.view()['cursor']['nbt'] == core['nbt'] and guest.cell()['empty'], 'another player can retrieve the whole machine after normal save')
        guest.click_menu(0, 1)
        f.wait(lambda v: v['upgrade']['slots'][0].get('nbt') == core['nbt'], 'returned whole machine synchronizes to the original viewer')
        evidence = d.command('server', 'dockslot_observe'); out['finalWorldEvidence'] = evidence
        saved = [s for s in evidence['saved'] if s['machine'] == core['machine'] and s['content'] == card['hash']]
        check(out, len(saved) == 1 and saved[0]['hash'] == final['savedStateHash'] == saved[0]['diskSha256'] and saved[0]['size'] == saved[0]['diskBytes'], 'docked game commits under the original machine/game key with matching actual world state bytes', saved)
        check(out, evidence['machineNbt'] == core['nbt'] and evidence['nbtRoundTrip'] and all(i.get('machine') != core['machine'] for role in (f, guest) for i in role.view()['inventory']), 'exactly the dock retains the original complete machine; both player inventories contain no duplicate')
        f.picture('dock-container-saved-slot')
        f.close(); guest.close()
        out['scenarioComplete'] = True
    except Exception as error:
        out['failure'] = str(error)
        raise
    finally:
        cleanup(d, out)
        out['observationTimeouts'] = d.observation_timeouts
        path.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding='utf-8')
    return out


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', type=Path, required=True)
    parser.add_argument('--build', type=Path, required=True)
    parser.add_argument('--execute', action='store_true')
    args = parser.parse_args()
    if not args.execute:
        parser.error('Use --execute for the normal owned-run inventory scenario')
    driver = Driver(args.run, args.build)
    print(json.dumps(run(driver), ensure_ascii=False, indent=2))
