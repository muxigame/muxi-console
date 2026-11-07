package net.muxigame.console.domain;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Locale;

/** Independent cartridge admission. Raw formats have extension-based identification only. */
public final class RomInspector {
    public static final long MAX_ROM_BYTES = 128L * 1024L * 1024L;

    public RomDescriptor inspect(Path file, Platform selected) throws IOException {
        return inspect(file, file.getFileName().toString(), selected);
    }

    public RomDescriptor inspect(Path file, String filename, Platform selected) throws IOException {
        validateFilename(filename);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new DomainException("file", "Expected a regular ROM file");
        long size = Files.size(file);
        Platform platform = selectPlatform(filename, selected);
        if (size < platform.minBytes() || size > Math.min(platform.maxBytes(), MAX_ROM_BYTES))
            throw new DomainException("size", "ROM size outside admitted range");
        byte[] header;
        var digest = Hashing.sha256Digest();
        long count = 0;
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            header = input.readNBytes(512);
            digest.update(header);
            count = header.length;
            byte[] buffer = new byte[32768];
            for (int length; (length = input.read(buffer)) != -1;) {
                count += length;
                if (count > size) throw new DomainException("size", "ROM changed while being inspected");
                digest.update(buffer, 0, length);
            }
        }
        if (count != size) throw new DomainException("size", "ROM changed while being inspected");
        rejectContainer(header);
        Platform marker = markerPlatform(header);
        if (marker != null && marker != platform) throw new DomainException("format", "Cartridge header belongs to another platform");
        String detection = switch (platform) {
            case NES -> inspectNes(header, size);
            case GB, GBC -> inspectGameBoy(header, size, platform);
            case GBA -> inspectGba(header);
            case N64 -> inspectN64(header, size);
            case MD -> inspectMegaDrive(header, size);
            default -> "extension-and-size";
        };
        return new RomDescriptor(platform, HexFormat.of().formatHex(digest.digest()), size, filename, detection);
    }

    public static void validateFilename(String filename) {
        if (filename == null || filename.isBlank() || filename.length() > 128 || filename.equals(".") || filename.equals("..")
                || filename.indexOf('/') >= 0 || filename.indexOf('\\') >= 0 || filename.indexOf(':') >= 0
                || filename.codePoints().anyMatch(Character::isISOControl) || filename.endsWith(".") || filename.endsWith(" "))
            throw new DomainException("filename", "Use a simple ROM filename without path components");
        String lower = filename.toLowerCase(Locale.ROOT);
    }

    public static Platform selectPlatform(String filename, Platform selected) {
        validateFilename(filename);
        int dot = filename.lastIndexOf('.');
        String extension = dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        if (selected != null) {
            if (!selected.acceptsExtension(extension)) throw new DomainException("extension", "Filename does not match selected platform");
            return selected;
        }
        // .bin is ambiguous across many systems and must never silently choose a platform.
        if (extension.equals("bin")) throw new DomainException("platform", "Select a platform for a raw binary");
        var candidates = Platform.forExtension(extension);
        if (candidates.size() != 1) throw new DomainException("extension", "Unsupported or ambiguous cartridge extension");
        return candidates.getFirst();
    }

    private static void rejectContainer(byte[] header) {
        if (starts(header, 0x50, 0x4b) || starts(header, 0x37, 0x7a, 0xbc, 0xaf)
                || starts(header, 0x1f, 0x8b) || starts(header, 0x4d, 0x5a) || starts(header, 0x7f, 0x45, 0x4c, 0x46))
            throw new DomainException("format", "Archives and executable containers are not supported cartridge files");
    }

    private static Platform markerPlatform(byte[] header) {
        if (starts(header, 0x4e, 0x45, 0x53, 0x1a)) return Platform.NES;
        if (starts(header, 0x80, 0x37, 0x12, 0x40) || starts(header, 0x37, 0x80, 0x40, 0x12)
                || starts(header, 0x40, 0x12, 0x37, 0x80)) return Platform.N64;
        return null;
    }

    // Format specification by the iNES format author: https://fms.komkon.org/EMUL8/NES.html#LABM
    private static String inspectNes(byte[] header, long size) {
        require(header.length >= 16 && starts(header, 0x4e, 0x45, 0x53, 0x1a), "Missing iNES header");
        boolean nes2 = (u(header[7]) & 0x0c) == 0x08;
        long prg = u(header[4]);
        long chr = u(header[5]);
        if (nes2) {
            int upper = u(header[9]);
            require((upper & 15) != 15 && (upper >>> 4) != 15, "NES 2 exponent sizes are not admitted");
            prg |= (upper & 15) << 8;
            chr |= (upper >>> 4) << 8;
        }
        long expected = 16 + ((u(header[6]) & 4) == 0 ? 0 : 512) + prg * 16384L + chr * 8192L;
        require(prg > 0 && size == expected, "iNES bank sizes do not match the uploaded bytes");
        return nes2 ? "nes2-linear-header" : "ines-header";
    }

    // Header fields/checksum: https://gbdev.io/pandocs/The_Cartridge_Header.html
    private static String inspectGameBoy(byte[] header, long size, Platform platform) {
        require(header.length >= 0x150, "Truncated Game Boy header");
        int checksum = 0;
        for (int i = 0x134; i <= 0x14c; i++) checksum = (checksum - u(header[i]) - 1) & 255;
        require(checksum == u(header[0x14d]), "Game Boy header checksum mismatch");
        int code = u(header[0x148]);
        long expected = switch (code) {
            case 0, 1, 2, 3, 4, 5, 6, 7, 8 -> 32768L << code;
            case 0x52 -> 72 * 16384L;
            case 0x53 -> 80 * 16384L;
            case 0x54 -> 96 * 16384L;
            default -> -1;
        };
        require(expected == size, "Game Boy bank size does not match file size");
        boolean colorRequired = u(header[0x143]) == 0xc0;
        boolean colorSupported = (u(header[0x143]) & 0x80) != 0;
        require(platform != Platform.GB || !colorRequired, "Color-only cartridge requires GBC");
        require(platform != Platform.GBC || colorSupported, "GBC selection requires a color-capable cartridge");
        return "gameboy-size-and-header-checksum";
    }

    // mGBA-maintained format reference: https://mgba-emu.github.io/gbatek/#gba-cartridge-header
    private static String inspectGba(byte[] header) {
        require(header.length >= 192 && u(header[0xb2]) == 0x96, "Missing GBA header marker");
        int checksum = 0;
        for (int i = 0xa0; i <= 0xbc; i++) checksum -= u(header[i]);
        require(((checksum - 0x19) & 255) == u(header[0xbd]), "GBA header checksum mismatch");
        return "gba-marker-and-header-checksum";
    }

    private static String inspectN64(byte[] header, long size) {
        require(size >= 4096 && size % 4 == 0, "N64 cartridges require a complete aligned boot area");
        require(starts(header, 0x80, 0x37, 0x12, 0x40) || starts(header, 0x37, 0x80, 0x40, 0x12)
                || starts(header, 0x40, 0x12, 0x37, 0x80), "Unknown N64 byte order marker");
        return "n64-byte-order-marker";
    }

    private static String inspectMegaDrive(byte[] header, long size) {
        require(size >= 512 && size % 2 == 0 && header.length >= 0x104
                && header[0x100] == 'S' && header[0x101] == 'E' && header[0x102] == 'G' && header[0x103] == 'A',
                "Expected an uncompressed Mega Drive cartridge header");
        // Public ROM header reference: https://plutiedev.com/rom-header (boot-ROM serial types OS/BR).
        require(!(header[0x180] == 'O' && header[0x181] == 'S')
                && !(header[0x180] == 'B' && header[0x181] == 'R'), "Boot ROMs are not cartridge content");
        return "megadrive-header-marker";
    }

    private static boolean starts(byte[] bytes, int... signature) {
        if (bytes.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) if (u(bytes[i]) != signature[i]) return false;
        return true;
    }
    private static int u(byte value) { return value & 255; }
    private static void require(boolean condition, String message) {
        if (!condition) throw new DomainException("format", message);
    }
}
