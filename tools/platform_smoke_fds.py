"""Original FDS diagnostic disk plus original diagnostic-only firmware.

The firmware reads our disk payload through the FDS byte-transfer registers,
loads it into RAM and executes it. It contains no original manufacturer BIOS,
logo, boot routine or commercial game. It is not a general FDS BIOS replacement.
"""
from __future__ import annotations


class Code6502:
    def __init__(self, origin):
        self.origin = origin; self.code = bytearray(); self.labels = {}; self.fixups = []
    def emit(self, *values): self.code.extend(values)
    def label(self, name): self.labels[name] = len(self.code)
    def address(self, op, label):
        self.emit(op, 0, 0); self.fixups.append((len(self.code) - 2, label, False))
    def branch(self, op, label):
        self.emit(op, 0); self.fixups.append((len(self.code) - 1, label, True))
    def finish(self):
        for position, label, relative in self.fixups:
            value = self.labels[label] - position - 1 if relative else self.origin + self.labels[label]
            if relative:
                if not -128 <= value <= 127: raise ValueError("6502 branch out of range")
                self.code[position] = value & 255
            else: self.code[position:position + 2] = value.to_bytes(2, "little")
        return bytes(self.code)


def diagnostic_firmware() -> bytes:
    c = Code6502(0xe000)
    c.emit(0x78, 0xd8, 0xa2, 0xff, 0x9a, 0xa9, 0)
    for address in (0x2000, 0x2001, 0x4022): c.emit(0x8d, address & 255, address >> 8)
    c.emit(0xa9, 3, 0x8d, 0x23, 0x40, 0xa9, 0x86, 0x8d, 0x25, 0x40)
    for name, length in (("volume", 56), ("count", 2), ("header", 16)):
        c.address(0x20, "block"); c.emit(0xa2, length)
        c.label(name); c.address(0x20, "byte"); c.emit(0xca); c.branch(0xd0, name)
    c.address(0x20, "block")
    for _ in range(3): c.address(0x20, "byte")  # block ID and our two marker bytes
    c.emit(0xa9, 0, 0x85, 0, 0xa9, 0x60, 0x85, 1, 0xa2, 2, 0xa0, 0)
    c.label("load"); c.address(0x20, "byte"); c.emit(0x91, 0, 0xc8); c.branch(0xd0, "load")
    c.emit(0xe6, 1, 0xca); c.branch(0xd0, "load")
    c.emit(0x4c, 0, 0x60)
    c.label("block"); c.emit(0xa9, 0x85, 0x8d, 0x25, 0x40, 0xa9, 0xc5, 0x8d, 0x25, 0x40, 0x60)
    c.label("byte"); c.emit(0xad, 0x30, 0x40, 0x29, 2); c.branch(0xf0, "byte")
    c.emit(0xad, 0x31, 0x40, 0x60)
    code = c.finish(); firmware = bytearray([0xea] * 8192); firmware[:len(code)] = code
    firmware[-6:] = b"\x00\xe0" * 3
    return bytes(firmware)


def disk_program(palette):
    c = Code6502(0x6000)
    c.emit(0xa9, 0, 0x8d, 0, 0x20, 0x8d, 1, 0x20)
    c.label("vblank"); c.emit(0x2c, 2, 0x20); c.branch(0x10, "vblank")
    # Tile zero is empty, tile one is solid. Top fifteen rows use tile one.
    c.emit(0xa9, 0, 0x8d, 6, 0x20, 0x8d, 6, 0x20, 0xa2, 16)
    c.label("empty"); c.emit(0x8d, 7, 0x20, 0xca); c.branch(0xd0, "empty")
    c.emit(0xa2, 8, 0xa9, 255)
    c.label("solid"); c.emit(0x8d, 7, 0x20, 0xca); c.branch(0xd0, "solid")
    c.emit(0xa2, 8, 0xa9, 0)
    c.label("plane"); c.emit(0x8d, 7, 0x20, 0xca); c.branch(0xd0, "plane")
    c.emit(0xa9, 0x20, 0x8d, 6, 0x20, 0xa9, 0, 0x8d, 6, 0x20, 0xa0, 0)
    c.label("row"); c.emit(0xa2, 32, 0x98, 0xc9, 15, 0xa9, 0); c.branch(0xb0, "write")
    c.emit(0xa9, 1); c.label("write"); c.emit(0x8d, 7, 0x20, 0xca); c.branch(0xd0, "write")
    c.emit(0xc8, 0xc0, 30); c.branch(0xd0, "row")
    c.emit(0xa2, 64, 0xa9, 0)
    c.label("attribute"); c.emit(0x8d, 7, 0x20, 0xca); c.branch(0xd0, "attribute")
    c.emit(0xa9, 0x3f, 0x8d, 6, 0x20, 0xa9, 0, 0x8d, 6, 0x20, 0xa9, 0x0f, 0x8d, 7, 0x20,
           0xa9, palette, 0x8d, 7, 0x20)
    c.emit(0xa9, 0, 0x8d, 0, 0x20, 0x8d, 5, 0x20, 0x8d, 5, 0x20, 0xa9, 0x0a, 0x8d, 1, 0x20)
    c.label("hold"); c.emit(0xad, 0x32, 0x40, 0x29, 1); c.branch(0xf0, "hold")
    c.label("removed"); c.emit(0xad, 0x32, 0x40, 0x29, 1); c.branch(0xd0, "removed")
    c.emit(0x4c, 0, 0xe0)  # Re-run our transfer firmware after a fresh insertion.
    return c.finish().ljust(512, b"\xea")


def diagnostic_disk() -> bytes:
    result = bytearray(b"FDS\x1a" + bytes([2]) + bytes(11))
    for palette in (0x16, 0x1a):
        side = bytearray(56); side[0] = 1; side[1:15] = b"*NINTENDO-HVC*"
        side[16:19] = b"MXI"; side[25] = 0x61
        payload = b"\xd5\x6e" + disk_program(palette)
        header = bytes([3, 0, 0]) + b"MUXIDIAG" + (0x6000).to_bytes(2, "little") + len(payload).to_bytes(2, "little") + b"\x00"
        side.extend(b"\x02\x01" + header + b"\x04" + payload)
        result.extend(side.ljust(65500, b"\x00"))
    return bytes(result)
