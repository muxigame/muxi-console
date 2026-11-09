"""Real WebDisplays/CEF video and passive PCM from one normally burned NES console."""
from __future__ import annotations
import argparse
import json
import time
from pathlib import Path
from drive import Driver, sha
from polish_review import record, check
from cartridge_review import cleanup
from link_review import LinkFront
from media_review import received, observe_window
from browser_pages import Pages


def finish(d, out, host, guest, fixture, target, position, before_guest):
    position('host', 10.5, 11.5)
    host.do('slot', dict(slot=8)); host.do('use_block', dict(x=10))
    host.wait(lambda v: v['screen'].endswith('DeviceScreen'), 'open the actual occupied source console')
    before_focus = host.see()
    host.do('button', dict(text='P1 加入'))
    focused = host.wait(lambda v: v['screen'].endswith('GameScreen') and v['ready'] and v['workerRunning'], 'resume actual game input focus')
    check(out, focused['room'] == before_focus['room'] and focused['epoch'] == before_focus['epoch'] and focused['workers'] == before_focus['workers'], 'normal existing-seat reopen gives real input focus without restarting the source')
    previous = host.see()['crc']
    host.do('input_source', dict(buttons=256))
    host.wait(lambda v: v['crc'] and v['crc'] != previous, 'real CPU picture changes after A input')
    host.do('input_source', dict(buttons=0))
    for role in ('host', 'guest'):
        first = next(row for row in out['gpuSamples'] if row['role'] == role)
        out['gpuSamples'].append(d.gpu_sample(role, target, first['textureCrc']))
    start, end, rate = observe_window(guest, ('browser_frame', 'browser_audio', 'rom_chunk'))
    d.assert_observer(end)
    check(out, rate['operations']['browser_frame']['packets'] >= 10
          and rate['operations']['browser_audio']['nonzeroPcmSamples'] > 1000
          and rate['operations']['rom_chunk']['dataBytes'] == 0,
          'passive actual WebDisplays viewing transports live pixels and nonzero PCM with zero ROM/core', rate)
    out['passiveWebDisplaysRate'] = rate
    for role in ('host', 'guest'):
        other = 'guest' if role == 'host' else 'host'
        position(other, 16 if role == 'host' else 9, 14.5)
        d.command('server', 'browser_camera', dict(role=role))
        d.command(role, 'review_view', dict(held=False)); d.settle(role, 10)
        row = d.capture(role, 'browser-media-' + role + '-actual-face')
        row.update(role=role, browser=d.command(role, 'browser_native', target)['browserNative'])
        out['screenshots'].append(row)
    position('host', 13, 11.5); position('guest', 13.5, 13.5)

    def restored(url):
        for role in ('host', 'guest'):
            deadline=time.monotonic()+30;last={}
            while time.monotonic()<deadline:
                last=d.command(role,'browser_native',target)['browserNative']
                if (not last.get('linkedNativeBrowser') and last.get('url')==url and last.get('browserUrl')==url
                        and (not url.startswith('http://127.0.0.1:26323/') or last.get('gpuRead') and last.get('chromaticPixels',0)>90000)):
                    break
                time.sleep(.2)
            else:
                raise TimeoutError('Actual target face did not restore its native page: '+json.dumps({k:last.get(k)for k in ('url','browserUrl','linkedNativeBrowser','textureCrc','chromaticPixels')}))
            out.setdefault('restorations', []).append(dict(role=role,**last))

    host.do('browser_select', target)
    d.wait_for('server', lambda v: v['browser']['bindings'] == 0, 'normal controller unbind')
    restored(fixture['url'])
    check(out, True, 'normal unbind restores both real third-party browser URLs')
    host.do('browser_select', target)
    d.wait_for('server', lambda v: v['browser']['bindings'] == 1, 'second normal binding')
    d.command('server', 'browser_owner', dict(owner='guest'))
    d.wait_for('server', lambda v: v['browser']['bindings'] == 0, 'revoked URL rights remove the binding')
    restored(fixture['url'])
    check(out, True, 'URL rights revocation restores both real third-party pages')
    d.command('server', 'browser_owner', dict(owner='host'))
    host.do('browser_select', target)
    d.wait_for('server', lambda v: v['browser']['bindings'] == 1, 'third normal binding')
    navigated = fixture.get('navigationUrl','about:blank#muxi-console-navigated')
    d.command('server', 'browser_url', dict(url=navigated))
    d.wait_for('server', lambda v: v['browser']['bindings'] == 0, 'normal navigation invalidates the binding')
    restored(navigated)
    check(out, True, 'later normal navigation is preserved on both clients')
    d.command('server', 'browser_url', dict(url=fixture['url']))
    host.do('browser_select', target)
    d.wait_for('server', lambda v: v['browser']['bindings'] == 1, 'final normal binding')
    closed = host.leave('actual WebDisplays source final checkpoint')
    out['finalSaveHash'] = closed['savedStateHash']
    d.wait_for('server', lambda v: v['browser']['bindings'] == 0, 'source leave removes browser binding')
    restored(fixture['url'])
    final_guest = guest.see(); d.assert_observer(final_guest)
    check(out, final_guest['normalWorkerExits'] == before_guest['normalWorkerExits']
          and final_guest['forcedWorkerExits'] == final_guest['failedWorkerExits'] == 0,
          'passive player remains core-free from start through source shutdown')
    out['scenarioComplete'] = True


