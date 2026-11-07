"""Original hardware-register diagnostics, without vendor boot code or assets.

Each function returns a complete ROM image authored here from public CPU/video
register documentation. These are diagnostics, not game compatibility samples.
"""
from __future__ import annotations
import struct


class Tiny6502:
    def __init__(self, origin=0x8000):
        self.origin=origin; self.data=bytearray(); self.labels={}; self.fixups=[]
    def emit(self,*values): self.data.extend(values)
    def label(self,name): self.labels[name]=len(self.data)
    def branch(self,op,name): self.emit(op,0); self.fixups.append((len(self.data)-1,name,True))
    def jump(self,name): self.emit(0x4c,0,0); self.fixups.append((len(self.data)-2,name,False))
    def finish(self):
        for at,name,relative in self.fixups:
            address=self.labels[name]-at-1 if relative else self.labels[name]+self.origin
            if relative:
                if not -128<=address<=127: raise ValueError('Diagnostic branch range')
                self.data[at]=address&255
            else: self.data[at:at+2]=address.to_bytes(2,'little')
        return bytes(self.data)


def snes_diagnostic():
    """LoROM; upper half red, lower black. P1 A changes upper half to green."""
    c=Tiny6502()
    def write(address,value): c.emit(0xa9,value,0x8d,address&255,address>>8)
    c.emit(0x78,0xd8,0xa2,0xff,0x9a)
    write(0x2100,0x80)
    for address in (0x4200,0x420b,0x420c,0x2105,0x2106,0x2107,0x2108,0x2109,0x210a,
                    0x210c,0x212c,0x212d,0x2130,0x2131,0x2133): write(address,0)
    write(0x210b,1); write(0x2115,0x80)
    for address in (0x210d,0x210e): write(address,0); write(address,0)
    # Word-addressed tile data. Eight words each for empty/solid 2bpp tiles.
    write(0x2116,0); write(0x2117,0x10)
    for value in (0,255):
        for _ in range(8): write(0x2118,value); write(0x2119,0)
    # 32x32 tilemap, fourteen solid rows followed by empty rows.
    write(0x2116,0); write(0x2117,0)
    c.emit(0xa0,0)
    c.label('row'); c.emit(0xa2,32,0x98,0xc9,14,0xa9,0); c.branch(0xb0,'map')
    c.emit(0xa9,1); c.label('map'); c.emit(0x8d,0x18,0x21,0x9c,0x19,0x21,0xca); c.branch(0xd0,'map')
    c.emit(0xc8,0xc0,32); c.branch(0xd0,'row')
    write(0x2121,0); write(0x2122,0); write(0x2122,0)
    write(0x2121,1); write(0x2122,31); write(0x2122,0)
    write(0x212c,1); write(0x4200,1); write(0x2100,15)
    c.label('frame'); c.emit(0xad,0x12,0x42); c.branch(0x10,'frame')
    # Auto joypad read finishes shortly after vblank begins.
    c.label('joy'); c.emit(0xad,0x12,0x42,0x29,1); c.branch(0xd0,'joy')
    write(0x2121,1)
    c.emit(0xad,0x18,0x42,0x29,0x80); c.branch(0xd0,'green')
    write(0x2122,31); write(0x2122,0); c.jump('end')
    c.label('green'); write(0x2122,0xe0); write(0x2122,3)
    c.label('end'); c.emit(0xad,0x12,0x42); c.branch(0x30,'end'); c.jump('frame')
    rom=bytearray([0xea]*32768); code=c.finish(); rom[:len(code)]=code
    rom[0x7fc0:0x7fd5]=b'MUXI SNES IO DIAG    '
    rom[0x7fd5:0x7fdc]=bytes([0x20,0,5,0,1,0,0])
    rom[0x7fdc:0x7fe0]=b'\xff\xff\x00\x00'
    for at in range(0x7fe0,0x8000,2): rom[at:at+2]=b'\x00\x80'
    return bytes(rom)


