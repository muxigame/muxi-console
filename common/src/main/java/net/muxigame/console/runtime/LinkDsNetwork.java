package net.muxigame.console.runtime;

import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Authenticated local packet switch between two independently executing DS worker processes. */
public final class LinkDsNetwork implements AutoCloseable {
    private static final int MAGIC=0x4d58444e,VERSION=2,CAPACITY=128,MARKER=128,ACK=129;
    public record Address(int port,String token){
        public Address{if(port<1||port>65535||token==null||!token.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Invalid wireless address");}
        @Override public String toString(){return "DS wireless loopback address [redacted]";}
    }
    private record Envelope(int sender,int flags,int target,byte[] bytes){}
    private final ServerSocket listener=new ServerSocket();
    private final String token;
    private final Socket[] sockets=new Socket[2];
    private final List<Thread> threads=new CopyOnWriteArrayList<>();
    private final List<BlockingQueue<Envelope>> queues=List.of(new ArrayBlockingQueue<>(CAPACITY),new ArrayBlockingQueue<>(CAPACITY));
    private final CountDownLatch connected=new CountDownLatch(2);
    private volatile IOException failure;private volatile boolean closed;
    public LinkDsNetwork()throws IOException{
        byte[] random=new byte[32];new SecureRandom().nextBytes(random);token=HexFormat.of().formatHex(random);
        listener.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),2);listener.setSoTimeout(250);thread("accept",this::accept);
    }
    public Address address(){return new Address(listener.getLocalPort(),token);}
    public void awaitConnected(long timeoutMillis)throws IOException{
        if(timeoutMillis<1||timeoutMillis>30000)throw new IOException("Invalid wireless connection timeout");long end=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while(connected.getCount()!=0){check();try{connected.await(25,TimeUnit.MILLISECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("Wireless connection interrupted");}if(System.nanoTime()-end>=0)throw new IOException("Both DS workers did not connect");}check();
    }
    private interface Io{void run()throws IOException;}
    private void thread(String name,Io action){Thread t=new Thread(()->{try{action.run();}catch(IOException error){fail(error);}catch(RuntimeException error){fail(new IOException("Wireless transport failed",error));}},"muxi-ds-network-"+name);t.setDaemon(true);threads.add(t);t.start();}
    private void accept()throws IOException{
        while(!closed&&connected.getCount()>0){
            Socket socket;try{socket=listener.accept();}catch(SocketTimeoutException waiting){continue;}
            boolean adopted=false;
            try{
                socket.setSoTimeout(5000);socket.setTcpNoDelay(true);var in=new DataInputStream(new BufferedInputStream(socket.getInputStream()));var out=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                int magic=in.readInt(),version=in.readUnsignedShort(),port=in.readUnsignedByte();byte[] supplied=in.readNBytes(32);
                if(magic!=MAGIC||version!=VERSION||port>1||supplied.length!=32||!MessageDigest.isEqual(supplied,HexFormat.of().parseHex(token)))continue;
                synchronized(sockets){if(sockets[port]!=null)continue;sockets[port]=socket;adopted=true;}
                socket.setSoTimeout(0);out.writeInt(MAGIC);out.writeByte(port);out.flush();connected.countDown();
                final int sender=port;thread("read-"+port,()->route(sender,in));thread("write-"+port,()->deliver(sender,out));
            }finally{if(!adopted)socket.close();}
        }
    }
    private void route(int sender,DataInputStream input)throws IOException{
        while(!closed){int flags=input.readUnsignedByte(),target=input.readUnsignedShort(),length=input.readInt();if(flags==MARKER||flags==ACK){if(target!=1-sender||length!=8)throw new IOException("Invalid wireless barrier");}else validate(flags,target,length,1-sender);byte[] bytes=input.readNBytes(length);if(bytes.length!=length)throw new EOFException();if(length==0)continue;
            if(!queues.get(1-sender).offer(new Envelope(sender,flags,target,bytes)))throw new IOException("Wireless receiver queue exceeded its bound");
        }
    }
    private void deliver(int port,DataOutputStream output)throws IOException{
        while(!closed){Envelope packet;try{packet=queues.get(port).poll(100,TimeUnit.MILLISECONDS);}catch(InterruptedException interrupted){if(closed)return;Thread.currentThread().interrupt();throw new IOException("Wireless delivery interrupted");}if(packet==null)continue;
            output.writeByte(packet.flags);output.writeByte(packet.sender);output.writeInt(packet.bytes.length);output.write(packet.bytes);output.flush();
        }
    }
    private static void validate(int flags,int target,int length,int peer)throws IOException{if((flags&~7)!=0||(flags&3)==3||(target!=peer&&target!=65535)||length<0||length>65536)throw new IOException("Invalid wireless transport packet");}
    private void check()throws IOException{if(failure!=null)throw failure;if(closed)throw new IOException("Wireless packet switch closed");}
    private void fail(IOException error){if(!closed&&failure==null){failure=error;for(Socket socket:sockets)if(socket!=null)try{socket.close();}catch(IOException ignored){}}}
    @Override public void close()throws IOException{
        if(closed)return;closed=true;IOException error=null;try{listener.close();}catch(IOException failure){error=failure;}
        for(Socket socket:sockets)if(socket!=null)try{socket.close();}catch(IOException failure){if(error==null)error=failure;}
        for(Thread thread:threads)thread.interrupt();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);
        for(Thread thread:threads)if(thread!=Thread.currentThread()){long remaining=end-System.nanoTime();if(remaining<=0)break;try{thread.join(Math.max(1,TimeUnit.NANOSECONDS.toMillis(remaining)));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();break;}}
        queues.forEach(Queue::clear);if(error!=null)throw error;
    }
    /** Each connection belongs to exactly one native worker; its threads never invoke libretro callbacks. */
    public static final class Connection implements LinkDsNetpacket.Transport {
        private final Socket socket;private final int port;private final DataInputStream input;private final DataOutputStream output;
        private final BlockingQueue<Envelope> outgoing=new ArrayBlockingQueue<>(CAPACITY);
        private final BlockingQueue<LinkDsNetpacket.Packet> incoming=new ArrayBlockingQueue<>(CAPACITY);
        private final Map<Long,CountDownLatch> barriers=new ConcurrentHashMap<>();private long barrierSequence;
        private final List<Thread> threads=new ArrayList<>();private volatile IOException failure;private volatile boolean closed;
        public Connection(Address address,int port)throws IOException{
            if(port<0||port>1)throw new IOException("Invalid DS worker port");this.port=port;socket=new Socket();
            try{socket.connect(new InetSocketAddress("127.0.0.1",address.port),5000);socket.setSoTimeout(5000);socket.setTcpNoDelay(true);input=new DataInputStream(new BufferedInputStream(socket.getInputStream()));output=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));output.writeInt(MAGIC);output.writeShort(VERSION);output.writeByte(port);output.write(HexFormat.of().parseHex(address.token));output.flush();if(input.readInt()!=MAGIC||input.readUnsignedByte()!=port)throw new IOException("Wireless worker authentication failed");socket.setSoTimeout(0);thread("read",this::read);thread("write",this::write);}
            catch(IOException|RuntimeException error){try{socket.close();}catch(IOException cleanup){error.addSuppressed(cleanup);}throw error;}
        }
        private void thread(String suffix,Io action){Thread t=new Thread(()->{try{action.run();}catch(IOException error){fail(error);}catch(RuntimeException error){fail(new IOException("Wireless worker transport failed",error));}},"muxi-ds-packet-"+port+"-"+suffix);t.setDaemon(true);threads.add(t);t.start();}
        private void fail(IOException error){if(!closed&&failure==null){failure=error;try{socket.close();}catch(IOException ignored){}}}
        private void check()throws IOException{if(failure!=null)throw new IOException("DS wireless connection ended",failure);if(closed)throw new IOException("DS wireless connection closed");}
        @Override public void send(int flags,int target,byte[] bytes)throws IOException{check();if(bytes==null)throw new IOException("Missing wireless payload");validate(flags,target,bytes.length,1-port);
            // Every real packet is already flushed by write(). The core can spin
            // thousands of zero-byte FLUSH_HINT polls while waiting for radio data;
            // these hints carry no packet and must not fill a bounded payload queue.
            if(bytes.length==0)return;
            if(!outgoing.offer(new Envelope(port,flags,target,bytes.clone())))throw new IOException("DS wireless send queue exceeded its bound");}
        @Override public LinkDsNetpacket.Packet poll()throws IOException{check();return incoming.poll();}
        private void read()throws IOException{while(!closed){int flags=input.readUnsignedByte(),sender=input.readUnsignedByte(),length=input.readInt();if(sender!=1-port||length<1||length>65536)throw new IOException("Wireless peer payload exceeds bounds");byte[] bytes=input.readNBytes(length);if(bytes.length!=length)throw new EOFException();
            if(flags==MARKER){if(length!=8)throw new IOException("Invalid wireless barrier marker");if(!outgoing.offer(new Envelope(port,ACK,1-port,bytes)))throw new IOException("Wireless barrier queue exceeded bound");continue;}
            if(flags==ACK){if(length!=8)throw new IOException("Invalid wireless barrier acknowledgement");long id=new DataInputStream(new ByteArrayInputStream(bytes)).readLong();CountDownLatch latch=barriers.get(id);if(latch!=null)latch.countDown();continue;}
            validate(flags,port,length,port);if(!incoming.offer(new LinkDsNetpacket.Packet(sender,bytes)))throw new IOException("DS wireless core queue exceeded its bound");}}
        private void write()throws IOException{while(!closed){Envelope packet;try{packet=outgoing.poll(100,TimeUnit.MILLISECONDS);}catch(InterruptedException interrupted){if(closed)return;Thread.currentThread().interrupt();throw new IOException("Wireless sender interrupted");}if(packet==null)continue;output.writeByte(packet.flags);output.writeShort(packet.target);output.writeInt(packet.bytes.length);output.write(packet.bytes);output.flush();}}
        /** Caller has paused both native cores. The round trip orders all pre-barrier data into the opposite incoming queue. */
        public void drainBarrier()throws IOException{
            check();long id=++barrierSequence;CountDownLatch done=new CountDownLatch(1);barriers.put(id,done);
            try{var bytes=new ByteArrayOutputStream();new DataOutputStream(bytes).writeLong(id);if(!outgoing.offer(new Envelope(port,MARKER,1-port,bytes.toByteArray())))throw new IOException("Wireless barrier queue exhausted");long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);while(done.getCount()!=0){check();try{done.await(10,TimeUnit.MILLISECONDS);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new IOException("Wireless barrier interrupted");}if(System.nanoTime()-end>=0)throw new IOException("Wireless queues did not reach a common barrier");}}
            finally{barriers.remove(id,done);}
        }
        public byte[] snapshotIncoming()throws IOException{
            check();var bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){out.writeInt(0x44515431);Object[] packets=incoming.toArray();out.writeInt(packets.length);for(Object entry:packets){LinkDsNetpacket.Packet packet=(LinkDsNetpacket.Packet)entry;byte[] payload=packet.bytes();out.writeShort(packet.sender());out.writeInt(payload.length);out.write(payload);}}return bytes.toByteArray();
        }
        public void restoreIncoming(byte[] bytes)throws IOException{
            check();if(bytes==null||bytes.length>CAPACITY*(65536+6)+8)throw new IOException("Wireless queue state exceeds bound");var in=new DataInputStream(new ByteArrayInputStream(bytes));if(in.readInt()!=0x44515431)throw new IOException("Invalid wireless queue format");int count=in.readInt();if(count<0||count>CAPACITY)throw new IOException("Wireless queue count exceeds bound");List<LinkDsNetpacket.Packet> restored=new ArrayList<>();for(int i=0;i<count;i++){int sender=in.readUnsignedShort(),length=in.readInt();if(sender!=1-port||length<1||length>65536||length>in.available())throw new IOException("Invalid saved wireless packet");restored.add(new LinkDsNetpacket.Packet(sender,in.readNBytes(length)));}if(in.available()!=0||!incoming.isEmpty())throw new IOException("Wireless restore needs an empty current incoming queue");for(var packet:restored)if(!incoming.offer(packet))throw new IOException("Wireless restored queue exceeds bound");
        }
        @Override public void close()throws IOException{if(closed)return;closed=true;socket.close();for(Thread thread:threads)thread.interrupt();long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(1);for(Thread thread:threads){long remaining=end-System.nanoTime();if(remaining<=0)break;try{thread.join(Math.max(1,TimeUnit.NANOSECONDS.toMillis(remaining)));}catch(InterruptedException interrupted){Thread.currentThread().interrupt();break;}}outgoing.clear();incoming.clear();}
    }
}
