package net.muxigame.console.client.audio;

/** Bounded stereo sample-frame ring; capacity is duration, independent of producer chunk sizes. */
public final class PcmSampleQueue {
    private final short[] ring;private int head,size;private long offered,dropped;
    public PcmSampleQueue(int capacityFrames){if(capacityFrames<1||capacityFrames>48000)throw new IllegalArgumentException("PCM capacity");ring=new short[capacityFrames*2];}
    public synchronized void offer(short[] pcm){if((pcm.length&1)!=0)throw new IllegalArgumentException("Stereo samples");offered+=pcm.length/2;int from=Math.max(0,pcm.length-ring.length);if(from>0)dropped+=from/2;int needed=pcm.length-from;if(size+needed>ring.length){int remove=size+needed-ring.length;head=(head+remove)%ring.length;size-=remove;dropped+=remove/2;}for(int i=from;i<pcm.length;i++)ring[(head+size++)%ring.length]=pcm[i];notifyAll();}
    public synchronized short[] poll(int frames){int count=Math.min(size,frames*2);short[] result=new short[count];for(int i=0;i<count;i++)result[i]=ring[(head+i)%ring.length];head=(head+count)%ring.length;size-=count;return result;}
    public synchronized int frames(){return size/2;}
    public synchronized long offeredFrames(){return offered;}
    public synchronized long droppedFrames(){return dropped;}
    /** Explicit startup catch-up; discarded real samples remain included in the total drop counter. */
    public synchronized long discardTo(int retainedFrames){if(retainedFrames<0||retainedFrames>ring.length/2)throw new IllegalArgumentException("PCM retained duration");int remove=Math.max(0,size-retainedFrames*2);head=(head+remove)%ring.length;size-=remove;dropped+=remove/2;return remove/2;}
    public synchronized void clear(){head=size=0;notifyAll();}
}
