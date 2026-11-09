"""Original ARM cable challenge, independent KEYINPUT/audio, no manufacturer ROM/BIOS bytes."""
from __future__ import annotations
import argparse
import ctypes
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import sys
from gba_diagnostic import Arm

def diagnostic(endpoint):
    arm=Arm();challenge=[0x3c,0xa7][endpoint]
    arm.literal(0,0x04000000);arm.literal(1,0x403);arm.halfword(1,0)
    arm.literal(0,0x04000020);arm.literal(1,0x100);arm.halfword(1,0);arm.halfword(1,0,6)
    arm.alu(13,1,0,0);arm.halfword(1,0,2);arm.halfword(1,0,4);arm.emit(0xE5801008);arm.emit(0xE580100C)
    arm.literal(8,0x02000000);arm.literal(14,0x0E000000);arm.alu(13,1,0,0);arm.emit(0xE5881000)
    for offset in range(3):arm.byte_store(1,14,offset)
    arm.alu(13,1,0,challenge);arm.byte_store(1,8,3);arm.byte_store(1,14,3)
    # Two genuinely different PSG frequencies, emitted by their separate hardware cores.
    arm.literal(0,0x04000084);arm.alu(13,1,0,0x80);arm.halfword(1,0)
    arm.literal(0,0x04000080);arm.literal(1,0x2277);arm.halfword(1,0)
    arm.literal(0,0x04000068);arm.literal(1,0xF080);arm.halfword(1,0)
    arm.literal(0,0x0400006C);arm.literal(1,0x8000|[1536,1792][endpoint]);arm.halfword(1,0)
    arm.literal(9,0x04000128)
    arm.literal(0,0x04000134);arm.alu(13,1,0,0);arm.halfword(1,0)
    arm.literal(1,0x2003);arm.halfword(1,9)  # Multiplayer, 115200 baud; both endpoints send a 16-bit word.
    arm.alu(13,1,0,challenge);arm.halfword(1,9,2)
    # Hardware multiplayer ID selects the clock master, independently of which
    # authored ROM happens to occupy the first physical cable port.
    arm.literal(10,0x04000006);arm.alu(13,11,0,4)
    arm.label('grace_top');arm.halfword(1,10,load=True);arm.alu(10,0,1,160,flags=True);arm.branch('grace_top',condition=2)
    arm.label('grace_blank');arm.halfword(1,10,load=True);arm.alu(10,0,1,160,flags=True);arm.branch('grace_blank',condition=3)
    arm.alu(2,11,11,1,flags=True);arm.branch('grace_top',condition=1)
    arm.halfword(5,9,load=True);arm.alu(0,5,5,0x30);arm.alu(10,0,5,0,flags=True);arm.branch('wait_start',condition=1)
    arm.literal(1,0x2083);arm.halfword(1,9);arm.branch('serial')
    arm.label('wait_start');arm.halfword(1,9,load=True);arm.alu(8,0,1,0x80,flags=True);arm.branch('wait_start',condition=0)
    arm.label('serial');arm.halfword(1,9,load=True);arm.alu(8,0,1,0x80,flags=True);arm.branch('serial',condition=1)
    arm.alu(10,0,5,0,flags=True);arm.literal(0,0x04000122,condition=0);arm.literal(0,0x04000120,condition=1)
    arm.halfword(1,0,load=True);arm.byte_store(1,8,0);arm.byte_store(1,14,0);arm.alu(13,1,0,1);arm.byte_store(1,8,2);arm.byte_store(1,14,2)
    # Draw once. Repainting all 38,400 pixels every poll took several emulated
    # frames and made this test ROM itself delay otherwise correct input.
    arm.literal(4,0x001F if endpoint==0 else 0x7C00);arm.literal(6,0x06000000);arm.literal(7,240*160)
    arm.label('paint');arm.halfword(4,6);arm.alu(4,6,6,2);arm.alu(2,7,7,1,flags=True);arm.branch('paint',condition=1)
    arm.literal(10,0x04000006);arm.literal(2,0x04000130);arm.alu(13,11,0,1);arm.alu(13,12,0,0)
    arm.label('frame_start');arm.halfword(1,10,load=True);arm.alu(10,0,1,160,flags=True);arm.branch('frame_start',condition=2)
    arm.label('frame_end');arm.halfword(1,10,load=True);arm.alu(10,0,1,160,flags=True);arm.branch('frame_end',condition=3)
    arm.halfword(3,2,load=True);arm.alu(0,3,3,1);arm.emit(0xE3530000);arm.branch('released',condition=1)
    arm.emit(0xE35B0000);arm.alu(4,12,12,1,condition=1);arm.alu(13,11,0,0);arm.branch('key_done')
    arm.label('released');arm.alu(13,11,0,1)
    arm.label('key_done');arm.byte_store(12,8,1);arm.byte_store(12,14,1)
    arm.branch('frame_start')
    code=arm.finish();rom=bytearray(65536);struct.pack_into('<I',rom,0,0xEA00003E)
    rom[0xA0:0xAC]=f'MX GBA LINK{endpoint}'.encode().ljust(12,b' ');rom[0xAC:0xB0]=b'MXLC';rom[0xB0:0xB2]=b'00';rom[0xB2]=0x96;rom[0xBD]=(-sum(rom[0xA0:0xBD])-0x19)&255;rom[0x100:0x100+len(code)]=code
    rom[0x2000:0x200a]=b'SRAM_V113\0'
    assert not any(rom[4:0xA0]);assert (sum(rom[0xA0:0xBE])+0x19)&255==0
    return bytes(rom)

