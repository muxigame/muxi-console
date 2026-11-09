import java.util.Arrays;
import net.muxigame.console.client.audio.PcmSampleQueue;

public final class PcmSampleQueueTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        PcmSampleQueue a=new PcmSampleQueue(100),b=new PcmSampleQueue(100);short[] signal=new short[160];for(int i=0;i<signal.length;i++)signal[i]=(short)i;
        a.offer(signal);for(int from=0;from<signal.length;from+=4)b.offer(Arrays.copyOfRange(signal,from,from+4));
        check(a.frames()==b.frames()&&Arrays.equals(a.poll(100),b.poll(100)),"capacity is sample duration, independent of 1 or 40 producer chunks");check(a.offeredFrames()==80&&a.droppedFrames()==0,"offered/dropped frames are real samples");
        PcmSampleQueue wrap=new PcmSampleQueue(6);wrap.offer(new short[]{0,1,2,3,4,5,6,7});check(Arrays.equals(wrap.poll(3),new short[]{0,1,2,3,4,5}),"ring reads complete stereo frames");wrap.offer(new short[]{8,9,10,11,12,13,14,15});check(Arrays.equals(wrap.poll(6),new short[]{6,7,8,9,10,11,12,13,14,15}),"wrapped channels remain in exact order");
        wrap.offer(signal);check(wrap.frames()==6&&wrap.droppedFrames()==74,"oversized backlog is bounded and lost sample duration is explicit");check(Arrays.equals(wrap.poll(6),Arrays.copyOfRange(signal,148,160)),"overload keeps latest complete stereo frames");wrap.offer(new short[]{1,2});wrap.clear();check(wrap.frames()==0&&wrap.poll(1).length==0,"close flushes pending PCM");
        try{wrap.offer(new short[3]);throw new AssertionError("odd samples accepted");}catch(IllegalArgumentException expected){checks++;}
        PcmSampleQueue startup=new PcmSampleQueue(100);startup.offer(signal);check(startup.discardTo(20)==60&&startup.frames()==20&&startup.droppedFrames()==60,"explicit startup trim is duration bounded and included in total discarded samples");check(Arrays.equals(startup.poll(20),Arrays.copyOfRange(signal,120,160)),"startup trim preserves the newest complete stereo frames");startup.offer(new short[]{1,2,3,4});check(startup.discardTo(20)==0&&startup.frames()==2&&startup.droppedFrames()==60,"under-target priming never drops or fabricates samples");check(startup.discardTo(0)==2&&startup.frames()==0&&startup.droppedFrames()==62,"zero retained duration retires the exact remaining samples");try{startup.discardTo(101);throw new AssertionError("unbounded retain accepted");}catch(IllegalArgumentException expected){checks++;}
        System.out.println("PCM sample queue: "+checks+" checks passed");
    }
}