class Tiny68000:
    def __init__(self): self.data=bytearray(); self.labels={}; self.fixups=[]
    def word(self,*values):
        for value in values: self.data.extend(struct.pack('>H',value&65535))
    def long(self,value): self.data.extend(struct.pack('>I',value&0xffffffff))
    def writew(self,address,value): self.word(0x33fc,value); self.long(address)
    def writel(self,address,value): self.word(0x23fc); self.long(value); self.long(address)
    def label(self,name): self.labels[name]=len(self.data)
    def branch(self,opcode,name): self.word(opcode,0); self.fixups.append((len(self.data)-2,name))
    def finish(self):
        for at,name in self.fixups:
            value=self.labels[name]-at
            if not -32768<=value<=32767: raise ValueError('68000 branch range')
            self.data[at:at+2]=struct.pack('>h',value)
        return bytes(self.data)


def md_diagnostic():
    """68000 + VDP; red tile field at upper half, black below, no firmware."""
    c=Tiny68000(); ctrl=0xc00004; data=0xc00000
    c.word(0x46fc,0x2700)  # move.w #$2700,sr
    for value in (0x8004,0x8104,0x8230,0x832c,0x8407,0x856c,0x8700,0x8a00,0x8b00,0x8c00,0x8d3f,0x8f02,0x9000,0x9100,0x9200): c.writew(ctrl,value)
    # Clear all VRAM using a counted word loop.
    c.writel(ctrl,0x40000000); c.word(0x303c,32767)
    c.label('clear'); c.writew(data,0); c.branch(0x51c8,'clear')
    # Tile 1 is one solid palette index, eight 32-bit rows.
    c.writel(ctrl,0x40200000)
    for _ in range(8): c.writel(data,0x11111111)
    # Plane A at C000, first fourteen rows of 32 tiles use tile 1.
    c.writel(ctrl,0x40000003); c.word(0x303c,14*32-1)
    c.label('map'); c.writew(data,1); c.branch(0x51c8,'map')
    c.writel(ctrl,0xc0000000); c.writew(data,0); c.writew(data,0x000e)
    c.writew(ctrl,0x8144)
    # P1 TH output selects the A/Start half of the standard three-button pad.
    c.word(0x13fc,0x40); c.long(0xa10009)
    c.word(0x13fc,0x00); c.long(0xa10003)
    c.label('hold'); c.word(0x1039); c.long(0xa10003)
    c.word(0x0200,0x10); c.branch(0x6700,'pressed')
    c.writel(ctrl,0xc0020000); c.writew(data,0x000e); c.branch(0x6000,'hold')
    c.label('pressed'); c.writel(ctrl,0xc0020000); c.writew(data,0x00e0); c.branch(0x6000,'hold')
    rom=bytearray(65536)
    struct.pack_into('>II',rom,0,0x00fffe00,0x200)
    for at in range(8,256,4): struct.pack_into('>I',rom,at,0x200)
    rom[0x100:0x110]=b'SEGA MEGA DRIVE '
    rom[0x120:0x150]=b'MUXI ORIGINAL VDP DIAGNOSTIC'.ljust(48,b' ')
    rom[0x150:0x180]=rom[0x120:0x150]
    rom[0x180:0x18e]=b'GM MUXIDIAG-00'
    rom[0x190:0x1a0]=b'J'.ljust(16,b' ')
    struct.pack_into('>II',rom,0x1a0,0,len(rom)-1)
    struct.pack_into('>II',rom,0x1a8,0xff0000,0xffffff)
    rom[0x1f0:0x200]=b'JUE'.ljust(16,b' ')
    code=c.finish(); rom[0x200:0x200+len(code)]=code
    checksum=sum(struct.unpack('>'+str((len(rom)-0x200)//2)+'H',rom[0x200:]))&65535
    struct.pack_into('>H',rom,0x18e,checksum)
    return bytes(rom)


class TinyZ80:
    def __init__(self): self.data=bytearray(); self.labels={}; self.fixups=[]
    def emit(self,*values): self.data.extend(values)
    def label(self,name): self.labels[name]=len(self.data)
    def branch(self,opcode,name): self.emit(opcode,0); self.fixups.append((len(self.data)-1,name))
    def out(self,port,value): self.emit(0x3e,value,0xd3,port)
    def finish(self):
        for at,name in self.fixups:
            value=self.labels[name]-at-1
            if not -128<=value<=127: raise ValueError('Z80 branch range')
            self.data[at]=value&255
        return bytes(self.data)


def sega8_diagnostic(kind='sms'):
    """Z80 VDP tile diagnostic for SMS, GG or SG-1000: red top, black bottom."""
    if kind not in ('sms','gg','sg1000'): raise ValueError(kind)
    c=TinyZ80(); c.emit(0xf3,0x31,0xf0,0xdf)
    def address(value,cram=False): c.out(0xbf,value&255); c.out(0xbf,(value>>8)|(0xc0 if cram else 0x40))
    def register(index,value): c.out(0xbf,value); c.out(0xbf,0x80|index)
    register(1,0x80)
    address(0); c.emit(1,0,0x40)
    c.label('clear'); c.emit(0xaf,0xd3,0xbe,0x0b,0x78,0xb1); c.branch(0x20,'clear')
    values=(0,0x80,0x0e,0x80,0,0x7e,0,1) if kind=='sg1000' else (6,0x80,0x0e,0,0,0x7e,0,0,0,0,0xff)
    for index,value in enumerate(values): register(index,value)
    address(8 if kind=='sg1000' else 32)
    for _ in range(8):
        c.out(0xbe,255)
        if kind!='sg1000':
            for _ in range(3): c.out(0xbe,0)
    if kind=='sg1000': address(0x2000); c.out(0xbe,0x61)
    else:
        address(0,True)
        for _ in range(64 if kind=='gg' else 32): c.out(0xbe,0)
        address(2 if kind=='gg' else 1,True); c.out(0xbe,15 if kind=='gg' else 3)
        if kind=='gg': c.out(0xbe,0)
    address(0x3800)
    for row in range(24):
        for _ in range(32):
            c.out(0xbe,1 if row<12 else 0)
            if kind!='sg1000': c.out(0xbe,0)
    address(0x3f00); c.out(0xbe,208)  # End sprite list before its first object.
    register(1,0xc0)
    c.label('hold');c.emit(0xdb,0xdc,0xe6,0x10);c.branch(0x20,'up')
    if kind=='sg1000':address(0x2000);c.out(0xbe,0x31)
    else:
        address(2 if kind=='gg' else 1,True);c.out(0xbe,0xf0 if kind=='gg' else 0x0c)
        if kind=='gg':c.out(0xbe,0)
    c.branch(0x18,'hold')
    c.label('up')
    if kind=='sg1000':address(0x2000);c.out(0xbe,0x61)
    else:
        address(2 if kind=='gg' else 1,True);c.out(0xbe,15 if kind=='gg' else 3)
        if kind=='gg':c.out(0xbe,0)
    c.branch(0x18,'hold')
    code=c.finish(); rom=bytearray(32768); rom[:len(code)]=code
    if kind!='sg1000':
        rom[0x7ff0:0x7ff8]=b'TMR SEGA'  # Standard ROM-header signature, not an asset.
        rom[0x7fff]=0x7c if kind=='gg' else 0x4c
    return bytes(rom)


def a2600_diagnostic():
    """4 KiB 6502/TIA diagnostic, 262 lines with distinct playfield/background."""
    c=Tiny6502(0xf000);c.emit(0x78,0xd8,0xa2,0xff,0x9a)
    for address in (0x04,0x05,0x06,0x07,0x0a,0x1b,0x1c,0x1d,0x1e,0x1f):
        c.emit(0xa9,0,0x85,address)
    c.emit(0xa9,0xf0,0x85,0x0d,0xa9,0,0x85,0x0e,0xa9,0xff,0x85,0x0f,0xa9,0xc6,0x85,0x08)
    c.label('frame');c.emit(0xa9,2,0x85,1,0x85,0,0xa2,3)
    c.label('sync');c.emit(0x85,2,0xca);c.branch(0xd0,'sync')
    c.emit(0xa9,0,0x85,0,0xa2,37)
    c.label('blank');c.emit(0x85,2,0xca);c.branch(0xd0,'blank')
    c.emit(0xa9,0,0x85,1,0xa5,0x0c,0x29,0x80);c.branch(0xf0,'pressed')
    c.emit(0xa9,0x46);c.jump('color')
    c.label('pressed');c.emit(0xa9,0x86)
    c.label('color');c.emit(0x85,9,0xa2,192)
    c.label('visible');c.emit(0x85,2,0xca);c.branch(0xd0,'visible')
    c.emit(0xa9,2,0x85,1,0xa2,30)
    c.label('overscan');c.emit(0x85,2,0xca);c.branch(0xd0,'overscan');c.jump('frame')
    rom=bytearray(4096);code=c.finish();rom[:len(code)]=code;rom[-6:]=b'\x00\xf0'*3
    return bytes(rom)


def gb_diagnostic(color=False):
    """LR35902 tile pattern. Header logo is zero; all game code is original."""
    c=TinyZ80();c.emit(0xf3,0x31,0xfe,0xff,0xaf,0xe0,0x40)
    # LCD is disabled, so tile data and map can be written without PPU contention.
    c.emit(0x21,0,0x80,0x06,16,0xaf)
    c.label('empty');c.emit(0x22,0x05);c.branch(0x20,'empty')
    c.emit(0x06,8)
    c.label('solid');c.emit(0x3e,255,0x22,0xaf,0x22,0x05);c.branch(0x20,'solid')
    c.emit(0x21,0,0x98)
    for row in range(32):
        c.emit(0x3e,1 if row<9 else 0,0x06,32)
        label=f'row{row}';c.label(label);c.emit(0x22,0x05);c.branch(0x20,label)
    c.emit(0x3e,0,0xe0,0x42,0xe0,0x43,0x3e,0xe4,0xe0,0x47)
    if color:
        c.emit(0x3e,0x80,0xe0,0x68)
        for value in (0,0,31,0,0,0,0,0):c.emit(0x3e,value,0xe0,0x69)
    c.emit(0x3e,0x91,0xe0,0x40)
    c.label('loop');c.emit(0xf0,0x44,0xfe,144);c.branch(0x38,'loop')
    c.emit(0x3e,0x10,0xe0,0,0xf0,0,0xe6,1);c.branch(0x28,'pressed')
    if color:
        c.emit(0x3e,0x82,0xe0,0x68,0x3e,31,0xe0,0x69,0xaf,0xe0,0x69)
    else:c.emit(0x3e,0xe4,0xe0,0x47)
    c.branch(0x18,'end')
    c.label('pressed')
    if color:
        c.emit(0x3e,0x82,0xe0,0x68,0x3e,0xe0,0xe0,0x69,0x3e,3,0xe0,0x69)
    else:c.emit(0x3e,0xec,0xe0,0x47)
    c.label('end');c.emit(0xf0,0x44,0xfe,144);c.branch(0x30,'end');c.branch(0x18,'loop')
    code=c.finish();rom=bytearray(32768);rom[0x100:0x104]=b'\x00\xc3\x50\x01'
    rom[0x134:0x143]=b'MUXI GB IO DIAG'[:15].ljust(15,b' ')
    rom[0x143]=0x80 if color else 0;rom[0x147]=0;rom[0x148]=0;rom[0x149]=0
    rom[0x14a]=1;rom[0x14d]=(-sum(rom[0x134:0x14d])-25)&255
    rom[0x150:0x150+len(code)]=code
    return bytes(rom)


def gb_diagnostic_boot(color=False):
    """Original diagnostic-only boot mapping; never a vendor BIOS replacement."""
    boot=bytearray(2304 if color else 256)
    boot[:7]=b'\xf3\x31\xfe\xff\xc3\xfc\x00'
    boot[0xfc:0x100]=b'\x3e\x01\xe0\x50'
    return bytes(boot)


def ws_diagnostic(color=False):
    """8086 tiled LCD diagnostic, monochrome WS or true WSC RGB palette mode."""
    c=bytearray(b'\xfa\xfc\x31\xc0\x8e\xd8\x8e\xc0\x8e\xd0\xbc\x00\x40')
    def emit(*values):c.extend(values)
    def port(address,value):emit(0xb0,value,0xe6,address)
    def memword(address,value):emit(0xc7,0x06,address&255,address>>8,value&255,value>>8)
    port(0,0);port(0x14,1);port(0x60,0x80 if color else 0)
    # Clear map and tile memory, then one 2bpp tile uses palette color one.
    emit(0x31,0xc0,0xbf,0,0x10,0xb9,0,0x10,0xf3,0xab)
    emit(0xb8,0xff,0,0xbf,0x10,0x20,0xb9,8,0,0xf3,0xab)
    emit(0xb8,1,0,0xbf,0,0x10,0xb9,0x20,1,0xf3,0xab)
    if color:memword(0xfe00,0);memword(0xfe02,0x0f00)
    else:
        port(0x1c,0xf0);port(0x1d,0xf8);port(0x1e,0xff);port(0x1f,0xff)
        port(0x20,0x10);port(0x21,0)
    port(7,2);port(0x10,0);port(0x11,0);port(0,1)
    loop=len(c);port(0xb5,0x40);emit(0xe4,0xb5,0xa8,4,0x74,0);unpressed=len(c)-1
    if color:memword(0xfe02,0x00f0)
    else:port(0x20,0x20)
    emit(0xeb,0);end=len(c)-1
    c[unpressed]=(len(c)-unpressed-1)&255
    if color:memword(0xfe02,0x0f00)
    else:port(0x20,0x10)
    c[end]=(len(c)-end-1)&255
    emit(0xe9);c.extend(struct.pack('<h',loop-len(c)-2))
    rom=bytearray(131072);rom[-65536:-65536+len(c)]=c
    rom[-16:-11]=b'\xea\x00\x00\x00\xf0'
    rom[-10:-2]=bytes([0,1 if color else 0,0,0,0,0,0,0])
    rom[-2:]=(sum(rom[:-2])&65535).to_bytes(2,'little')
    return bytes(rom)


def pce_diagnostic():
    """HuC6280 + HuC6270 planar tile diagnostic, red upper half and black below."""
    c=Tiny6502(0xe000)
    c.emit(0x78,0xd8,0xd4,0xa9,0xff,0x53,1,0xa9,0xf8,0x53,2,0xa2,0xff,0x9a)
    def write(address,value):c.emit(0xa9,value,0x8d,address&255,address>>8)
    def reg(index,value):write(0,index);write(2,value&255);write(3,value>>8)
    for index,value in ((5,0),(7,0),(8,0),(9,0),(10,0x0202),(11,0x041f),(12,0x0d07),(13,223),(14,3),(15,0)):
        reg(index,value)
    write(0x400,0);write(0x402,0);write(0x403,0)
    write(0x404,0);write(0x405,0);write(0x404,0x38);write(0x405,0)
    reg(0,0);write(0,2);c.emit(0xa0,32,0xa2,0,0xa9,0)
    c.label('clear');c.emit(0x8d,2,0,0x8d,3,0,0xca);c.branch(0xd0,'clear');c.emit(0x88);c.branch(0xd0,'clear')
    # Tile #65 at word $410. Plane 0 solid, planes 1/2/3 empty.
    reg(0,0x410);write(0,2)
    for value in (255,0):
        for _ in range(8):write(2,value);write(3,0)
    reg(0,0);write(0,2);c.emit(0xa0,0)
    c.label('row');c.emit(0xa2,32,0x98,0xc9,14,0xa9,64);c.branch(0xb0,'map');c.emit(0xa9,65)
    c.label('map');c.emit(0x8d,2,0,0x9c,3,0,0xca);c.branch(0xd0,'map');c.emit(0xc8,0xc0,32);c.branch(0xd0,'row')
    reg(5,0x80)
    c.label('hold');write(0x1000,2);write(0x1000,0);c.emit(0xea,0xea,0xea,0xad,0,0x10,0x29,1);c.branch(0xf0,'pressed')
    write(0x402,1);write(0x403,0);write(0x404,0x38);write(0x405,0);c.jump('hold')
    c.label('pressed');write(0x402,1);write(0x403,0);write(0x404,0xc0);write(0x405,1);c.jump('hold')
    code=c.finish();rom=bytearray(32768);rom[:len(code)]=code
    for bank in range(4):rom[(bank+1)*8192-8:(bank+1)*8192]=b'\x00\xe0'*4
    return bytes(rom)


def a7800_diagnostic():
    """6502 + MARIA raster background diagnostic, no manufacturer BIOS."""
    c=Tiny6502(0x8000);c.emit(0x78,0xd8,0xa2,0xff,0x9a)
    def write(address,value):c.emit(0xa9,value,0x8d,address&255,address>>8)
    write(1,7);write(0x2c,0x81);write(0x30,0);write(0x3c,0x40)
    c.label('frame');c.emit(0xad,0x28,0);c.branch(0x30,'frame')
    write(0x20,0x46);c.emit(0xa2,100)
    c.label('upper');c.emit(0x8d,0x24,0,0xca);c.branch(0xd0,'upper')
    write(0x20,0x86);c.emit(0xa2,100)
    c.label('lower');c.emit(0x8d,0x24,0,0xca);c.branch(0xd0,'lower')
    c.label('blank');c.emit(0xad,0x28,0);c.branch(0x10,'blank');c.jump('frame')
    code=c.finish();rom=bytearray(32768);rom[:len(code)]=code
    for zone in range(24):rom[0x100+zone*3:0x103+zone*3]=bytes([15,0x83,0])
    rom[0x300:0x302]=b'\x00\x00';rom[-6:]=b'\x00\x80'*3
    header=bytearray(128);header[0]=4;header[1:17]=b'ATARI7800       '
    header[17:49]=b'MUXI ORIGINAL MARIA RASTER'.ljust(32,b' ')
    header[49:53]=len(rom).to_bytes(4,'big');header[55:57]=b'\x01\x01'
    header[100:128]=b'ACTUAL CART DATA STARTS HERE'.ljust(28,b'\x00')
    return bytes(header+rom)


def ngp_diagnostic(color=False):
    """Original TLCS-900/H memory-immediate program and K2GE/K2GE tile data."""
    c=bytearray([6,7])
    def write(address,value):c.extend((0xf1,address&255,address>>8,0,value))
    for address,value in ((0x8000,0),(0x8002,0),(0x8003,0),(0x8004,160),(0x8005,152),(0x8006,198),
                          (0x8012,0),(0x8020,0),(0x8021,0),(0x8030,0),(0x8032,0),(0x8033,0),(0x8034,0),(0x8035,0),
                          (0x87e2,0 if color else 0x80),(0x8118,0x87),(0x8109,0)):
        write(address,value)
    for address in range(0x8200,0x8400):write(address,0)
    for address in range(0xa000,0xa020):write(address,0x55 if address>=0xa010 else 0)
    for row in range(32):
        for column in range(32):
            address=0x9000+(row*32+column)*2
            write(address,1 if row<9 else 0);write(address+1,0)
            write(address+0x800,0);write(address+0x801,0)
    if color:write(0x8282,0x0f);write(0x8283,0)
    loop=0x200100+len(c);c.append(0x1b);c.extend(loop.to_bytes(3,'little'))
    rom=bytearray(32768);rom[:28]=b'MUXI ORIGINAL DIAGNOSTIC'.ljust(28,b' ')
    rom[0x1c:0x20]=(0x200100).to_bytes(4,'little');rom[0x23]=0x10 if color else 0
    rom[0x24:0x30]=b'MUXI K2 TEST';rom[0x100:0x100+len(c)]=c
    return bytes(rom)


def vb_diagnostic():
    """Original V810 program writes both eyes' two VIP framebuffers directly."""
    c=bytearray()
    def instruction(op,reg1=0,reg2=0,immediate=None):
        c.extend(struct.pack('<H',(op<<10)|(reg2<<5)|reg1))
        if immediate is not None:c.extend(struct.pack('<H',immediate&65535))
    def address(register,value):
        instruction(0x2f,0,register,value>>16);instruction(0x2c,register,register,value&65535)
    def writehalf(offset,value):
        instruction(0x28,0,2,value);instruction(0x35,1,2,offset)
    address(1,0x0005f800)
    for offset,value in ((2,0),(0x22,1),(0x40,0),(0x24,64),(0x26,64),(0x28,64),(0x2a,0),(0x2e,0)):
        writehalf(offset,value)
    instruction(0x10,31,2)  # mov -1,r2: packed pixel value 3.
    instruction(0,0,3)
    for base in (0,0x8000,0x10000,0x18000):
        address(1,base)
        for column in range(384):
            for word in range(16):instruction(0x37,1,2 if word<7 else 3,column*64+word*4)
    address(1,0x0005f800);writehalf(0x22,2)
    # Relative branch with true condition and zero displacement is a stable loop.
    c.extend(struct.pack('<H',0x8a00))
    rom=bytearray(131072);rom[:len(c)]=c
    reset=struct.pack('<HHH',0xbc20,0x0700,0x1801)
    rom[-16:-16+len(reset)]=reset
    rom[-0x220:-0x20c]=b'MUXI ORIGINAL VIP   '
    return bytes(rom)


def lynx_diagnostic():
    """Original unencrypted BS93 homebrew object, 65C02 + Mikey LCD DMA."""
    c=Tiny6502(0x20a);c.emit(0x78,0xd8,0xa2,0xff,0x9a)
    def write(address,value):c.emit(0xa9,value,0x8d,address&255,address>>8)
    write(0xfd94,0);write(0xfd95,0x30)
    write(0xfda0,0);write(0xfdb0,0);write(0xfda1,0);write(0xfdb1,15)
    write(0xfd92,0x0d)
    c.label('hold');c.jump('hold')
    payload=bytearray(0x3000-0x200+8160)
    payload[:10]=b'\x80\x08'+(0x20a).to_bytes(2,'big')+len(payload).to_bytes(2,'big')+b'BS93'
    code=c.finish();payload[10:10+len(code)]=code
    payload[0x2e00:0x2e00+4080]=b'\x11'*4080
    return bytes(payload)


def lynx_diagnostic_firmware():
    """512 original bytes required by the core; BS93 bypasses the cart loader.

    This is a diagnostic stub, not a general encrypted LNX boot ROM.
    """
    result=bytearray([0xea]*512);result[:3]=b'\x4c\x00\x02';result[-6:]=b'\x00\xfe'*3
    return bytes(result)


GENERATORS={'snes':snes_diagnostic,'md':md_diagnostic,
            'sms':lambda:sega8_diagnostic('sms'),'gg':lambda:sega8_diagnostic('gg'),
            'sg1000':lambda:sega8_diagnostic('sg1000'),'a2600':a2600_diagnostic,
            'gb':lambda:gb_diagnostic(False),'gbc':lambda:gb_diagnostic(True),
            'ws':lambda:ws_diagnostic(False),'wsc':lambda:ws_diagnostic(True),'pce':pce_diagnostic,
            'a7800':a7800_diagnostic,'ngp':lambda:ngp_diagnostic(False),'ngpc':lambda:ngp_diagnostic(True),
            'vb':vb_diagnostic,'lynx':lynx_diagnostic}