def child(args):
    core=ctypes.CDLL(str(args.library.resolve()));core.muxi_gba_link_open.argtypes=[ctypes.c_char_p,ctypes.c_char_p];core.muxi_gba_link_open.restype=ctypes.c_int
    core.muxi_gba_link_step.argtypes=[ctypes.c_uint32,ctypes.c_uint32];core.muxi_gba_link_step.restype=ctypes.c_int
    core.muxi_gba_link_read.argtypes=[ctypes.c_uint,ctypes.c_uint32];core.muxi_gba_link_read.restype=ctypes.c_uint32
    core.muxi_gba_link_audio.argtypes=[ctypes.c_uint,ctypes.POINTER(ctypes.c_int16),ctypes.c_uint];core.muxi_gba_link_audio.restype=ctypes.c_uint
    core.muxi_gba_link_audio_rate.argtypes=[ctypes.c_uint];core.muxi_gba_link_audio_rate.restype=ctypes.c_uint
    paths=[args.output/f'endpoint-{i}.gba' for i in range(2)]
    if args.reverse:paths.reverse()
    assert core.muxi_gba_link_open(*(str(p.resolve()).encode() for p in paths))==1
    authors=[1,0]if args.reverse else[0,1]
    report={'librarySHA256':hashlib.sha256(args.library.read_bytes()).hexdigest(),'normalExit':False,'physicalPortAuthors':authors,'checks':[],'inputFrames':[]}
    pcm=(ctypes.c_int16*8192)()
    def drain(endpoint):
        n=core.muxi_gba_link_audio(endpoint,pcm,8192)
        return list(pcm[:n])
    try:
        for i in range(20):
            assert core.muxi_gba_link_step(0,0)==1,('step',i)
            drain(0);drain(1)
        words=[core.muxi_gba_link_read(i,0x02000000) for i in range(2)];report['wordsBefore']=words
        assert all(word&255==[0xa7,0x3c][author]for word,author in zip(words,authors)),words
        assert (words[0]>>16)&255==1 and (words[1]>>16)&255==1
        report['checks'].append('distinct multiplayer-16 serial challenges exchanged by actual GBA CPUs')
        samples=[[],[]]
        for i in range(3):
            assert core.muxi_gba_link_step(1,0)==1
            report['inputFrames'].append({'frameAfterPress':i+1,'words':[core.muxi_gba_link_read(p,0x02000000)for p in range(2)]})
            for endpoint in range(2):samples[endpoint].extend(drain(endpoint))
        after=[core.muxi_gba_link_read(i,0x02000000) for i in range(2)];report['wordsAfter']=after
        assert (after[0]>>8)&255==((words[0]>>8)&255)+1 and (after[1]>>8)&255==((words[1]>>8)&255),after
        report['checks'].append('own KEYINPUT edge changes only own machine')
        for i in range(5):
            assert core.muxi_gba_link_step(0,0)==1
            for endpoint in range(2):samples[endpoint].extend(drain(endpoint))
        rates=[core.muxi_gba_link_audio_rate(i)for i in range(2)];frequencies=[]
        for endpoint in range(2):
            left=samples[endpoint][::2];assert left and max(left)>min(left)
            mean=sum(left)/len(left);crossings=sum(a<=mean<b for a,b in zip(left,left[1:]));frequencies.append(crossings*rates[endpoint]/len(left))
        report.update(audioSamples=list(map(len,samples)),audioRates=rates,audioFrequencies=frequencies)
        assert all(([220,450][author]<frequency<[290,570][author])for frequency,author in zip(frequencies,authors)),frequencies
        report['checks'].append('independent hardware PSG tones measured near 256 and 512 Hz')
    finally:core.muxi_gba_link_close()
    report['normalExit']=True;(args.output/'result.json').write_text(json.dumps(report,indent=2),encoding='utf-8');print(json.dumps(report))

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--library',type=Path,required=True);parser.add_argument('--output',type=Path,required=True);parser.add_argument('--child',action='store_true');parser.add_argument('--reverse',action='store_true');args=parser.parse_args()
    if args.child:return child(args)
    args.output.mkdir(parents=True,exist_ok=False)
    for i in range(2):(args.output/f'endpoint-{i}.gba').write_bytes(diagnostic(i))
    result=subprocess.run([sys.executable,'-B',__file__,'--child','--library',str(args.library.resolve()),'--output',str(args.output.resolve()),*(['--reverse']if args.reverse else[])],capture_output=True,text=True,timeout=120)
    (args.output/'probe.log').write_text(result.stdout+result.stderr,encoding='utf-8');print('native child exit',result.returncode)
    if result.returncode:print((result.stdout+result.stderr)[-4000:])
    else:print((args.output/'result.json').read_text(encoding='utf-8'))
    raise SystemExit(result.returncode)

if __name__=='__main__':main()
