"""Compile worker/link code and run original dual-ROM plus official single-core compatibility probes."""
from __future__ import annotations
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import zipfile
from link_diagnostic import diagnostic, boot

ROOT = Path(__file__).resolve().parents[1]


def sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def main():
    ap = argparse.ArgumentParser(); ap.add_argument('--bridge', type=Path, required=True); ap.add_argument('--output', type=Path, required=True)
    ap.add_argument('--platform', choices=['gb', 'gbc', 'mixed','gba'], default='gb');ap.add_argument('--reverse',action='store_true');ap.add_argument('--legacy-state',type=Path);args = ap.parse_args()
    if args.legacy_state and args.platform!='gba':raise ValueError('Legacy SRAM migration probe is specific to mGBA')
    if args.reverse and args.platform!='gba':raise ValueError('Reversed physical-port probe currently uses the GBA hardware-ID diagnostic')
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=False); classes = out / 'classes'; classes.mkdir()
    jdk = ROOT.parent / 'test-environment/toolchains/java-21/bin'
    import build_worker
    jars = []
    for dep in build_worker.dependencies():
        choices = [ROOT.parent / 'test-environment/cache/muxi-console/dependencies' / dep['file']]
        choices += list((ROOT.parent / 'test-environment/resources/client/game/libraries').rglob(dep['file']))
        match = next((p for p in choices if p.is_file() and p.stat().st_size == dep['bytes'] and sha(p) == dep['sha256']), None)
        if match is None: raise RuntimeError('Missing pinned existing worker dependency; no download performed')
        jars.append(match)
    sources = sorted((ROOT / 'common/src/main/java/net/muxigame/console/runtime').glob('*.java')) + [ROOT / 'tests/link/native/LinkSmoke.java',ROOT/'tests/link/native/GbaLegacyStateSmoke.java']
    inputs = {str(p.relative_to(ROOT)): sha(p) for p in sources}
    command = [str(jdk / 'javac.exe'), '-J-Xmx512m', '-J-XX:ActiveProcessorCount=1', '-J-Duser.language=en', '-J-Dfile.encoding=UTF-8', '--release', '21', '-encoding', 'UTF-8', '-proc:none', '-cp', os.pathsep.join(map(str, jars)), '-d', str(classes), *map(str, sources)]
    result = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', errors='replace')
    (out / 'compile.log').write_text(result.stdout + result.stderr, encoding='utf-8')
    if result.returncode: raise RuntimeError('Link narrow compilation failed; see compile.log')
    manifest = json.loads((ROOT / 'third-party/cores.json').read_text(encoding='utf-8'))['cores']; rows = manifest.values() if isinstance(manifest, dict) else manifest
    core_id='mgba'if args.platform=='gba'else 'sameboy'
    pin = next(p for p in rows if core_id in p.get('source', '').lower())['platforms']['windows-x86_64']
    archive = ROOT.parent / pin['archive']
    if sha(archive) != pin['archiveSha256']: raise RuntimeError('Official base archive changed')
    payload = out / 'native'; payload.mkdir(); base = payload / pin['libraryName']
    with zipfile.ZipFile(archive) as z: base.write_bytes(z.read(next(n for n in z.namelist() if Path(n).name == pin['libraryName'])))
    if sha(base) != pin['librarySha256']: raise RuntimeError('Official base DLL changed')
    system = out / 'system'; system.mkdir()
    if args.platform!='gba':
        for name in ['dmg', 'mgb', 'cgb', 'cgbE']: (system / (name + '_boot.bin')).write_bytes(boot(name.startswith('cgb')))
    roms = []
    for i, challenge in enumerate([0x3c, 0xa7]):
        platform = ('gb' if i == 0 else 'gbc') if args.platform == 'mixed' else args.platform
        path = out / ('original-endpoint-' + str(i + 1) + '.' + platform)
        if platform=='gba':
            from link_gba_diagnostic import diagnostic as gba_diagnostic
            path.write_bytes(gba_diagnostic(i))
        else:path.write_bytes(diagnostic(i, challenge, platform == 'gbc'))
        roms.append(path)
    if args.reverse:roms.reverse()
    report_path=args.bridge.parent/'build-report.json'
    if not report_path.is_file():report_path=args.bridge.parent.parent/'build-report.json'
    report = json.loads(report_path.read_text(encoding='utf-8'))
    if sha(args.bridge) != report['library']['sha256']: raise RuntimeError('Exact-source bridge changed')
    cp = os.pathsep.join(map(str, [classes, *jars]))
    command = [str(jdk / 'java.exe'), '-Xmx256m', '-XX:ActiveProcessorCount=1', '-Djava.awt.headless=true', '-ea', '-cp', cp,
               'net.muxigame.console.tests.link.LinkSmoke', str(jdk / 'java.exe'), str(args.bridge.resolve()), str(base), *map(str, roms), str(system), str(out / 'result'), args.platform,*(['reverse']if args.reverse else[])]
    result = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', errors='replace')
    (out / 'native.log').write_text(result.stdout + result.stderr, encoding='utf-8')
    if result.returncode==0 and args.legacy_state:
        extra=subprocess.run([str(jdk/'java.exe'),'-Xmx256m','-XX:ActiveProcessorCount=1','-cp',cp,'net.muxigame.console.tests.link.GbaLegacyStateSmoke',str(jdk/'java.exe'),str(args.bridge.resolve()),str(roms[0]),str(roms[1]),str(args.legacy_state.resolve()),str(out/'legacy')],capture_output=True,text=True,encoding='utf-8',errors='replace');(out/'legacy.log').write_text(extra.stdout+extra.stderr,encoding='utf-8')
        if extra.returncode:raise RuntimeError('Actual legacy mGBA import probe failed; original log retained')
        print(extra.stdout.strip())
    base.unlink(); payload.rmdir()
    (out / 'inputs.json').write_text(json.dumps(inputs, indent=2) + '\n', encoding='utf-8')
    if result.returncode: raise RuntimeError('Link native/compatibility probe failed; see native.log')
    if any(sha(ROOT / p) != h for p, h in inputs.items()): raise RuntimeError('Runtime input source changed during probe; results retained without freeze claim')
    print(result.stdout.strip())


if __name__ == '__main__': main()
