package net.muxigame.console.runtime;

/** Bit order follows the public RetroPad ABI: B,Y,Select,Start,Up,Down,Left,Right,A,X,L,R,L2,R2,L3,R3. */
public record PortInput(int buttons,short leftX,short leftY,short rightX,short rightY,int device,Pointer pointer,Motion motion,Microphone microphone) {
    public record Pointer(String surface,short x,short y,boolean pressed,boolean valid){
        public static final Pointer IDLE=new Pointer("primary",(short)0,(short)0,false,false);
        public Pointer{if(!java.util.List.of("primary","top","bottom").contains(surface)||x==Short.MIN_VALUE||y==Short.MIN_VALUE||pressed&&!valid)throw new IllegalArgumentException("Invalid normalized pointer");}
    }
    /** Acceleration in g and angular velocity in radians/sec, never preconverted to core units. */
    public record Motion(float accelerationX,float accelerationY,float accelerationZ,float gyroX,float gyroY,float gyroZ,long timestampNanos,boolean valid){
        public static final Motion IDLE=new Motion(0,0,-1,0,0,0,0,false);
        public Motion{if(timestampNanos<0||!Float.isFinite(accelerationX)||!Float.isFinite(accelerationY)||!Float.isFinite(accelerationZ)||!Float.isFinite(gyroX)||!Float.isFinite(gyroY)||!Float.isFinite(gyroZ)||Math.abs(accelerationX)>32||Math.abs(accelerationY)>32||Math.abs(accelerationZ)>32||Math.abs(gyroX)>128||Math.abs(gyroY)>128||Math.abs(gyroZ)>128)throw new IllegalArgumentException("Invalid motion input");}
    }
    public record Microphone(boolean connected,int sampleRate,short[] samples){
        public static final int MAX_SAMPLES=2400;
        public static final Microphone SILENT=new Microphone(false,48000,new short[0]);
        public Microphone{if(sampleRate<8000||sampleRate>48000||samples==null||samples.length>MAX_SAMPLES||!connected&&samples.length>0)throw new IllegalArgumentException("Invalid microphone input");samples=samples.clone();}
        @Override public short[] samples(){return samples.clone();}
        public Microphone withoutSamples(){return samples.length==0?this:new Microphone(connected,sampleRate,new short[0]);}
        @Override public boolean equals(Object other){return other instanceof Microphone mic&&connected==mic.connected&&sampleRate==mic.sampleRate&&java.util.Arrays.equals(samples,mic.samples);}
        @Override public int hashCode(){return java.util.Objects.hash(connected,sampleRate,java.util.Arrays.hashCode(samples));}
    }
    public static final PortInput IDLE = new PortInput(0, (short) 0, (short) 0, (short) 0, (short) 0);
    public PortInput {
        if ((buttons & ~0xffff) != 0) throw new IllegalArgumentException("RetroPad mask exceeds 16 bits");
        if(device<0||device>0xffff||device!=0&&((device&255)!=1||(device>>>8)>15)||pointer==null||motion==null||microphone==null)throw new IllegalArgumentException("Invalid controller device or auxiliary input");
    }
    public PortInput(int buttons,short leftX,short leftY,short rightX,short rightY){this(buttons,leftX,leftY,rightX,rightY,1,Pointer.IDLE,Motion.IDLE,Microphone.SILENT);}
    public PortInput withoutMicrophone(){return new PortInput(buttons,leftX,leftY,rightX,rightY,device,pointer,motion,microphone.withoutSamples());}
    public PortInput withPointer(Pointer value){return new PortInput(buttons,leftX,leftY,rightX,rightY,device,value,motion,microphone);}
    public PortInput(int buttons) { this(buttons, (short) 0, (short) 0, (short) 0, (short) 0); }
}
