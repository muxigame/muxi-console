"""Build the original link bridge against one exact complete MIT SameBoy source snapshot.

No source/product pin is edited and no toolchain is installed. The source delivery contains
the untouched upstream archive, this driver, original bridge and independent build instructions.
"""
from __future__ import annotations
import argparse
import datetime
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import zipfile

ROOT = Path(__file__).resolve().parents[1]
WORKSPACE = ROOT.parent
COMMIT = '90e2899574b7b63c59dce8a7276783426d52a068'
SOURCE_SHA = '6d1d2b586e261078dbb05905b4e535918c4206cf16c770cacc6cf65fbb2d3167'
LLVM_SHA = '45145c035d9246e1de16f1873aa9afa863d93909f4a8f363e2eb38a04031d3c3'
CORE_FILES = ['gb', 'sgb', 'apu', 'memory', 'mbc', 'timing', 'display', 'camera', 'sm83_cpu', 'joypad', 'save_state', 'random', 'rumble']


def sha(path): return hashlib.sha256(Path(path).read_bytes()).hexdigest()
def write_json(path, value): Path(path).write_text(json.dumps(value, indent=2) + '\n', encoding='utf-8')


def build(args):
    archive = args.source_archive.resolve()
    if sha(archive) != SOURCE_SHA: raise ValueError('Exact complete SameBoy archive hash differs')
    out = args.output.resolve()
    if (WORKSPACE / 'projects.json').is_file():
        allowed = (WORKSPACE / 'test-environment/cache/muxi-console/rebuild-sameboy-link').resolve()
        if not out.is_relative_to(allowed): raise ValueError('Use the dedicated shared link rebuild cache')
    out.mkdir(parents=True, exist_ok=False)
    llvm = args.llvm.resolve()
    compilers = list(llvm.rglob('x86_64-w64-mingw32-clang.exe'))
    if len(compilers) != 1: raise ValueError('Provide one existing LLVM-MinGW installation')
    compiler = compilers[0]
    # Scoped child PATH; no inherited application/task paths in the build or public record.
    env = {k: v for k, v in os.environ.items() if k not in ['PATH', 'CC', 'CXX', 'CFLAGS', 'CXXFLAGS', 'LDFLAGS']}
    env['PATH'] = os.pathsep.join([str(compiler.parent), str(Path(os.environ.get('SystemRoot', 'C:/Windows')) / 'System32')])
    source = out / 'source'; source.mkdir()
    with tarfile.open(archive) as tar:
        for member in tar.getmembers():
            parts = Path(member.name).parts[1:]
            if not parts: continue
            target = source.joinpath(*parts)
            if not target.resolve().is_relative_to(source.resolve()) or not (member.isdir() or member.isfile()):
                raise ValueError('Unsafe upstream archive entry')
            if member.isdir(): target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with target.open('xb') as stream: stream.write(tar.extractfile(member).read())
    boot_c = out / 'boot-arrays.c'
    blocks = []
    for name in ['dmg', 'mgb', 'cgb0', 'cgb', 'agb', 'sgb', 'sgb2']:
        data = (source / f'BootROMs/prebuilt/{name}_boot.bin').read_bytes()
        blocks.append('const unsigned char ' + name + '_boot[]={' + ','.join(str(v) for v in data) + '};\n'
                      + 'const unsigned ' + name + '_boot_length=sizeof(' + name + '_boot);\n')
    boot_c.write_text('/* Arrays of the exact upstream MIT prebuilt open boot programs. */\n' + ''.join(blocks), encoding='ascii')
    adapter = args.adapter.resolve()
    flags = ['-std=gnu11', '-O2', '-DNDEBUG', '-DGB_INTERNAL', '-D__LIBRETRO__', '-D_GNU_SOURCE', '-D_USE_MATH_DEFINES',
             '-DGB_DISABLE_TIMEKEEPING', '-DGB_DISABLE_REWIND', '-DGB_DISABLE_DEBUGGER', '-DGB_DISABLE_CHEATS',
             '-DGB_VERSION="1.0.5 90e2899"', '-DGB_COPYRIGHT_YEAR="2026"', '-I' + str(source)]
    logs = []; objects = []
    files = [source / ('Core/' + name + '.c') for name in CORE_FILES] + [boot_c, adapter]
    def run(command):
        result = subprocess.run(command, cwd=out, env=env, capture_output=True, text=True, encoding='utf-8', errors='replace')
        logs.append(result.stdout + result.stderr); (out / 'build.log').write_text(''.join(logs), encoding='utf-8')
        if result.returncode: raise RuntimeError('Link adapter compilation failed; private build.log retained')
    for i, file in enumerate(files):
        obj = out / f'{i:02d}.o'; objects.append(obj)
        run([str(compiler), *flags, '-c', str(file), '-o', str(obj)])
    library = out / 'muxi_sameboy_link.dll'
    run([str(compiler), '-shared', '-Wl,--no-undefined,--no-insert-timestamp', *map(str, objects), '-o', str(library)])
    compressed = out / 'muxi_sameboy_link.dll.zip'
    with zipfile.ZipFile(compressed, 'x', zipfile.ZIP_DEFLATED) as z: z.write(library, library.name)
    instructions = '''# Independent SameBoy link adapter build

This source delivery contains the untouched official source archive, including its full tree,
original licenses, boot assembly and upstream MIT prebuilt open boot programs. The Muxi bridge and
build driver are original MIT code. There is no commercial game, manufacturer BIOS or source patch.

Use Python 3.11+ and official LLVM-MinGW 20250613 ucrt x86_64 (Clang 20.1.7):
https://github.com/mstorsjo/llvm-mingw/releases/download/20250613/llvm-mingw-20250613-ucrt-x86_64.zip
SHA256 45145c035d9246e1de16f1873aa9afa863d93909f4a8f363e2eb38a04031d3c3

From this extracted delivery, using an existing isolated toolchain:
python -B tools/build_link_adapter.py --source-archive upstream.tar.gz --adapter native/link/sameboy_link_adapter.c --llvm C:/path/to/llvm-mingw --output C:/path/to/new-build

The output directory must not already exist. Builds compile one file at a time (no make/job fanout).
In the registered muxigame workspace, output must stay within test-environment/cache/muxi-console/rebuild-sameboy-link.
No global environment or existing toolchain is modified. The scoped compiler PATH contains only
LLVM_BIN and WINDOWS_SYSTEM32. This establishes this new binary's source; no old-DLL or bit-identical claim.

The original libretro frontend still loads two ROMs via subsystem 0x101 and runs its official serial
bit callbacks and CPU scheduler. The bridge adds bounded per-endpoint PCM taps and single-endpoint
state access. Both CPUs execute only once per committed frame; no audio/state replay is used.
'''
    delivery = out / 'corresponding-source.tar.gz'
    with delivery.open('xb') as raw, gzip.GzipFile(filename='', mode='wb', fileobj=raw, mtime=0) as gz, tarfile.open(fileobj=gz, mode='w') as tar:
        entries = [('upstream.tar.gz', archive.read_bytes()), ('tools/build_link_adapter.py', Path(__file__).read_bytes()),
                   ('native/link/sameboy_link_adapter.c', adapter.read_bytes()), ('BUILDING.md', instructions.encode()),
                   ('LICENSE', (ROOT / 'LICENSE').read_bytes())]
        for name, data in entries:
            item = tarfile.TarInfo(name); item.size = len(data); item.mode = 0o644; item.mtime = 0; tar.addfile(item, io.BytesIO(data))
    version = subprocess.run([str(compiler), '--version'], capture_output=True, text=True, env=env, encoding='utf-8').stdout.splitlines()[0]
    report = {'schemaVersion': 1, 'sourceRepository': 'https://github.com/libretro/SameBoy', 'sourceCommit': COMMIT,
              'upstreamArchive': {'sha256': SOURCE_SHA, 'bytes': archive.stat().st_size},
              'library': {'path': library.name, 'bytes': library.stat().st_size, 'sha256': sha(library)},
              'archive': {'path': compressed.name, 'bytes': compressed.stat().st_size, 'sha256': sha(compressed)},
              'sourceArchive': {'path': delivery.name, 'format': 'tar.gz', 'bytes': delivery.stat().st_size, 'sha256': sha(delivery), 'patchesApplied': False},
              'adapter': {'path': 'native/link/sameboy_link_adapter.c', 'sha256': sha(adapter), 'license': 'MIT'},
              'buildScript': {'path': 'tools/build_link_adapter.py', 'sha256': sha(Path(__file__))},
              'toolchain': {'version': version, 'compilerSHA256': sha(compiler), 'archiveSHA256': LLVM_SHA,
                            'url': 'https://github.com/mstorsjo/llvm-mingw/releases/download/20250613/llvm-mingw-20250613-ucrt-x86_64.zip'},
              'build': {'jobs': 1, 'defines': [f for f in flags if f.startswith('-D')], 'language': 'gnu11', 'optimization': 'O2',
                        'environmentPathRoles': ['LLVM_BIN', 'WINDOWS_SYSTEM32'], 'prebuiltOpenBootPrograms': True},
              'normalCompileExit': True, 'nativeGameplayTested': False, 'builtUtc': datetime.datetime.now(datetime.timezone.utc).isoformat()}
    write_json(out / 'build-report.json', report)
    print('Built exact-source SameBoy link bridge:', report['library']['sha256'])


def main():
    ap = argparse.ArgumentParser(); ap.add_argument('--source-archive', type=Path, required=True)
    ap.add_argument('--adapter', type=Path, default=ROOT / 'native/link/sameboy_link_adapter.c')
    ap.add_argument('--llvm', type=Path, required=True); ap.add_argument('--output', type=Path, required=True)
    build(ap.parse_args())


if __name__ == '__main__': main()
