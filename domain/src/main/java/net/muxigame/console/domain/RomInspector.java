package net.muxigame.console.domain;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** Independent cartridge admission. Raw formats have extension-based identification only. */
public final class RomInspector {
    public static final long MAX_ROM_BYTES = 12L * 1024L * 1024L * 1024L;

    public RomDescriptor inspect(Path file, Platform selected) throws IOException {
        return inspect(file, file.getFileName().toString(), selected,()->true);
    }
    public RomDescriptor inspect(Path file,Platform selected,BooleanSupplier active)throws IOException{return inspect(file,file.getFileName().toString(),selected,active);}

    public RomDescriptor inspect(Path file, String filename, Platform selected) throws IOException {
        return inspect(file,filename,selected,()->true);
    }
    public RomDescriptor inspect(Path file,String filename,Platform selected,BooleanSupplier active)throws IOException{
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
            if(Thread.currentThread().isInterrupted()||!active.getAsBoolean())throw new InterruptedIOException("ROM inspection cancelled");
            header = input.readNBytes(4096);
            digest.update(header);
            count = header.length;
            byte[] buffer = new byte[32768];
            for (int length; (length = input.read(buffer)) != -1;) {
                if(Thread.currentThread().isInterrupted()||!active.getAsBoolean())throw new InterruptedIOException("ROM inspection cancelled");
                count += length;
                if (count > size) throw new DomainException("size", "ROM changed while being inspected");
                digest.update(buffer, 0, length);
            }
        }
        if (count != size) throw new DomainException("size", "ROM changed while being inspected");
        if(platform!=Platform.ARCADE)rejectContainer(header,platform==Platform.WII);
        Platform marker = markerPlatform(header);
        if (marker != null && marker != platform) throw new DomainException("format", "Cartridge header belongs to another platform");
        String detection = switch (platform) {
            case NES -> inspectNes(header, size);
            case GB, GBC -> inspectGameBoy(header, size, platform);
            case GBA -> inspectGba(header);
            case N64 -> inspectN64(header, size);
            case MD -> inspectMegaDrive(header, size);
            case FDS -> inspectFds(file, size);
            case LYNX -> inspectLynx(header,size,filename);
            case ARCADE -> { ArcadeBundle.Manifest media=ArcadeBundle.inspect(file);yield "mxarc-1:"+media.driver(); }
            case NDS -> inspectNds(header,size);
            case N3DS -> inspect3ds(header,size,filename);
            case WII -> inspectWii(header,size,filename);
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
    // Public Lynx file formats: beetle-lynx mednafen/lynx/system.cpp and ram.cpp.
    // BS93 is an emulated 65C02 homebrew object, not a host object/executable file.
    private static String inspectLynx(byte[] h,long size,String filename){
        boolean bs93=h.length>=10&&h[6]=='B'&&h[7]=='S'&&h[8]=='9'&&h[9]=='3';
        if(bs93){int entry=(u(h[2])<<8)|u(h[3]),length=(u(h[4])<<8)|u(h[5]);require(entry>=10&&size>=11&&size<=65535&&length==size,"BS93 RAM image has invalid entry or byte length");return "lynx-bs93-size-and-entry";}
        require(!filename.toLowerCase(Locale.ROOT).endsWith(".o"),"Only BS93 Lynx homebrew objects are admitted");
        if(starts(h,'L','Y','N','X')){require(size>=1088&&h.length>=64,"Truncated LNX cartridge");return "lynx-cartridge-header";}
        require(size==128*1024||size==256*1024||size==512*1024,"Expected LNX header or a supported raw Lynx cartridge size");return "lynx-raw-cartridge-size";
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

    private static void rejectContainer(byte[] header,boolean wiiElf) {
        if (starts(header, 0x50, 0x4b) || starts(header, 0x37, 0x7a, 0xbc, 0xaf)
                || starts(header, 0x1f, 0x8b) || starts(header, 0x4d, 0x5a) || !wiiElf&&starts(header, 0x7f, 0x45, 0x4c, 0x46))
            throw new DomainException("format", "Archives and executable containers are not supported cartridge files");
    }
    private static long le32(byte[] h,int p){return Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(h,p,4).order(java.nio.ByteOrder.LITTLE_ENDIAN).getInt());}
    private static long be32(byte[] h,int p){return Integer.toUnsignedLong(java.nio.ByteBuffer.wrap(h,p,4).order(java.nio.ByteOrder.BIG_ENDIAN).getInt());}
    private static boolean marker(byte[] h,int p,String value){if(h.length<p+value.length())return false;for(int i=0;i<value.length();i++)if(u(h[p+i])!=value.charAt(i))return false;return true;}
    private static String inspectNds(byte[] h,long size){
        require(h.length>=512,"Truncated DS header");int crc=0xffff;for(int i=0;i<0x15e;i++){crc^=u(h[i]);for(int bit=0;bit<8;bit++)crc=(crc>>>1)^((crc&1)!=0?0xa001:0);}
        require(crc==(u(h[0x15e])|(u(h[0x15f])<<8)),"DS header CRC16 mismatch");
        for(int p:new int[]{0x20,0x30}){long offset=le32(h,p),entry=le32(h,p+4),address=le32(h,p+8),length=le32(h,p+12);require(offset>=512&&length>=4&&offset+length<=size,"DS CPU payload exceeds ROM bounds");require(address>=0x02000000L&&address+length<=0x04000000L&&entry>=address&&entry<address+length,"DS CPU entry or load address is invalid");}
        return "nds-cpu-ranges-and-header-crc16";
    }
    private static String inspect3ds(byte[] h,long size,String filename){
        if(filename.toLowerCase(Locale.ROOT).endsWith(".3dsx")){
            require(starts(h,'3','D','S','X')&&h.length>=32,"Missing 3DSX homebrew header");int header=u(h[4])|(u(h[5])<<8),relocation=u(h[6])|(u(h[7])<<8);long code=le32(h,16),ro=le32(h,20),data=le32(h,24),bss=le32(h,28);
            require(header>=32&&header<=size&&relocation>=8&&relocation<=4096&&code>0&&bss<=data&&header+3L*relocation+code+ro+data-bss<=size,"3DSX segments exceed file bounds");return "3dsx-homebrew-segment-bounds";
        }
        require(h.length>=512,"Truncated 3DS cartridge header");
        if(marker(h,0x100,"NCSD")){long media=le32(h,0x104)*512;require(media>=512,"Invalid NCSD media length");boolean partition=false;for(int i=0;i<8;i++){long offset=le32(h,0x120+i*8)*512,length=le32(h,0x124+i*8)*512;if(length>0){partition=true;require(offset>=512&&offset+length<=size&&offset+length<=media,"NCSD partition exceeds file bounds");}}require(partition,"NCSD has no partitions");return "ncsd-partition-bounds";}
        require(marker(h,0x100,"NCCH")&&le32(h,0x104)>0&&le32(h,0x104)*512<=size,"Expected an NCSD cartridge or NCCH executable container");return "ncch-header-size";
    }
    private static String inspectWii(byte[] h,long size,String filename){
        String extension=filename.substring(filename.lastIndexOf('.')+1).toLowerCase(Locale.ROOT);
        if(extension.equals("dol")){
            require(h.length>=256,"Truncated DOL header");long entry=be32(h,0xe0);boolean executable=false;
            for(int i=0;i<18;i++){long offset=be32(h,i*4),address=be32(h,0x48+i*4),length=be32(h,0x90+i*4);if(length==0)continue;require(offset>=256&&offset+length<=size&&address>=0x80000000L&&address+length<=0x94000000L,"DOL segment exceeds file or Wii memory bounds");if(i<7&&entry>=address&&entry<address+length)executable=true;}
            require(executable,"DOL entry point is outside executable segments");return "wii-dol-segment-bounds";
        }
        if(extension.equals("elf")){
            require(h.length>=52&&starts(h,0x7f,'E','L','F')&&h[4]==1&&h[5]==2&&u(h[18])==0&&u(h[19])==20,"Only 32-bit big-endian PowerPC Wii ELF is admitted");long table=be32(h,28);int stride=(u(h[42])<<8)|u(h[43]),count=(u(h[44])<<8)|u(h[45]);require(stride>=32&&count>0&&count<=128&&table+(long)stride*count<=size,"Invalid Wii ELF program headers");return "wii-powerpc-elf-header";
        }
        if(extension.equals("iso")){require(h.length>=32&&be32(h,0x18)==0x5d1c9ea3L,"Missing Wii disc magic");return "wii-disc-header";}
        if(extension.equals("wbfs")){require(h.length>=12&&starts(h,'W','B','F','S')&&u(h[8])>=9&&u(h[8])<=16&&u(h[9])>=u(h[8])&&u(h[9])<=26&&be32(h,4)>0,"Invalid WBFS disc header");return "wii-wbfs-header";}
        require(extension.equals("rvz")&&starts(h,'R','V','Z',1)&&h.length>=72,"Missing RVZ format header");return "wii-rvz-header";
    }

    private static Platform markerPlatform(byte[] header) {
        if (starts(header, 0x4e, 0x45, 0x53, 0x1a)) return Platform.NES;
        if (starts(header, 0x46, 0x44, 0x53, 0x1a)) return Platform.FDS;
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

    private static String inspectFds(Path file,long size)throws IOException{
        try(InputStream in=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){
            byte[] first=in.readNBytes(16);boolean headered=starts(first,0x46,0x44,0x53,0x1a);long sides;
            if(headered){sides=u(first[4]);require(sides>0&&size==16+sides*65500,"FDS header side count does not match file size");for(int i=5;i<16;i++)require(first[i]==0,"FDS reserved header bytes must be zero");}
            else{sides=size/65500;require(sides>0&&size%65500==0,"Headerless FDS must contain complete 65500-byte sides");}
            byte[] side=new byte[65500];for(int n=0;n<sides;n++){
                int offset=0;if(!headered&&n==0){System.arraycopy(first,0,side,0,first.length);offset=first.length;}
                int got=in.readNBytes(side,offset,side.length-offset);require(got==side.length-offset,"Truncated FDS side");
                require(side[0]==1,"Missing FDS disk information block");byte[] signature="*NINTENDO-HVC*".getBytes(java.nio.charset.StandardCharsets.US_ASCII);for(int i=0;i<signature.length;i++)require(side[1+i]==signature[i],"Invalid FDS disk identification");
                require(side[56]==2,"Missing FDS file count block");int files=u(side[57]),position=58;for(int index=0;index<files;index++){require(position+17<=side.length&&side[position]==3,"Invalid FDS file header block");int length=u(side[position+13])|(u(side[position+14])<<8);position+=16;require(side[position]==4&&position+1L+length<=side.length,"Invalid FDS file data block");position+=1+length;}
            }
            require(in.read()==-1,"Unexpected FDS trailing bytes");return headered?"fds-header-and-disk-blocks":"fds-headerless-disk-blocks";
        }
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
