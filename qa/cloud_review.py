"""Actual optional cloud writer, duplicate burn, physical loan and direct client ROM loading. Never launches MC."""
from __future__ import annotations
import argparse,json
from pathlib import Path
from drive import Driver,sha
from polish_review import record,check
from cartridge_review import items,raw,same_card,cleanup
from transfer_review import TransferFront
from media_review import received


def remote_only(d,card):
    root=d.root/'server/qa-world/muxi-console/content'
    digest=card['hash'];metadata=root/digest[:2]/(digest+'.meta')
    return metadata.is_file() and 'storage=object' in metadata.read_text(encoding='ascii').splitlines() and not list(root.glob('??/*.rom'))


def run(d):
    path,out=record(d,'cloud-storage');out.update(driverSha256=sha(Path(__file__)),qaBuildSha256=sha(d.build_path))
    host,guest=(TransferFront(d,out,role)for role in('host','guest'))
    try:
        pin=d.build['gbaDiagnosticRom'];size=Path(pin['path']).stat().st_size
        d.prepare();d.command('server','dock_prepare');out['fixture']=d.command('server','cartridge_prepare')
        actual=d.command('server','observe')
        check(out,actual['cloudStorageConfigured']and not actual['localUploadServerRunning'],'this actual server enabled only the external optional cloud adapter')
        for f in(host,guest):f.do('review_view',dict(held=False))
        occupied={v['slot']for v in items(host.see())};free=[v for v in range(9,36)if v not in occupied]
        if len(free)<2:raise ValueError('Two observed empty real author slots are required')
        original_slot,duplicate_slot=free[:2]
        before=host.see();card=host.bare_burn('gba',pin,original_slot);after=host.see();out['originalCard']=card
        first_ack=received(after,'upload_ack','metadataBytes')-received(before,'upload_ack','metadataBytes')
        check(out,remote_only(d,card)and d.command('server','observe')['contentBytes']==size,'normal cloud burn writes a world reference and no server ROM body')
        before=host.see();host.inv();host.install(card,7);host.close();host.do('slot',dict(slot=7))
        live=host.wait(lambda v:v['ready']and v['workerRunning']and v['frames']>=30,'author runs the normally cloud-burned GBA from its original cache')
        check(out,received(live,'rom_chunk')==received(before,'rom_chunk')and live['downloadsGranted']==before['downloadsGranted'],'cloud author starts with zero ROM return download')
        author=host.leave('cloud author original machine');host.do('slot',dict(slot=8));host.inv();host.eject(7,card,original_slot);host.close()
        before=host.see();duplicate=host.bare_burn('gba',pin,duplicate_slot);after=host.see();out['duplicateCard']=duplicate
        second_ack=received(after,'upload_ack','metadataBytes')-received(before,'upload_ack','metadataBytes')
        check(out,duplicate['hash']==card['hash']and duplicate['instance']!=card['instance']and remote_only(d,card)and 0<second_ack<first_ack,'duplicate cloud burn has a new physical card and a shorter reuse acknowledgement without a PUT ticket',dict(firstAckMetadataBytes=first_ack,reusedAckMetadataBytes=second_ack))
        guest.camera(22.5,22.5,180,10);host.camera(5.5,12.5,0,0);host.do('select_card',dict(instance=card['instance']));host.do('drop');host.camera(10.5,11.5);host.settle(45)
        drop=next(v for v in d.command('server','observe')['dock']['drops']if v.get('instance')==card['instance']);guest.camera(drop['x'],drop['z'],180,10)
        got=guest.wait(lambda v:any(i.get('instance')==card['instance']for i in items(v)),'the other real player picks up the original complete cloud cartridge')
        check(out,same_card(raw(got,card['instance']),card)and not any(v.get('instance')==card['instance']for v in items(host.see())),'physical cloud cartridge transfer preserves its complete stack')
        guest.do('slot',dict(slot=8));guest.inv();guest.install(card,7);guest.close();guest.camera();before=guest.see();guest.do('slot',dict(slot=7))
        loaded=guest.wait(lambda v:v['ready']and v['workerRunning']and v['frames']>=30,'borrower downloads directly from actual object storage and boots GBA')
        check(out,received(loaded,'rom_ticket','packets')>received(before,'rom_ticket','packets')and received(loaded,'rom_chunk')==received(before,'rom_chunk'),'borrower receives an actual cloud ticket with zero Minecraft ROM-body bytes')
        cache=guest.do('cartridge_cache',dict(hash=card['hash']))['cacheCheck']['files'];complete=[v for v in cache if v['name'].endswith('.gba')]
        check(out,len(complete)==1 and complete[0]['bytes']==size and complete[0]['sha256']==pin['sha256'],'actual borrower cache has the exact original ROM SHA256',cache)
        check(out,not loaded['restoredStateHash'],'borrowed card does not import the author machine progress')
        guest.do('cartridge_use',dict(hand='MAIN_HAND'));focused=guest.wait(lambda v:v['screen'].endswith('GameScreen')and v['polish'].get('presentation',{}).get('mode')=='model','normal right click enters the complete GBA model with real cloud-loaded pixels')
        guest.pic('cloud-gba-borrowed-model',focused);saved=guest.leave('cloud borrower machine');guest.do('slot',dict(slot=8));guest.settle(3)
        before=guest.see();guest.do('slot',dict(slot=7));warm=guest.wait(lambda v:v['ready']and v['workerRunning']and v['frames']>=30 and v['restoredStateHash']==saved['savedStateHash'],'borrower restores its own saved machine from its verified cloud ROM cache')
        check(out,received(warm,'rom_ticket','packets')==received(before,'rom_ticket','packets')and received(warm,'rom_chunk')==received(before,'rom_chunk'),'verified warm cloud cache needs no new ticket or ROM body')
        closed=guest.leave('cloud borrower cached restart');guest.do('slot',dict(slot=8))
        check(out,remote_only(d,card),'all cloud sessions retain only world metadata and local machine states on the server')
        out.update(authorSaveHash=author['savedStateHash'],borrowerSaveHash=closed['savedStateHash'],scenarioComplete=True)
    except Exception as failure:out['failure']=str(failure);raise
    finally:cleanup(d,out);out['observationTimeouts']=d.observation_timeouts;path.write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
    return out


if __name__=='__main__':
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--run',type=Path,required=True);p.add_argument('--build',type=Path,required=True);a=p.parse_args();v=run(Driver(a.run,a.build));print(json.dumps(dict(complete=v['scenarioComplete'],checks=len(v['checks']),images=len(v['screenshots']))))
