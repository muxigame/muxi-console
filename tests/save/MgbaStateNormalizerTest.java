import net.muxigame.console.runtime.MgbaStateNormalizer;
import java.io.IOException;
import java.nio.*;
import java.util.*;

/** Default pure save suite: format safety; actual fixed-core cold recovery uses separate native workers. */
public final class MgbaStateNormalizerTest {
    private static final int BASE=0x61000;
    private static int checks;
    private static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    private static ByteBuffer le(byte[] bytes){return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);}
    private static byte[] fixture(){byte[] out=new byte[BASE+48+512+16];var b=le(out);b.putInt(0,0x0100000b);b.putInt(BASE,2).putInt(BASE+4,512).putLong(BASE+8,BASE+48);b.putInt(BASE+16,4).putInt(BASE+20,16).putLong(BASE+24,BASE+48+512);Arrays.fill(out,BASE+48,BASE+48+512,(byte)2);Arrays.fill(out,BASE+48+512,out.length,(byte)7);return out;}
    private static void rejected(byte[] bytes,String why)throws Exception{try{MgbaStateNormalizer.normalize(bytes,new byte[512]);throw new AssertionError(why);}catch(IOException expected){checks++;}}
    public static void main(String[] args)throws Exception{
        byte[] core=fixture(),original=core.clone(),stale=new byte[512];Arrays.fill(stale,(byte)1);var result=MgbaStateNormalizer.normalize(core,stale);
        check(result.normalized(),"recognized old duplicate SRAM state");check(result.sram().length==512&&result.sram()[0]==2&&result.sram()[511]==2,"authoritative private CPU backing beats stale external SRAM");
        check(Arrays.equals(core,original)&&stale[0]==1,"does not mutate original downloaded state or SRAM");check(le(result.core()).getInt(BASE)==-1,"marks only savedata descriptor as unknown, not terminator");
        check(le(result.core()).getInt(BASE+16)==4&&le(result.core()).getLong(BASE+24)==BASE+48+512,"following RTC descriptor and offset stay intact");
        byte[] expected=original.clone();le(expected).putInt(BASE,-1);check(Arrays.equals(result.core(),expected),"all preferred core data, payloads, RTC and offsets remain byte-for-byte unchanged");
        var twice=MgbaStateNormalizer.normalize(result.core(),result.sram());check(!twice.normalized()&&Arrays.equals(twice.core(),result.core()),"normalized state is idempotent and compatible with new bridge exports");
        byte[] unrelated=new byte[17];check(!MgbaStateNormalizer.normalize(unrelated,stale).normalized(),"unrelated core format untouched");
        byte[] version=original.clone();le(version).putInt(0,0x0100000a);check(!MgbaStateNormalizer.normalize(version,stale).normalized(),"unknown state version never parsed using a guessed layout");
        byte[] bare=Arrays.copyOf(original,BASE);check(!MgbaStateNormalizer.normalize(bare,stale).normalized(),"base state without extension table retained");
        byte[] rtc=original.clone();le(rtc).putInt(BASE,-1);check(!MgbaStateNormalizer.normalize(rtc,stale).normalized(),"RTC-only format has no private SRAM to replace");
        rejected(Arrays.copyOf(original,BASE-1),"truncated base rejected");rejected(Arrays.copyOf(original,BASE+15),"partial header rejected");
        byte[] bad=original.clone();le(bad).putInt(BASE+4,-1);rejected(bad,"negative payload size rejected");bad=original.clone();le(bad).putLong(BASE+8,-1);rejected(bad,"negative offset rejected");
        bad=original.clone();le(bad).putLong(BASE+8,Long.MAX_VALUE);rejected(bad,"offset cannot wrap");bad=original.clone();le(bad).putInt(BASE+4,Integer.MAX_VALUE);rejected(bad,"payload size cannot wrap");
        bad=original.clone();le(bad).putLong(BASE+8,BASE+16);rejected(bad,"SRAM cannot alias a following header");bad=original.clone();le(bad).putInt(BASE+16,2);rejected(bad,"ambiguous duplicate SRAM rejected");
        bad=original.clone();le(bad).putInt(BASE+4,3);rejected(bad,"invalid savedata byte count rejected");bad=original.clone();le(bad).putInt(BASE+36,1);rejected(bad,"noncanonical terminator rejected");
        bad=original.clone();le(bad).putInt(BASE+20,1000);rejected(bad,"RTC payload also bounded before native apply");
        System.out.println("PASS mGBA state normalizer "+checks);
    }
}
