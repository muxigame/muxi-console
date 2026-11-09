package net.muxigame.console.runtime;

import com.sun.jna.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Worker-only NETPACKET frontend. Network threads queue bytes; only the core thread enters native callbacks. */
public final class LinkDsNetpacket implements LinkNetpacket {
    public static final int MAX_PACKET=65536, BROADCAST=65535;
    public record Packet(int sender,byte[] bytes) {
        public Packet { if(sender<0||sender>=BROADCAST||bytes==null||bytes.length>MAX_PACKET)throw new IllegalArgumentException("Invalid wireless packet");bytes=bytes.clone(); }
        @Override public byte[] bytes(){return bytes.clone();}
    }
    /** Implementations must enqueue send without blocking, and poll without waiting or invoking native code. */
    public interface Transport extends AutoCloseable {
        void send(int flags,int target,byte[] bytes)throws IOException;
        Packet poll()throws IOException;
        @Override void close()throws IOException;
    }
    public static final class SizeT extends IntegerType {
        public SizeT(){this(0);}public SizeT(long value){super(Native.SIZE_T_SIZE,value,true);}
    }
    public interface Send extends Callback {void invoke(int flags,Pointer data,SizeT size,short target);}
    public interface Poll extends Callback {void invoke();}
    private final int clientId,peerId;
    private final Transport transport;
    private final Thread owner=Thread.currentThread();
    private Function start,receive,stop,poll,connected,disconnected;
    private String protocol="";
    private boolean configured,started,closed,insidePoll;
    private IOException callbackFailure;
    private long sentPackets,receivedPackets,sentBytes,receivedBytes;
    private final Send sendCallback=this::send;
    private final Poll pollCallback=()->{try{drain();}catch(IOException failure){remember(failure);}};

    public LinkDsNetpacket(int clientId,int peerId,Transport transport){
        if(clientId<0||clientId>1||peerId!=1-clientId)throw new IllegalArgumentException("DS wireless needs two distinct local clients");
        this.clientId=clientId;this.peerId=peerId;this.transport=Objects.requireNonNull(transport);
    }
    private void owner()throws IOException{if(Thread.currentThread()!=owner||closed)throw new IOException("Wireless callback outside its active core thread");}
    private static Function function(Pointer table,int index){Pointer value=table.getPointer((long)index*Native.POINTER_SIZE);return value==null?null:Function.getFunction(value);}
    @Override public boolean configure(Pointer callbacks)throws IOException{
        owner();if(callbacks==null||configured)return false;
        Function nextStart=function(callbacks,0),nextReceive=function(callbacks,1);if(nextStart==null||nextReceive==null)return false;
        Pointer text=callbacks.getPointer(6L*Native.POINTER_SIZE);
        if(text!=null){byte[] value=new byte[256];int length=0;while(length<value.length&&text.getByte(length)!=0){value[length]=text.getByte(length);length++;}if(length==value.length)throw new IOException("Wireless protocol identifier exceeds bound");protocol=new String(value,0,length,StandardCharsets.UTF_8);}
        start=nextStart;receive=nextReceive;stop=function(callbacks,2);poll=function(callbacks,3);connected=function(callbacks,4);disconnected=function(callbacks,5);configured=true;return true;
    }
    public boolean configured(){return configured;}
    public boolean started(){return started;}
    public String protocol(){return protocol;}
    public record Stats(long sentPackets,long receivedPackets,long sentBytes,long receivedBytes){}
    public Stats stats(){return new Stats(sentPackets,receivedPackets,sentBytes,receivedBytes);}
    @Override public void beforeRun()throws IOException{
        owner();checkFailure();if(!configured)throw new IOException("DS core did not negotiate NETPACKET");
        if(!started){
            started=true;start.invokeVoid(new Object[]{(short)clientId,CallbackReference.getFunctionPointer(sendCallback),CallbackReference.getFunctionPointer(pollCallback)});
            if(clientId==0&&connected!=null&&connected.invokeInt(new Object[]{(short)peerId})==0)throw new IOException("DS core rejected the wireless peer");
        }
        drain();if(poll!=null)poll.invokeVoid(new Object[0]);checkFailure();
    }
    private void send(int flags,Pointer data,SizeT size,short target){
        try{
            owner();if(!started)throw new IOException("DS core sent before network start");
            long count=size.longValue();int destination=Short.toUnsignedInt(target);
            if((flags&~7)!=0||(flags&3)==3||count<0||count>MAX_PACKET||(count>0&&data==null)||(destination!=BROADCAST&&destination!=peerId))throw new IOException("DS core supplied an invalid wireless packet");
            // A zero-size FLUSH_HINT is a real flush request, never a fabricated received packet.
            byte[] bytes=count==0?new byte[0]:data.getByteArray(0,(int)count);transport.send(flags,destination,bytes);
            if(count>0){sentPackets++;sentBytes+=count;}
        }catch(IOException|RuntimeException failure){remember(failure instanceof IOException io?io:new IOException("DS wireless send callback failed",failure));}
    }
    private void drain()throws IOException{
        owner();checkFailure();if(!started||insidePoll)return;insidePoll=true;
        try{
            // Keep native polling bounded even if a peer floods the transport.
            for(int count=0;count<256;count++){
                Packet packet=transport.poll();if(packet==null)break;if(packet.sender()!=peerId)throw new IOException("Wireless packet has the wrong peer identity");
                byte[] bytes=packet.bytes();if(bytes.length==0)throw new IOException("Empty wireless payload is not a received packet");
                try(Memory memory=new Memory(bytes.length)){memory.write(0,bytes,0,bytes.length);receive.invokeVoid(new Object[]{memory,new SizeT(bytes.length),(short)packet.sender()});}
                receivedPackets++;receivedBytes+=bytes.length;
            }
        }finally{insidePoll=false;}
        checkFailure();
    }
    private void remember(IOException failure){if(callbackFailure==null)callbackFailure=failure;}
    private void checkFailure()throws IOException{if(callbackFailure!=null)throw callbackFailure;}
    @Override public void close()throws IOException{
        if(closed)return;owner();IOException failure=null;
        try{if(started){if(clientId==0&&disconnected!=null)disconnected.invokeVoid(new Object[]{(short)peerId});if(stop!=null)stop.invokeVoid(new Object[0]);}}
        catch(RuntimeException error){failure=new IOException("DS wireless core closure failed",error);}
        finally{started=false;closed=true;try{transport.close();}catch(IOException error){if(failure==null)failure=error;else failure.addSuppressed(error);}}
        if(failure!=null)throw failure;
    }
}
