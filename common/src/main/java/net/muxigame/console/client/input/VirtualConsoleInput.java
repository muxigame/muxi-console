package net.muxigame.console.client.input;

import net.muxigame.console.runtime.PortInput;

/** Explicit user-controlled virtual tilt/shake and blowing, without opening a physical microphone. */
public final class VirtualConsoleInput {
    private long previous,shakeStarted,nextAudio;
    private double previousPitch,previousRoll;
    private int noise=0x50534d43;
    public record Sample(PortInput.Motion motion,PortInput.Microphone microphone){}
    public Sample sample(boolean left,boolean right,boolean forward,boolean backward,boolean shake,boolean centre,boolean blow,long now){
        if(now<0||now<previous)throw new IllegalArgumentException("Virtual input time must be monotonic");
        double roll=centre?0:((right?1:0)-(left?1:0))*Math.PI/6;
        double pitch=centre?0:((forward?1:0)-(backward?1:0))*Math.PI/6;
        double seconds=previous==0?.05:Math.max(.01,Math.min(.1,(now-previous)/1_000_000_000.0));
        float gx=(float)((pitch-previousPitch)/seconds),gy=(float)((roll-previousRoll)/seconds);
        previous=now;previousPitch=pitch;previousRoll=roll;
        float ax=(float)Math.sin(roll),ay=(float)Math.sin(pitch),az=(float)-Math.sqrt(Math.max(0,1-ax*ax-ay*ay));
        if(shake&&!centre){if(shakeStarted==0)shakeStarted=now;double phase=(now-shakeStarted)/1_000_000_000.0*Math.PI*14;az+=(float)(Math.sin(phase)*2.2);gx+=(float)(Math.cos(phase)*2.5);}
        else shakeStarted=0;
        PortInput.Motion motion=new PortInput.Motion(ax,ay,az,gx,gy,0,now,true);
        short[] samples=new short[0];
        if(blow&&now>=nextAudio){
            nextAudio=now+50_000_000L;samples=new short[2400];
            for(int i=0;i<samples.length;i++){
                noise^=noise<<13;noise^=noise>>>17;noise^=noise<<5;
                double edge=Math.min(1,Math.min(i/32.0,(samples.length-1-i)/32.0));
                samples[i]=(short)((noise>>16)*.25*edge);
            }
        }
        return new Sample(motion,blow?new PortInput.Microphone(true,48000,samples):PortInput.Microphone.SILENT);
    }
}
