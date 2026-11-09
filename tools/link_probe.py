"""Probe the exact official SameBoy two-ROM subsystem using only our original serial diagnostics."""
from __future__ import annotations
import argparse
import ctypes as C
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def native_probe(out: Path, color: bool):
    from link_diagnostic import diagnostic, boot
    if sys.platform != 'win32': raise RuntimeError('This pinned probe is Windows x64 only')
    # Keep the diagnostic workload on one available processor; never alter a global toolchain/env.
    kernel = C.WinDLL('kernel32', use_last_error=True)
    kernel.GetCurrentProcess.restype = C.c_void_p
    process = kernel.GetCurrentProcess()
    available = C.c_size_t(); system = C.c_size_t()
    kernel.GetProcessAffinityMask(C.c_void_p(process), C.byref(available), C.byref(system))
    kernel.SetProcessAffinityMask(C.c_void_p(process), C.c_size_t(available.value & -available.value))
    system_dir = out / 'system'; system_dir.mkdir()
    for name in ('dmg_boot.bin', 'mgb_boot.bin', 'cgb_boot.bin', 'cgbE_boot.bin'):
        (system_dir / name).write_bytes(boot(name.startswith('cgb')))
    core = C.CDLL(str(out / 'sameboy_libretro.dll'))
    ENV = C.CFUNCTYPE(C.c_bool, C.c_uint, C.c_void_p)
    VIDEO = C.CFUNCTYPE(None, C.c_void_p, C.c_uint, C.c_uint, C.c_size_t)
    AUDIO = C.CFUNCTYPE(None, C.c_int16, C.c_int16)
    BATCH = C.CFUNCTYPE(C.c_size_t, C.c_void_p, C.c_size_t)
    POLL = C.CFUNCTYPE(None)
    INPUT = C.CFUNCTYPE(C.c_int16, C.c_uint, C.c_uint, C.c_uint, C.c_uint)
    class Game(C.Structure): _fields_ = [('path', C.c_char_p), ('data', C.c_void_p), ('size', C.c_size_t), ('meta', C.c_char_p)]
    class Variable(C.Structure): _fields_ = [('key', C.c_char_p), ('value', C.c_void_p)]
    keep = []
    directory = C.create_string_buffer(str(system_dir).encode()); keep.append(directory)
    options = {k: C.create_string_buffer(v.encode()) for k, v in {
        'sameboy_link': 'enabled', 'sameboy_screen_layout': 'top-down', 'sameboy_audio_output': 'Mix',
        'sameboy_model_1': 'Game Boy Color' if color else 'Game Boy',
        'sameboy_model_2': 'Game Boy Color' if color else 'Game Boy',
        'sameboy_mono_palette_1': 'greyscale', 'sameboy_mono_palette_2': 'greyscale',
    }.items()}
    negotiated = []; pictures = []; samples = [0]; masks = [0, 0]; endpoint_audio = [bytearray(), bytearray()]
    bridge = hasattr(core, 'muxi_link_abi')
    if bridge:
        core.muxi_link_audio_attach.restype = C.c_bool
        core.muxi_link_audio_did_overflow.restype = C.c_bool
        core.muxi_link_audio_read.argtypes = [C.c_uint, C.c_void_p, C.c_uint]
        core.muxi_link_audio_read.restype = C.c_int
        core.muxi_link_endpoint_state_size.argtypes = [C.c_uint]; core.muxi_link_endpoint_state_size.restype = C.c_size_t
        core.muxi_link_endpoint_state_save.argtypes = [C.c_uint, C.c_void_p, C.c_size_t]; core.muxi_link_endpoint_state_save.restype = C.c_bool
    def step():
        if bridge: core.muxi_link_audio_clear()
        core.retro_run()
        if bridge:
            if core.muxi_link_audio_did_overflow(): raise RuntimeError('Per-endpoint PCM overflow')
            for i in range(2):
                pcm = (C.c_int16 * 8192)(); count = core.muxi_link_audio_read(i, pcm, 8192)
                if count < 0 or count > 8192 or count % 2: raise RuntimeError('Invalid per-endpoint PCM')
                endpoint_audio[i].extend(C.string_at(pcm, count * 2))
    @ENV
    def environment(command, data):
        if command in (9, 31): C.cast(data, C.POINTER(C.c_void_p))[0] = C.addressof(directory); return True
        if command == 15:
            var = C.cast(data, C.POINTER(Variable)).contents
            value = options.get(var.key.decode())
            var.value = C.addressof(value) if value is not None else None
            return value is not None
        if command == 17: C.cast(data, C.POINTER(C.c_bool))[0] = False; return True
        if command in (10, 16, 18, 34, 35, 37, 53, 54, 67, 68):
            negotiated.append(command); return True
        if command == 52: C.cast(data, C.POINTER(C.c_uint))[0] = 2; return True
        if command == (81 | 0x10000): C.cast(data, C.POINTER(C.c_uint))[0] = 48000; return True
        if command == (51 | 0x10000): return True
        return False
    @VIDEO
    def video(data, width, height, pitch):
        if data and width == 160 and height == 288 and pitch >= width * 4:
            pictures[:] = [b''.join(C.string_at(data + row * pitch, width * 4) for row in range(height))]
    @AUDIO
    def audio(left, right): pass
    @BATCH
    def batch(data, frames): samples[0] += frames; return frames
    @POLL
    def poll(): pass
    @INPUT
    def input_state(port, device, index, button):
        if port >= 2 or device != 1: return 0
        if button == 256: return C.c_int16(masks[port]).value
        return (masks[port] >> button) & 1 if button < 16 else 0
    for name, callback in [('retro_set_environment', environment), ('retro_set_video_refresh', video),
                           ('retro_set_audio_sample', audio), ('retro_set_audio_sample_batch', batch),
                           ('retro_set_input_poll', poll), ('retro_set_input_state', input_state)]:
        getattr(core, name).argtypes = [type(callback)]; getattr(core, name)(callback); keep.append(callback)
    core.retro_load_game_special.argtypes = [C.c_uint, C.POINTER(Game), C.c_size_t]
    core.retro_load_game_special.restype = C.c_bool
    core.retro_get_memory_data.argtypes = [C.c_uint]; core.retro_get_memory_data.restype = C.c_void_p
    core.retro_get_memory_size.argtypes = [C.c_uint]; core.retro_get_memory_size.restype = C.c_size_t
    core.retro_serialize_size.restype = C.c_size_t
    core.retro_serialize.argtypes = [C.c_void_p, C.c_size_t]; core.retro_serialize.restype = C.c_bool
    core.retro_unserialize.argtypes = [C.c_void_p, C.c_size_t]; core.retro_unserialize.restype = C.c_bool
    core.retro_init(); loaded = False
    try:
        roms = [diagnostic(0, 0x3c, color), diagnostic(1, 0xa7, color)]
        games = (Game * 2)()
        for i, rom in enumerate(roms):
            path = out / f'original-endpoint-{i + 1}.{"gbc" if color else "gb"}'; path.write_bytes(rom)
            data = C.create_string_buffer(rom); keep.append(data); games[i] = Game(str(path).encode(), C.addressof(data), len(rom), None)
        if not core.retro_load_game_special(0x101, games, 2): raise RuntimeError('Official two-ROM subsystem rejected original diagnostics')
        loaded = True
        if bridge and (core.muxi_link_abi() != 1 or not core.muxi_link_audio_attach()): raise RuntimeError('Native link bridge ABI refused')
        def read(which):
            memory_id = (0x100, 0x300)[which]; size = core.retro_get_memory_size(memory_id)
            pointer = core.retro_get_memory_data(memory_id)
            if size < 4 or not pointer: raise RuntimeError('Missing endpoint SRAM mapping')
            return C.string_at(pointer, size)
        for _ in range(100): step()
        exchanged = [list(read(i)[:4]) for i in range(2)]
        if exchanged[0] != [0xa7, 0, 1, 0x3c] or exchanged[1] != [0x3c, 0, 1, 0xa7]:
            raise RuntimeError('Serial challenge did not cross independent endpoints: ' + repr(exchanged))
        for port in range(2):
            masks[port] = 1 << 8
            for _ in range(3): step()
            masks[port] = 0
            for _ in range(3): step()
            expected = [1, 0] if port == 0 else [1, 1]
            if [read(i)[1] for i in range(2)] != expected: raise RuntimeError('Endpoint input isolation failed')
        size = core.retro_serialize_size(); state = C.create_string_buffer(size)
        if not core.retro_serialize(state, size): raise RuntimeError('Combined checkpoint refused')
        snapshot = [read(i) for i in range(2)]
        C.cast(core.retro_get_memory_data(0x100), C.POINTER(C.c_ubyte))[1] = 77
        C.cast(core.retro_get_memory_data(0x300), C.POINTER(C.c_ubyte))[1] = 88
        if not core.retro_unserialize(state, size): raise RuntimeError('Combined restore refused')
        if [read(i) for i in range(2)] != snapshot: raise RuntimeError('Combined restore crossed endpoint SRAM')
        for _ in range(3): step()
        if not pictures: raise RuntimeError('No two-endpoint video frame produced')
        from PIL import Image
        image = Image.frombytes('RGBA', (160, 288), pictures[0], 'raw', 'BGRA')
        image.save(out / 'serial-two-machines.png')
        result = {'sourceCommit': '90e2899574b7b63c59dce8a7276783426d52a068',
                  'librarySHA256': hashlib.sha256((out / 'sameboy_libretro.dll').read_bytes()).hexdigest(),
                  'platform': 'gbc' if color else 'gb', 'subsystem': 0x101, 'serialExchanged': exchanged,
                  'independentButtons': [read(i)[1] for i in range(2)], 'combinedStateBytes': size,
                  'memoryBytes': {hex(i): core.retro_get_memory_size(i) for i in [0, 1, 0x100, 0x201, 0x300, 0x401]},
                  'video': [160, 288], 'audioFrames': samples[0], 'audioIsMixed': not bridge,
                  'romSHA256': [hashlib.sha256(r).hexdigest() for r in roms], 'checksPassed': 8}
        if bridge:
            import array, struct
            counts = []
            for i in range(2):
                wave = array.array('h'); wave.frombytes(endpoint_audio[i]); mono = wave[::2]
                crossings = sum(a <= 0 < b for a, b in zip(mono, mono[1:]))
                counts.append(crossings)
                if crossings < 50: raise RuntimeError('Endpoint did not produce its own diagnostic tone')
                n = core.muxi_link_endpoint_state_size(i); raw_state = C.create_string_buffer(n)
                if not core.muxi_link_endpoint_state_save(i, raw_state, n): raise RuntimeError('Single-endpoint state export refused')
                sram = read(i); rtc_id = (0x201, 0x401)[i]
                rtc = C.string_at(core.retro_get_memory_data(rtc_id), core.retro_get_memory_size(rtc_id))
                mxs = struct.pack('>I', 0x4d585331) + b''.join(struct.pack('>I', len(b)) + b for b in [raw_state.raw, sram, rtc])
                (out / f'endpoint-{i + 1}.mxs1').write_bytes(mxs)
            if not 1.7 < counts[1] / counts[0] < 2.3: raise RuntimeError('Per-endpoint tones were mixed or duplicated')
            result['endpointAudioCrossings'] = counts
            result['endpointAudioSHA256'] = [hashlib.sha256(v).hexdigest() for v in endpoint_audio]
            result['checksPassed'] += 5
        (out / 'result.json').write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    finally:
        if loaded: core.retro_unload_game()
        core.retro_deinit()


