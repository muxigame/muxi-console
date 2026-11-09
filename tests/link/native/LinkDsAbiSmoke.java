package net.muxigame.console.tests.link;

import com.sun.jna.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import net.muxigame.console.runtime.*;

/** Real JNA callback trampolines and authenticated local sockets; this test does not emulate DS radio hardware. */
public final class LinkDsAbiSmoke {
    public interface Start extends Callback{void invoke(short id,Pointer send,Pointer poll);}
    public interface Receive extends Callback{void invoke(Pointer bytes,LinkDsNetpacket.SizeT count,short sender);}
    public interface Empty extends Callback{void invoke();}
    public interface Connected extends Callback{byte invoke(short peer);}
    public interface Disconnected extends Callback{void invoke(short peer);}
    private static final AtomicInteger checks=new AtomicInteger();
    private static void check(boolean ok,String text){checks.incrementAndGet();if(!ok)throw new AssertionError(text);}
    private static int run(int port,LinkDsNetwork.Connection connection)throws Exception{
        Thread owner=Thread.currentThread();int[] received={0},stops={0},joins={0},leaves={0},polls={0};Function[] outgoing=new Function[1],pollReceive=new Function[1];
        Start start=(id,send,poll)->{check(Short.toUnsignedInt(id)==port,"native start receives own client id");outgoing[0]=Function.getFunction(send);pollReceive[0]=Function.getFunction(poll);};
        Receive receive=(bytes,count,sender)->{check(Thread.currentThread()==owner,"network reader never reenters the native core");check(Short.toUnsignedInt(sender)==1-port,"authenticated other client is the sender");check(count.longValue()==11&&bytes.getByte(10)==(byte)(port==0?0xa7:0x3c),"payload crosses the actual socket unchanged");received[0]++;};
        Empty stop=()->stops[0]++,poll=()->polls[0]++;Connected connected=peer->{joins[0]++;return 1;};Disconnected disconnected=peer->leaves[0]++;
        LinkDsNetpacket bridge=new LinkDsNetpacket(port,1-port,connection);try(Memory table=new Memory(7L*Native.POINTER_SIZE);Memory protocol=new Memory(5)){
            protocol.setString(0,"test");List<Callback> callbacks=List.of(start,receive,stop,poll,connected,disconnected);for(int i=0;i<callbacks.size();i++)table.setPointer((long)i*Native.POINTER_SIZE,CallbackReference.getFunctionPointer(callbacks.get(i)));table.setPointer(6L*Native.POINTER_SIZE,protocol);
            check(bridge.configure(table),"native callback table accepted");check(!bridge.configure(table),"duplicate registration rejected");check(bridge.protocol().equals("test"),"protocol copied from temporary registration table");table.clear();bridge.beforeRun();
            try(Memory packet=new Memory(11)){packet.clear();packet.setByte(10,(byte)(port==0?0x3c:0xa7));outgoing[0].invokeVoid(new Object[]{4,packet,new LinkDsNetpacket.SizeT(11),(short)65535});packet.setByte(10,(byte)0);}
            outgoing[0].invokeVoid(new Object[]{4,Pointer.NULL,new LinkDsNetpacket.SizeT(0),(short)65535});
            long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);while(received[0]==0&&System.nanoTime()<end){bridge.beforeRun();Thread.sleep(1);}
            check(received[0]==1,"peer receives one payload; zero-length FLUSH never becomes a fake packet");check(bridge.stats().sentPackets()==1&&bridge.stats().receivedPackets()==1,"real payload statistics exclude flush-only calls");check(bridge.stats().sentBytes()==11&&bridge.stats().receivedBytes()==11,"bounded byte counters match actual payload");
            outgoing[0].invokeVoid(new Object[]{0,Pointer.NULL,new LinkDsNetpacket.SizeT(65537),(short)(1-port)});boolean refused=false;try{bridge.beforeRun();}catch(IOException expected){refused=true;}check(refused,"oversized native send fails closed before reading native memory");
        }finally{bridge.close();bridge.close();}
        check(stops[0]==1,"native stop called once while callbacks are still loaded");check(joins[0]==(port==0?1:0)&&leaves[0]==(port==0?1:0),"host-only connected/disconnected callbacks preserve client topology");check(polls[0]>0,"core poll is called between frames");return received[0];
    }
    public static void main(String[] args)throws Exception{
        byte[][] queued=new byte[2][];
        try(LinkDsNetwork network=new LinkDsNetwork();var a=new LinkDsNetwork.Connection(network.address(),0);var b=new LinkDsNetwork.Connection(network.address(),1)){
            network.awaitConnected(1000);a.send(4,1,new byte[]{3,12});b.send(4,0,new byte[]{7,21});a.drainBarrier();queued[0]=a.snapshotIncoming();queued[1]=b.snapshotIncoming();check(Arrays.equals(a.poll().bytes(),new byte[]{7,21})&&Arrays.equals(b.poll().bytes(),new byte[]{3,12}),"round-trip barrier drains both directions while no core callback consumes incoming queues");check(a.poll()==null&&b.poll()==null,"barrier and acknowledgement never enter the emulated radio queue");
        }
        try(LinkDsNetwork network=new LinkDsNetwork();var a=new LinkDsNetwork.Connection(network.address(),0);var b=new LinkDsNetwork.Connection(network.address(),1)){
            network.awaitConnected(1000);a.restoreIncoming(queued[0]);b.restoreIncoming(queued[1]);check(Arrays.equals(a.poll().bytes(),new byte[]{7,21})&&Arrays.equals(b.poll().bytes(),new byte[]{3,12}),"fresh connections restore actual pending peer packets with their original sender");check(a.poll()==null&&b.poll()==null,"restored queue has no replay or fabricated extra packet");
        }
        ExecutorService cores=Executors.newFixedThreadPool(2);try(LinkDsNetwork network=new LinkDsNetwork();var a=new LinkDsNetwork.Connection(network.address(),0);var b=new LinkDsNetwork.Connection(network.address(),1)){
            network.awaitConnected(1000);Future<Integer> first=cores.submit(()->run(0,a)),second=cores.submit(()->run(1,b));check(first.get(5,TimeUnit.SECONDS)==1&&second.get(5,TimeUnit.SECONDS)==1,"both independent core threads complete actual transport");
            check(!network.address().toString().contains(network.address().token()),"address diagnostics redact authentication token");
        }finally{cores.shutdownNow();}
        System.out.println("LinkDsAbiSmoke "+checks.get()+" checks passed; callback/socket contract only, not DS wireless game compatibility");
    }
}
