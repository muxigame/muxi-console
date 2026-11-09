"""Original LR35902 serial-link diagnostic; no vendor logo, game, font or BIOS bytes.

SRAM bytes: peer challenge, independent A press count, serial completion, own challenge.
Rows of eight tiles visualize own challenge / peer challenge / own A press count.
Each endpoint produces a different square-wave frequency for independent-audio tests.
"""
from __future__ import annotations


class Assembler:
    def __init__(self):
        self.data = bytearray()
        self.labels = {}
        self.fixups = []

    def emit(self, *values): self.data.extend(values)
    def label(self, name): self.labels[name] = len(self.data) + 0x150
    def branch(self, opcode, label):
        self.emit(opcode, 0)
        self.fixups.append((len(self.data) - 1, label, True))
    def absolute(self, opcode, label):
        self.emit(opcode, 0, 0)
        self.fixups.append((len(self.data) - 2, label, False))
    def write(self, address, value): self.emit(0x3e, value, 0xea, address & 255, address >> 8)
    def finish(self):
        for at, label, relative in self.fixups:
            value = self.labels[label]
            if relative:
                value -= 0x150 + at + 1
                if not -128 <= value <= 127: raise ValueError("Diagnostic branch out of range")
                self.data[at] = value & 255
            else:
                self.data[at:at + 2] = value.to_bytes(2, 'little')
        return bytes(self.data)


def boot(color=False):
    """Diagnostic-only entry mapping, not a general-purpose replacement firmware."""
    result = bytearray(2304 if color else 256)
    result[:7] = b'\xf3\x31\xfe\xff\xc3\xfc\x00'
    result[0xfc:0x100] = b'\x3e\x01\xe0\x50'
    return bytes(result)


def diagnostic(endpoint, challenge, color=False):
    if endpoint not in (0, 1) or not 0 <= challenge <= 255: raise ValueError("Invalid original diagnostic endpoint")
    c = Assembler()
    c.emit(0xf3, 0x31, 0xfe, 0xff)  # DI; stack
    c.write(0xff40, 0)
    c.write(0x0000, 0x0a)  # Enable our own battery-backed RAM.
    for addr in (0xc000, 0xc001, 0xc002, 0xa000, 0xa001, 0xa002): c.write(addr, 0)
    c.write(0xc010, challenge); c.write(0xa003, challenge)
    c.emit(0x21, 0, 0x80, 0x06, 16)
    c.label('clear_tile'); c.emit(0xaf, 0x22, 0x05); c.branch(0x20, 'clear_tile')
    c.emit(0x06, 16)
    c.label('solid_tile'); c.emit(0x3e, 0xff, 0x22, 0x05); c.branch(0x20, 'solid_tile')
    c.emit(0x21, 0, 0x98, 0x01, 0, 4)
    c.label('clear_map'); c.emit(0xaf, 0x22, 0x0b, 0x78, 0xb1); c.branch(0x20, 'clear_map')
    c.write(0xff47, 0xe4)
    if color:
        c.write(0xff68, 0x80)
        for b in (0xff, 0x7f, 0, 0, 0, 0, 0, 0): c.write(0xff69, b)
    for address, value in ((0xff26, 0x80), (0xff24, 0x77), (0xff25, 0x22), (0xff16, 0x80),
                           (0xff17, 0xa0), (0xff18, 0 if endpoint == 0 else 0x80), (0xff19, 0x87)):
        c.write(address, value)
    c.write(0xff40, 0x91)
    c.emit(0x06, 20 if endpoint == 0 else 2)
    c.label('wait_start'); c.absolute(0xcd, 'frame'); c.emit(0x05); c.branch(0x20, 'wait_start')
    c.write(0xff01, challenge)
    c.write(0xff02, 0x81 if endpoint == 0 else 0x80)
    c.label('serial_wait'); c.emit(0xf0, 2, 0xe6, 0x80); c.branch(0x20, 'serial_wait')
    c.emit(0xf0, 1, 0xea, 0, 0xc0, 0xea, 0, 0xa0)
    c.write(0xa002, 1)
    c.label('main')
    c.absolute(0xcd, 'frame')
    for address, row in ((0xc010, 0x9800), (0xc000, 0x9840), (0xc001, 0x9880)):
        c.emit(0xfa, address & 255, address >> 8, 0x21, row & 255, row >> 8)
        c.absolute(0xcd, 'draw')
    c.write(0xff00, 0x10)
    c.emit(0xf0, 0, 0xf0, 0, 0xe6, 1); c.branch(0x20, 'main')
    c.emit(0xfa, 1, 0xc0, 0x3c, 0xea, 1, 0xc0, 0xea, 1, 0xa0)
    c.label('release'); c.absolute(0xcd, 'frame'); c.emit(0xf0, 0, 0xe6, 1); c.branch(0x28, 'release')
    c.absolute(0xc3, 'main')
    c.label('frame')
    c.label('not_vblank'); c.emit(0xf0, 0x44, 0xfe, 144); c.branch(0x30, 'not_vblank')
    c.label('vblank'); c.emit(0xf0, 0x44, 0xfe, 144); c.branch(0x38, 'vblank'); c.emit(0xc9)
    c.label('draw'); c.emit(0x4f, 0x06, 8)
    c.label('draw_bit'); c.emit(0xcb, 0x21, 0x3e, 0, 0xce, 0, 0x22, 0x05); c.branch(0x20, 'draw_bit'); c.emit(0xc9)
    code = c.finish()
    rom = bytearray(32768); rom[0x100:0x103] = b'\xc3\x50\x01'
    rom[0x134:0x143] = (f'MUXI LINK {endpoint + 1}'.encode()).ljust(15, b'\0')
    rom[0x143] = 0x80 if color else 0
    rom[0x147:0x14b] = bytes((0x10, 0, 2, 1))  # MBC3 + RAM + timer + battery; 8 KiB RAM.
    rom[0x14d] = (-sum(rom[0x134:0x14d]) - 25) & 255
    rom[0x150:0x150 + len(code)] = code
    return bytes(rom)