def main():
    ap = argparse.ArgumentParser(); ap.add_argument('--output', type=Path); ap.add_argument('--color', action='store_true'); ap.add_argument('--native-child', type=Path)
    ap.add_argument('--library', type=Path)
    args = ap.parse_args()
    if args.native_child:
        native_probe(args.native_child.resolve(), args.color); return
    if args.output is None: ap.error('--output is required')
    out = args.output.resolve(); out.mkdir(parents=True, exist_ok=False)
    manifest = json.loads((ROOT / 'third-party/cores.json').read_text(encoding='utf-8'))['cores']
    rows = manifest.values() if isinstance(manifest, dict) else manifest
    pin = next(p for p in rows if 'sameboy' in p.get('source', '').lower())['platforms']['windows-x86_64']
    archive = ROOT.parent / pin['archive']
    if hashlib.sha256(archive.read_bytes()).hexdigest() != pin['archiveSha256']: raise RuntimeError('Pinned core archive changed')
    if args.library:
        library_source = args.library.resolve()
        report = json.loads((library_source.parent / 'build-report.json').read_text(encoding='utf-8'))
        data = library_source.read_bytes()
        if report['sourceCommit'] != '90e2899574b7b63c59dce8a7276783426d52a068' or hashlib.sha256(data).hexdigest() != report['library']['sha256']:
            raise RuntimeError('Exact-source link bridge changed')
    else:
        with zipfile.ZipFile(archive) as z:
            data = z.read(next(n for n in z.namelist() if Path(n).name == pin['libraryName']))
        if hashlib.sha256(data).hexdigest() != pin['librarySha256']: raise RuntimeError('Pinned core DLL changed')
    library = out / pin['libraryName']; library.write_bytes(data)
    command = [sys.executable, '-B', str(Path(__file__).resolve()), '--native-child', str(out)]
    if args.color: command.append('--color')
    result = subprocess.run(command, capture_output=True, text=True, encoding='utf-8', errors='replace')
    (out / 'native.log').write_text(result.stdout + result.stderr, encoding='utf-8')
    library.unlink()  # Child exited normally or with a reported failure; no retained native payload copy.
    if result.returncode: raise RuntimeError('Official link probe failed; see native.log')
    print('Official SameBoy serial link probe passed; child exit 0; native payload removed')


if __name__ == '__main__': main()