def run(d):
    path, out = record(d, 'browser-media')
    out.update(driverSha256=sha(Path(__file__)), qaBuildSha256=sha(d.build_path))
    host, guest = (LinkFront(d, out, role) for role in ('host', 'guest'))
    try:
        if 'mediaDiagnosticRom' not in d.build:
            raise ValueError('Build with the exact candidate audible-media probe')
        out['dependencies'] = d.browser_dependencies()
        prepared = d.prepare()
        fixture = d.command('server', 'browser_media_prepare_blocks')
        target = {k: fixture[k] for k in ('position', 'side')}
        for role in ('host', 'guest'):
            d.command(role, 'browser_native', target)
            d.wait_for(role, lambda v: v['browser'].get('block') == 'webdisplays:screen'
                       and v['browser'].get('fixtureBlocks') == 6,
                       'all real client display blocks arrive before addScreen: ' + role)
        fixture = d.command('server', 'browser_create_face')
        out['fixture'] = fixture
        for role in ('host', 'guest'):
            d.wait_for(role, lambda v: v['browser'].get('surfaceExists')
                       and v['browser'].get('browserPresent') and v['browser'].get('url')==fixture['url']
                       and v['browser'].get('browserUrl')==fixture['url'], 'real original WebDisplays page before any binding: ' + role)
        card = host.bare_burn('media_nes', d.build['mediaDiagnosticRom'], 7)
        out['card'] = card
        before_host = host.see()
        before_guest = guest.see()
        d.assert_observer(before_guest)
        position = lambda role, x, z: d.command('server', 'position',
            dict(player=d.marker['clientNames'][role], x=x, z=z))
        position('host', 10.5, 11.5)
        host.do('slot', dict(slot=7)); host.do('use_block', dict(x=10))
        host.do('slot', dict(slot=1)); host.do('use_block', dict(x=10))
        host.do('slot', dict(slot=8)); host.do('use_block', dict(x=10))
        host.wait(lambda v: v['screen'].endswith('DeviceScreen'), 'actual NES source controls')
        host.do('button', dict(text='启动 / 停止'))
        host.do('button', dict(text='连接附近屏幕'))
        host.do('button', dict(text='P1 加入'))
        source = host.wait(lambda v: v['ready'] and v['workerRunning'] and v['frames'] >= 60,
                           'real audible original NES source', 120)
        check(out, received(source, 'rom_chunk') == received(before_host, 'rom_chunk'),
              'normally burned author starts from its verified cache without a ROM return download')
        position('host', 13, 11.5); position('guest', 13.5, 13.5)
        guest.do('probe', dict(hash=card['hash'], room=source['room'], epoch=source['epoch']))
        denied = guest.wait(lambda v: v['probe'] != 'pending', 'passive observer ROM authorization')
        check(out, denied['probe'] == 'denied', 'passive observer cannot download the ROM')
        d.assert_observer(denied)
        # The fixture gives the actual observer a controller but no seat or URL rights.
        guest.do('browser_select', target); guest.settle(4)
        check(out, d.command('server', 'observe')['browser']['bindings'] == 0,
              'a passive controller holder cannot bind the screen')
        d.command('server', 'browser_owner', dict(owner='guest'))
        host.do('browser_select', target); host.settle(4)
        check(out, d.command('server', 'observe')['browser']['bindings'] == 0,
              'an active player without WebDisplays URL rights cannot bind the screen')
        d.command('server', 'browser_owner', dict(owner='host'))
        d.command('server', 'browser_url', dict(url=fixture['url']))
        for role in ('host','guest'):
            d.wait_for(role,lambda v:v['browser'].get('url')==fixture['url'] and v['browser'].get('browserUrl')==fixture['url'],'actual original page remains selected after URL-right fixtures: '+role)
        host.do('browser_select', target)
        d.wait_for('server', lambda v: v['browser']['bindings'] == 1, 'real controller use binds the face')
        out['gpuSamples'] = []
        for role in ('host', 'guest'):
            d.wait_for(role, lambda v: v['browser']['readyBrowsers'] == 1
                       and v['browser']['receivedFrames'] >= 4, 'actual CEF canvas is live: ' + role)
            out['gpuSamples'].append(d.gpu_sample(role, target))
        finish(d, out, host, guest, fixture, target, position, before_guest)
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
    args = parser.parse_args()
    with Pages():
        result = run(Driver(args.run, args.build))
    print(json.dumps(dict(complete=result['scenarioComplete'], checks=len(result['checks']),
                         images=len(result['screenshots']))))
