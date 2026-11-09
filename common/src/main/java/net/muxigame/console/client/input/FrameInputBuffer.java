package net.muxigame.console.client.input;

import java.util.ArrayDeque;
import java.util.List;
import net.muxigame.console.runtime.PortInput;

/** Main-thread levels with bounded digital transitions, consumed once per actual core frame. */
public final class FrameInputBuffer {
    private final int capacity;private final long expiryNanos;
    private record Edge(PortInput input,long sequence,long at){}
    public record Applied(long sequence,long sampledAtNanos,long appliedAtNanos,long frame,int buttons){}
    private final ArrayDeque<Edge> edges=new ArrayDeque<>();private final ArrayDeque<Applied> trace=new ArrayDeque<>();private int appliedButtons;
    private PortInput latest=PortInput.IDLE;private PortInput.Microphone pendingMic=PortInput.Microphone.SILENT;private long sequence=-1,publishedAt;private long published,consumed,dropped;private int peak;
    public FrameInputBuffer(int capacity,long expiryNanos){if(capacity<2||capacity>256||expiryNanos<1)throw new IllegalArgumentException("Input bounds");this.capacity=capacity;this.expiryNanos=expiryNanos;}
    public synchronized boolean publish(long next,PortInput input,long now){
        if(next<0)throw new IllegalArgumentException("Input sequence");if(next<=sequence)return false;
        boolean changed=input.buttons()!=latest.buttons()||input.device()!=latest.device()||input.pointer().pressed()!=latest.pointer().pressed()||input.pointer().valid()!=latest.pointer().valid();
        if(changed){if(edges.size()==capacity){dropped+=edges.size();edges.clear();}edges.addLast(new Edge(input.withoutMicrophone(),next,now));peak=Math.max(peak,edges.size());}
        if(!input.microphone().connected()||input.microphone().samples().length>0)pendingMic=input.microphone();latest=input;sequence=next;publishedAt=now;published++;return true;
    }
    public synchronized PortInput take(long now){return take(now,-1);}
    public synchronized PortInput take(long now,long frame){
        if(sequence<0||now-publishedAt>expiryNanos){clearLevels();return PortInput.IDLE;}
        while(!edges.isEmpty()&&now-edges.peekFirst().at>expiryNanos){edges.removeFirst();dropped++;}
        Edge edge=edges.pollFirst();PortInput selected=edge==null?latest:edge.input;consumed++;
        if(selected.buttons()!=appliedButtons){if(trace.size()==128)trace.removeFirst();trace.addLast(new Applied(edge==null?sequence:edge.sequence,edge==null?publishedAt:edge.at,now,frame,selected.buttons()));appliedButtons=selected.buttons();}
        PortInput.Microphone mic=pendingMic;pendingMic=pendingMic.withoutSamples();
        return new PortInput(selected.buttons(),latest.leftX(),latest.leftY(),latest.rightX(),latest.rightY(),selected.device(),edge==null?latest.pointer():selected.pointer(),latest.motion(),mic);
    }
    private void clearLevels(){edges.clear();latest=PortInput.IDLE;pendingMic=PortInput.Microphone.SILENT;}
    /** Clearing focus/ownership also retires all pending presses; the wire sequence never rewinds. */
    public synchronized void clear(){clearLevels();}
    public record Metrics(long sequence,long published,long consumed,int queued,int peakQueued,long droppedTransitions,List<Applied> applied){}
    public synchronized Metrics metrics(){return new Metrics(sequence,published,consumed,edges.size(),peak,dropped,List.copyOf(trace));}
}
