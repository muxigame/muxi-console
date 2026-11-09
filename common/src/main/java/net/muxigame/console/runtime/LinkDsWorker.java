package net.muxigame.console.runtime;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** One DS emulator per isolated process; its network reader never enters the emulator thread. */
public final class LinkDsWorker {
    private static String hash(Path path)throws IOException{
        if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(path))throw new IOException("DS worker needs an ordinary pinned file");
        try{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(var input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){byte[] piece=new byte[65536];for(int n;(n=input.read(piece))>=0;)if(n>0)digest.update(piece,0,n);}return HexFormat.of().formatHex(digest.digest());}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    public static void main(String[] args)throws Exception{
        if(args.length!=2||!args[1].matches("[a-f0-9]{64}"))throw new IOException("Invalid DS worker startup");
        Path spool=Path.of(System.getProperty("jna.tmpdir")).toAbsolutePath().toRealPath();
        try(Socket socket=new Socket("127.0.0.1",Integer.parseInt(args[0]));var input=new DataInputStream(new BufferedInputStream(socket.getInputStream()));var output=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))){
            socket.setTcpNoDelay(true);LinkWire.text(output,args[1]);output.flush();LibretroCore core=null;LinkDsNetpacket bridge=null;LinkDsNetwork.Connection network=null;LinkDsNativeState mpState=null;Path loadedLibrary=null;long frameNumber=0;
            try{boolean quit=false;while(!quit){LinkWire.Packet packet;try{packet=LinkWire.receive(input);}catch(EOFException ended){break;}byte[] response;
                try{var body=LinkWire.input(packet.body());byte[] result;
                    if(packet.operation()==LinkWire.OPEN){
                        if(core!=null)throw new IOException("DS worker already opened");Path library=Path.of(LinkWire.text(body));String libraryHash=LinkWire.text(body);Path rom=Path.of(LinkWire.text(body));String romHash=LinkWire.text(body);UUID machine=UUID.fromString(LinkWire.text(body));Path system=Path.of(LinkWire.text(body)),save=Path.of(LinkWire.text(body));int networkPort=body.readInt();String networkToken=LinkWire.text(body);int port=body.readUnsignedByte(),count=body.readInt();
                        if(count<0||count>256||port>1||Files.size(rom)>512L*1024*1024||!hash(library).equals(libraryHash)||!hash(rom).equals(romHash))throw new IOException("DS worker identity differs");Map<String,String> options=new HashMap<>();for(int i=0;i<count;i++)options.put(LinkWire.text(body),LinkWire.text(body));LinkWire.end(body);
                        options.put("melonds_render_mode","software");options.put("melonds_threaded_renderer","disabled");options.put("melonds_number_of_screen_layouts","1");options.put("melonds_screen_layout1","top-bottom");options.put("melonds_screen_gap","0");options.put("melonds_mac_address_mode",LinkDsIdentity.machineMac(machine));
                        network=new LinkDsNetwork.Connection(new LinkDsNetwork.Address(networkPort,networkToken),port);bridge=new LinkDsNetpacket(port,1-port,network);loadedLibrary=library;
                        core=new LibretroCore(library,rom,system,save,options,"nds",bridge);if(!bridge.configured())throw new IOException("DS core did not expose the required wireless interface");CoreInfo info=core.info();String protocol=bridge.protocol();result=LinkWire.body(out->{LinkWire.text(out,info.name());LinkWire.text(out,info.version());out.writeInt(info.width());out.writeInt(info.height());out.writeDouble(info.fps());out.writeDouble(info.sampleRate());LinkWire.text(out,protocol);});
                    }else{
                        if(core==null&&packet.operation()!=LinkWire.QUIT)throw new IOException("DS worker is not open");
                        result=switch(packet.operation()){
                            case LinkWire.STEP->{PortInput state=InputCodec.readPort(body);LinkWire.end(body);AvFrame raw=core.step(new InputFrame(List.of(state)));AvFrame frame=new AvFrame(frameNumber++,raw.width(),raw.height(),raw.rotation(),raw.rgba(),raw.stereoPcm(),raw.sampleRate(),raw.shutdown(),raw.displayAspect(),raw.surfaces());yield LinkWire.body(out->LinkWire.frame(out,frame));}
                            case LinkWire.EXPORT->{LinkWire.end(body);String name="endpoint-"+UUID.randomUUID()+".mxs1";core.saveStateTo(spool.resolve(name));yield LinkWire.body(out->LinkWire.text(out,name));}
                            case LinkWire.RESTORE->{String name=LinkWire.text(body);LinkWire.end(body);if(bridge.started())throw new IOException("An individual DS state can only restore before wireless execution; it is not a network group checkpoint");if(!name.matches("endpoint-[a-f0-9-]{36}\\.mxs1"))throw new IOException("Invalid DS state spool name");Path source=spool.resolve(name);try(var prefix=new DataInputStream(Files.newInputStream(source,LinkOption.NOFOLLOW_LINKS))){if(prefix.readInt()!=0x4d585331)throw new IOException("An individual DS state must be MXS1, never DNL1");}core.loadStateFrom(source);yield new byte[0];}
                            case 8->{LinkWire.end(body);LinkDsNetpacket.Stats stats=bridge.stats();yield LinkWire.body(out->{out.writeLong(stats.sentPackets());out.writeLong(stats.receivedPackets());out.writeLong(stats.sentBytes());out.writeLong(stats.receivedBytes());});}
                            case 9->{LinkWire.end(body);network.drainBarrier();yield new byte[0];}
                            case 10->{boolean deliver=body.readBoolean();LinkWire.end(body);if(!bridge.started())throw new IOException("DS group checkpoint requires a started wireless frontend");if(deliver)bridge.beforeRun();if(mpState==null)mpState=new LinkDsNativeState(loadedLibrary);String name=LinkDsCheckpoint.save(spool,frameNumber,core,mpState,network);yield LinkWire.body(out->LinkWire.text(out,name));}
                            case 11->{String name=LinkWire.text(body);LinkWire.end(body);if(bridge.started())throw new IOException("DS group restore requires fresh unexecuted workers");if(!name.matches("endpoint-[a-f0-9-]{36}\\.dse1"))throw new IOException("Invalid DS group spool name");bridge.beforeRun();if(mpState==null)mpState=new LinkDsNativeState(loadedLibrary);frameNumber=LinkDsCheckpoint.restore(spool,spool.resolve(name),core,mpState,network);long restoredFrame=frameNumber;yield LinkWire.body(out->out.writeLong(restoredFrame));}
                            case LinkWire.QUIT->{LinkWire.end(body);quit=true;if(core!=null){core.close();core=null;}yield new byte[0];}
                            default->throw new IOException("Unknown DS worker operation");
                        };
                    }
                    response=LinkWire.body(out->{out.writeByte(0);out.write(result);});
                }catch(Exception|LinkageError error){String text=error instanceof IOException?String.valueOf(error.getMessage()):"DS worker rejected "+error.getClass().getSimpleName();String bounded=text.length()>4096?text.substring(0,4096):text;response=LinkWire.body(out->{out.writeByte(1);LinkWire.text(out,bounded);});}
                LinkWire.send(output,packet.operation()|0x8000,packet.sequence(),response);
            }}finally{if(core!=null)core.close();else if(bridge!=null)bridge.close();}
        }
    }
}
