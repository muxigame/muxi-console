"""Wrap our verified original DOL in an unencrypted Wii data partition and WBFS.

This is a small executable disc, not a renamed DOL or a commercial game image.
Its apploader, ticket/TMD metadata and filesystem are authored here. No vendor
apploader, signature, key, BIOS or game assets are read or distributed.
"""
from __future__ import annotations
import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess

ROOT = Path(__file__).resolve().parents[1]
SDK = ROOT.parent / 'test-environment/toolchains/muxi-console-expansion-devkitpro/install/opt/devkitpro/devkitPPC/bin'
DOL_OFFSET = 0x10000
PARTITION = 0x50000
PARTITION_DATA = 0x20000
APPLOADER = 0x2440
WBFS_BLOCK = 0x200000
GAME_ID = b'MXIE00'
TITLE_ID = 0x000100004d584945


def sha(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def build(name: str, diagnostics: Path):
    if Path(name).name != name or not name or any(c not in 'abcdefghijklmnopqrstuvwxyz0123456789-' for c in name):
        raise ValueError('Choose a new lowercase simple build name')
    out = ROOT / 'build' / name
    if out.exists():
        raise ValueError('Preserve prior outputs; choose a new build name')
    metadata = json.loads(diagnostics.read_text(encoding='utf-8'))
    if not metadata.get('original') or metadata.get('biosIncluded') or metadata.get('gameAssetsIncluded'):
        raise ValueError('Only the separately verified original diagnostic is accepted')
    for relative, digest in metadata['sources'].items():
        if sha(ROOT / relative) != digest:
            raise ValueError('Original diagnostic source differs: ' + relative)
    source = next(p for p in metadata['outputs'] if Path(p['path']).suffix == '.dol')
    dol_path = Path(source['path'])
    if dol_path.stat().st_size != source['bytes'] or sha(dol_path) != source['sha256']:
        raise ValueError('Verified original DOL differs')
    dol = dol_path.read_bytes()
    if len(dol) < 256:
        raise ValueError('Truncated original DOL')
    be32 = lambda offset: struct.unpack_from('>I', dol, offset)[0]
    sections = []
    for i in range(18):
        offset, address, length = be32(i * 4), be32(0x48 + i * 4), be32(0x90 + i * 4)
        if not length:
            continue
        if offset < 256 or offset + length > len(dol) or offset % 4 or not 0x80000000 <= address < address + length <= 0x81800000:
            raise ValueError('Original diagnostic segment is outside the permitted disc-loader range')
        sections.append((address, length, (DOL_OFFSET + offset) // 4))
    if not sections or not any(a <= be32(0xe0) < a + n for a, n, _ in sections):
        raise ValueError('DOL entry does not lie in an original segment')
    out.mkdir()
    header = out / 'wii-disc-layout.h'
    header.write_text(
        '/* Generated only from the verified original DOL. */\n'
        'struct muxi_section { unsigned address, length, disc_words; };\n'
        'static const struct muxi_section muxi_sections[] = {\n' +
        ''.join('{0x%x,0x%x,0x%x},\n' % row for row in sections) + '};\n' +
        '#define MUXI_SECTION_COUNT %du\n' % len(sections) +
        '#define MUXI_BSS_ADDRESS 0x%xu\n#define MUXI_BSS_LENGTH 0x%xu\n#define MUXI_DOL_ENTRY 0x%xu\n' %
        (be32(0xd8), be32(0xdc), be32(0xe0)), encoding='utf-8')
    loader = ROOT / 'tests/runtime/diagnostics/expansion/disc/apploader.c'
    def run(label, args):
        result = subprocess.run(list(map(str, args)), cwd=out, capture_output=True, text=True, encoding='utf-8', errors='replace', timeout=60)
        (out / (label + '.log')).write_text(result.stdout + result.stderr, encoding='utf-8')
        if result.returncode:
            raise RuntimeError(label + ' failed: ' + result.stderr[-2000:])
    elf = out / 'apploader.elf'
    binary = out / 'apploader.bin'
    run('apploader-compile', [SDK / 'powerpc-eabi-gcc.exe', '-Os', '-mcpu=750', '-meabi', '-msdata=none', '-ffreestanding', '-fno-builtin', '-fno-stack-protector', '-fno-pic', '-nostdlib', '-Wl,-Ttext=0x81200000', '-Wl,-e,apploader_entry', '-I' + str(out), loader, '-o', elf])
    run('apploader-binary', [SDK / 'powerpc-eabi-objcopy.exe', '-O', 'binary', elf, binary])
    result = subprocess.run([str(SDK / 'powerpc-eabi-nm.exe'), '-n', str(elf)], capture_output=True, text=True, encoding='utf-8', check=True, timeout=15)
    symbols = {parts[2]: int(parts[0], 16) for line in result.stdout.splitlines() if len(parts := line.split()) == 3}
    app = binary.read_bytes()
    if not 0 < len(app) < DOL_OFFSET - APPLOADER - 32 or not 0x81200000 <= symbols['apploader_entry'] < 0x81200000 + len(app):
        raise ValueError('Original apploader does not fit its dedicated disc area')
    # The compact ISO contains one plain data partition. Byte 0x60 and 0x61
    # explicitly disable Wii hashes/encryption, supported by Dolphin VolumeWii.
    iso = bytearray(0x100000)
    def put(offset, value, fmt='>I'):
        struct.pack_into(fmt, iso, offset, value)
    iso[:6] = GAME_ID
    put(0x18, 0x5d1c9ea3)
    title = b'Muxi Original WBFS Diagnostic\0'
    iso[0x20:0x20 + len(title)] = title
    iso[0x60] = iso[0x61] = 1
    put(0x40000, 1); put(0x40004, 0x40020 // 4)
    put(0x40020, PARTITION // 4); put(0x40024, 0)
    put(0x4e000, 1)  # NTSC-U, matched to the original MXIE test identifier.
    # Public ES structures, with our own unsigned metadata and no certificates.
    put(PARTITION, 0x10001)
    issuer = b'MUXI-TEST\0'
    iso[PARTITION + 0x140:PARTITION + 0x140 + len(issuer)] = issuer
    put(PARTITION + 0x1dc, TITLE_ID, '>Q')
    tmd_offset, tmd_size = 0x400, 0x1e4
    put(PARTITION + 0x2a4, tmd_size); put(PARTITION + 0x2a8, tmd_offset // 4)
    put(PARTITION + 0x2b8, PARTITION_DATA // 4); put(PARTITION + 0x2bc, (len(iso) - PARTITION - PARTITION_DATA) // 4)
    tmd = PARTITION + tmd_offset
    put(tmd, 0x10001); iso[tmd + 0x140:tmd + 0x140 + len(issuer)] = issuer
    put(tmd + 0x184, 0x000000010000003a, '>Q')  # The emulator's HLE IOS58.
    put(tmd + 0x18c, TITLE_ID, '>Q'); put(tmd + 0x194, 1)
    put(tmd + 0x198, 0x3030, '>H'); put(tmd + 0x19c, 1, '>H')
    data = PARTITION + PARTITION_DATA
    iso[data:data + 0x100] = iso[:0x100]
    put(data + 0x420, DOL_OFFSET // 4)
    put(data + 0x424, 0xf000 // 4); put(data + 0x428, 12); put(data + 0x42c, 12)
    put(data + 0xf000, 0x01000000); put(data + 0xf004, 0); put(data + 0xf008, 1)
    iso[data + APPLOADER:data + APPLOADER + 10] = b'MUXI-TEST\0'
    put(data + APPLOADER + 0x10, symbols['apploader_entry']); put(data + APPLOADER + 0x14, len(app))
    iso[data + APPLOADER + 0x20:data + APPLOADER + 0x20 + len(app)] = app
    iso[data + DOL_OFFSET:data + DOL_OFFSET + len(dol)] = dol
    iso_path = out / 'muxi-original-disc.iso'
    iso_path.write_bytes(iso)
    # WBFS uses 512-byte host sectors and 2 MiB mapped disc clusters. Store one
    # metadata cluster and one real data cluster; the advertised disc is sparse.
    wbfs = bytearray(2 * WBFS_BLOCK)
    wbfs[:4] = b'WBFS'; struct.pack_into('>I', wbfs, 4, len(wbfs) // 512)
    wbfs[8] = 9; wbfs[9] = 21; wbfs[12] = 1
    wbfs[512:768] = iso[:256]; struct.pack_into('>H', wbfs, 768, 1)
    wbfs[WBFS_BLOCK:WBFS_BLOCK + len(iso)] = iso
    wbfs_path = out / 'muxi-original-disc.wbfs'
    wbfs_path.write_bytes(wbfs)
    # Independently decode the on-disk WBFS mapping and compare every authored
    # ISO byte, not only its magic or filename.
    block = 1 << wbfs[9]; mapped = struct.unpack_from('>H', wbfs, 512 + 256)[0]
    if bytes(wbfs[mapped * block:mapped * block + len(iso)]) != bytes(iso):
        raise AssertionError('WBFS data mapping differs from the actual original ISO')
    files = [{'path': str(p), 'bytes': p.stat().st_size, 'sha256': sha(p)} for p in (iso_path, wbfs_path)]
    report = dict(original=True, vendorApploaderIncluded=False, biosIncluded=False, keysIncluded=False,
                  gameAssetsIncluded=False, partitionEncrypted=False, partitionHasHashes=False,
                  originalDol=source, originalDiagnostics=dict(path=str(diagnostics), sha256=sha(diagnostics)),
                  apploader=dict(source=loader.relative_to(ROOT).as_posix(), sourceSha256=sha(loader), bytes=len(app), entry=symbols['apploader_entry'], sha256=sha(binary)),
                  toolSha256=sha(Path(__file__)), sections=sections, programs=files,
                  structuralChecksPassed=True, nativeBootTested=False)
    (out / 'disc-diagnostic.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
    return report


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--name', required=True)
    parser.add_argument('--diagnostics', type=Path, default=ROOT / 'build/expansion-diagnostics-12/diagnostics.json')
    options = parser.parse_args()
    print(json.dumps(build(options.name, options.diagnostics), indent=2))
