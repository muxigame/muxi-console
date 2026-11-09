package net.muxigame.console.runtime;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

/** The fixed mGBA GBA-v11 extdata format, independently decoded from its public file format.
 * libretro/mgba 7a12d6d: core/serialize.c restores tag2 even without SAVESTATE_SAVEDATA,
 * creating a private SRAM mask which retro_get_memory_data does not expose. */
public final class MgbaStateNormalizer {
    private static final int MAGIC=0x0100000b,BASE=0x61000,HEADER=16,MAX_HEADERS=1024,EXTDATA_MAX=0x103;
    /** Inputs are never modified. A changed result owns new core/SRAM arrays. */
    public record Result(byte[] core,byte[] sram,boolean normalized){}
    private record Entry(int header,int tag,int size,int offset){}
    public static Result normalize(byte[] core,byte[] externalSram)throws IOException{
        Objects.requireNonNull(core);Objects.requireNonNull(externalSram);
        if(core.length<4||ByteBuffer.wrap(core).order(ByteOrder.LITTLE_ENDIAN).getInt()!=MAGIC)return new Result(core,externalSram,false);
        if(core.length<BASE)throw new IOException("Truncated mGBA GBA state");
        if(core.length==BASE)return new Result(core,externalSram,false);
        ByteBuffer input=ByteBuffer.wrap(core).order(ByteOrder.LITTLE_ENDIAN);List<Entry> entries=new ArrayList<>();int tableEnd=-1;
        for(int n=0;n<MAX_HEADERS;n++){
            int at=BASE+n*HEADER;if(at>core.length-HEADER)throw new IOException("Truncated mGBA state extension table");
            int tag=input.getInt(at),size=input.getInt(at+4);long offset=input.getLong(at+8);
            if(tag==0){if(size!=0||offset!=0)throw new IOException("Invalid mGBA extension terminator");tableEnd=at+HEADER;break;}
            // Unknown tags are explicitly skipped by this upstream parser.
            if(Integer.compareUnsigned(tag,EXTDATA_MAX)>=0)continue;
            if(size<=0||offset<BASE||offset>core.length||(long)size>core.length-offset)throw new IOException("Invalid mGBA extension payload bounds");
            entries.add(new Entry(at,tag,size,(int)offset));
        }
        if(tableEnd<0)throw new IOException("mGBA state extension table exceeds bound");
        Entry savedata=null;for(Entry entry:entries){
            if(entry.offset<tableEnd)throw new IOException("mGBA extension overlaps its table");
            if(entry.tag==2){if(savedata!=null)throw new IOException("Duplicate mGBA SRAM extension");savedata=entry;}
        }
        if(savedata==null)return new Result(core,externalSram,false);
        int size=savedata.size;if(size<512||size>128*1024||(size&(size-1))!=0)throw new IOException("Invalid mGBA SRAM extension size");
        byte[] sram=Arrays.copyOfRange(core,savedata.offset,savedata.offset+size),normalized=core.clone();
        // Do not use tag0: that would terminate parsing before a following RTC.
        ByteBuffer.wrap(normalized).order(ByteOrder.LITTLE_ENDIAN).putInt(savedata.header,0xffffffff);
        return new Result(normalized,sram,true);
    }
    private MgbaStateNormalizer(){}
}
