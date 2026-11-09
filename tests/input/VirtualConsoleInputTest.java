package net.muxigame.console.tests.input;

import net.muxigame.console.client.input.VirtualConsoleInput;

/** Virtual user actions produce bounded real input packets; no ambient microphone is opened. */
public final class VirtualConsoleInputTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    public static void main(String[] args){
        var virtual=new VirtualConsoleInput();long now=1_000_000_000L;
        var idle=virtual.sample(false,false,false,false,false,false,false,now);
        check(idle.motion().accelerationX()==0&&idle.motion().accelerationY()==0&&idle.motion().accelerationZ()==-1,"Neutral virtual gravity has a centred physical orientation");
        check(!idle.microphone().connected()&&idle.microphone().samples().length==0,"No microphone activity without the explicit blowing action");
        var tilt=virtual.sample(true,false,true,false,false,false,false,now+50_000_000);
        check(tilt.motion().accelerationX()<0&&tilt.motion().accelerationY()>0&&tilt.motion().gyroX()>0&&tilt.motion().gyroY()<0,"Two actual held directions produce tilt and edge angular velocity");
        var held=virtual.sample(true,false,true,false,false,false,false,now+100_000_000);
        check(held.motion().gyroX()==0&&held.motion().gyroY()==0,"Holding a steady tilt does not invent continued angular velocity");
        var centred=virtual.sample(true,false,true,false,true,true,false,now+150_000_000);
        check(centred.motion().accelerationX()==0&&centred.motion().accelerationY()==0&&centred.motion().accelerationZ()==-1,"Explicit centre suppresses both tilt and shake");
        var first=virtual.sample(false,false,false,false,false,false,true,now+200_000_000);
        check(first.microphone().connected()&&first.microphone().sampleRate()==48000&&first.microphone().samples().length==2400,"A blowing press produces one bounded 50ms PCM block");
        int nonzero=0,peak=0;for(short sample:first.microphone().samples()){if(sample!=0)nonzero++;peak=Math.max(peak,Math.abs((int)sample));}
        check(peak<=8192,"Generated blowing stays inside PCM amplitude headroom");
        check(nonzero>2200,"Generated blowing contains actual nonzero audio samples");
        var throttled=virtual.sample(false,false,false,false,false,false,true,now+201_000_000);
        check(throttled.microphone().connected()&&throttled.microphone().samples().length==0,"Repeated sampling cannot resend or duplicate the last audio block");
        virtual.sample(false,false,false,false,false,false,false,now+202_000_000);
        check(virtual.sample(false,false,false,false,false,false,true,now+203_000_000).microphone().samples().length==0,"Rapid release/repress cannot bypass the 20Hz audio budget");
        var second=virtual.sample(false,false,false,false,false,false,true,now+250_000_000);
        check(second.microphone().samples().length==2400&&!java.util.Arrays.equals(first.microphone().samples(),second.microphone().samples()),"The next due block contains new audio rather than a replay");
        var released=virtual.sample(false,false,false,false,false,false,false,now+300_000_000);
        check(!released.microphone().connected()&&released.microphone().samples().length==0,"Release disconnects the virtual blowing source");
        check(released.motion().timestampNanos()==now+300_000_000&&released.motion().valid(),"The input timestamp is its actual caller sampling instant");
        try{virtual.sample(false,false,false,false,false,false,false,now);throw new AssertionError("Backward time accepted");}catch(IllegalArgumentException expected){checks++;}
        System.out.println("VirtualConsoleInputTest: "+checks+" checks passed; virtual actions only, no physical microphone claim");
    }
}
