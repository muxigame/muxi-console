import net.muxigame.console.client.input.FrameInputBuffer;
import net.muxigame.console.client.input.RealtimeFrameClock;
import net.muxigame.console.runtime.PortInput;

public final class RealtimeInputTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        for(int pulse:new int[]{10,20,30}){
            FrameInputBuffer input=new FrameInputBuffer(64,500_000_000);input.publish(0,PortInput.IDLE,0);input.publish(1,new PortInput(256),1_000_000);input.publish(2,PortInput.IDLE,(1L+pulse)*1_000_000);
            check(input.take(50_000_000,1).buttons()==256,"complete "+pulse+"ms tap is consumed even between 50ms game ticks");check(input.take(66_000_000,2).buttons()==0,"release occupies the next actual frame");
            var trace=input.metrics().applied();check(trace.size()==2&&trace.get(0).sampledAtNanos()==1_000_000&&trace.get(0).frame()==1&&trace.get(1).frame()==2,"trace binds actual sample time and applied frame");
        }
        FrameInputBuffer input=new FrameInputBuffer(4,300);
        check(input.publish(5,new PortInput(256),100),"first authenticated sequence accepted");check(!input.publish(5,PortInput.IDLE,101)&&!input.publish(4,PortInput.IDLE,102),"duplicates/old sequences cannot alter input");check(input.take(110).buttons()==256,"old release did not override current press");check(input.take(401).buttons()==0,"remote level expires without replay");
        input.publish(6,new PortInput(256),500);input.publish(7,PortInput.IDLE,510);input.clear();check(input.take(520).buttons()==0&&input.metrics().queued()==0,"focus loss retires every pending edge");
        input.publish(8,new PortInput(256),600);input.publish(9,PortInput.IDLE,610);input.publish(10,new PortInput(1),1000);check(input.take(1001).buttons()==1&&input.metrics().droppedTransitions()==2,"fresh packet cannot keep old digital edges alive");
        for(int i=11;i<31;i++)input.publish(i,new PortInput(i%2),1100+i);check(input.metrics().queued()<=4&&input.metrics().droppedTransitions()>2,"burst queue is bounded and explicitly counts discarded transitions");
        PortInput.Microphone mic=new PortInput.Microphone(true,48000,new short[]{1,2,3});FrameInputBuffer auxiliary=new FrameInputBuffer(4,1000);
        auxiliary.publish(0,new PortInput(0,(short)0,(short)0,(short)0,(short)0,1,PortInput.Pointer.IDLE,PortInput.Motion.IDLE,mic),0);auxiliary.publish(1,new PortInput(0,(short)0,(short)0,(short)0,(short)0,1,PortInput.Pointer.IDLE,PortInput.Motion.IDLE,mic.withoutSamples()),1);
        check(auxiliary.take(2).microphone().samples().length==3,"render poll cannot erase an unconsumed microphone chunk");check(auxiliary.take(3).microphone().samples().length==0,"microphone chunk is consumed only once");
        RealtimeFrameClock clock=new RealtimeFrameClock(59.7275005696);clock.renew(0,15000);long now=0;
        for(int i=0;i<600;i++){now+=clock.delay(now);clock.completed(now+1000);}
        check(Math.abs(now-599*1_000_000_000d/59.7275005696)<2,"fractional GBA fps has no 20Hz rounding drift");check(clock.completedFrames()==600&&clock.lateFrames()==0,"normal scheduler records actual completions");
        check(clock.delay(15_000_000_000L)==-1,"lost server lease stops local core steps");clock.renew(20_000_000_000L,15000);check(clock.delay(20_000_000_000L)==0,"renewal reanchors rather than accumulating five seconds of work");clock.completed(20_500_000_000L);check(clock.delay(20_500_000_000L)>16_000_000,"large worker stall schedules a future frame, never an unbounded catch-up burst");clock.reanchor(21_000_000_000L);check(clock.delay(21_000_000_000L)==0,"snapshot completion resumes from current time");
        clock.pause();check(clock.delay(21_000_000_000L)==-1,"explicit hold stops dispatch");
        RealtimeFrameClock negative=new RealtimeFrameClock(60);negative.renew(-2_000_000_000L,15000);check(negative.delay(-2_000_000_000L)==0,"absolute nanoTime deadlines may be negative");
        RealtimeFrameClock capture=new RealtimeFrameClock(60);capture.renew(0,15000);capture.completed(0);long before=capture.delay(1_000_000);capture.resumeAfterSnapshot(1_000_000);check(capture.delay(1_000_000)==before&&before>15_000_000,"one-millisecond snapshot preserves the future frame phase exactly");capture.resumeAfterSnapshot(20_000_000);capture.completed(20_000_000);check(capture.delay(20_000_000)>13_000_000,"small overrun recovers only the single due frame");capture.resumeAfterSnapshot(500_000_000);capture.completed(500_000_000);check(capture.delay(500_000_000)>16_000_000,"long capture cannot accumulate a burst of old deadlines");
        System.out.println("Realtime input/clock: "+checks+" checks passed");
    }
}
