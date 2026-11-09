package net.muxigame.console.runtime;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** One 3DS CPU and private frontend member per process. Room initialization precedes NWM construction. */
public final class Link3dsWorker {
    static final int STATUS=8,HOST=9,JOIN=10,BOOT=11;
    static String hash(Path path)throws IOException {
        ordinary(path);try {MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream input=Files.newInputStream(path,LinkOption.NOFOLLOW_LINKS)){byte[] bytes=new byte[65536];for(int n;(n=input.read(bytes))!=-1;)if(n>0)digest.update(bytes,0,n);}return HexFormat.of().formatHex(digest.digest());}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    static void ordinary(Path path)throws IOException {if(!path.isAbsolute()||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||!path.equals(path.toRealPath()))throw new IOException("3DS worker requires an ordinary local file");}
    static void writeStatus(DataOutputStream out,Link3dsNative.Status status)throws IOException {
        out.writeInt(status.abi());out.writeBoolean(status.initialized());out.writeBoolean(status.roomOpen());out.writeInt(status.memberState());out.writeLong(status.lastError());
        out.writeLong(status.sentPackets());out.writeLong(status.sentBytes());out.writeLong(status.receivedPackets());out.writeLong(status.receivedBytes());out.writeLong(status.sentDataPackets());out.writeLong(status.receivedDataPackets());out.writeInt(status.profileStateAbi());
    }
    static Link3dsNative.Status readStatus(DataInputStream in)throws IOException {
        var status=new Link3dsNative.Status(in.readInt(),in.readBoolean(),in.readBoolean(),in.readInt(),in.readLong(),in.readLong(),in.readLong(),in.readLong(),in.readLong(),in.readLong(),in.readLong(),in.readInt());
        if(status.abi()!=1||status.memberState()<0||status.memberState()>4||!Link3dsNative.supportedProfileAbi(status.profileStateAbi())||status.sentPackets()<0||status.receivedPackets()<0||status.sentBytes()<0||status.receivedBytes()<0||status.sentDataPackets()<0||status.receivedDataPackets()<0)throw new IOException("Invalid 3DS worker native status");return status;
    }
    public static void main(String[] arguments)throws Exception {
        if(arguments.length!=2||!arguments[1].matches("[a-f0-9]{64}"))throw new IOException("Invalid 3DS worker startup");
        Path spool=Path.of(System.getProperty("jna.tmpdir")).toAbsolutePath().toRealPath();
        try(Socket socket=new Socket("127.0.0.1",Integer.parseInt(arguments[0]));var input=new DataInputStream(new BufferedInputStream(socket.getInputStream()));var output=new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))) {
            socket.setTcpNoDelay(true);LinkWire.text(output,arguments[1]);output.flush();
            LibretroCore core=null;Link3dsNative room=null;Path library=null,rom=null,system=null,save=null;UUID machine=null;Map<String,String> options=null;long frameNumber=0;
            try {
                boolean quit=false;
                while(!quit) {
                    LinkWire.Packet packet;try{packet=LinkWire.receive(input);}catch(EOFException ended){break;}
                    byte[] response;
                    try {
                        var body=LinkWire.input(packet.body());byte[] result;
                        if(packet.operation()==LinkWire.OPEN) {
                            if(room!=null)throw new IOException("3DS worker already initialized");
                            library=Path.of(LinkWire.text(body));String expectedLibrary=LinkWire.text(body);rom=Path.of(LinkWire.text(body));String expectedRom=LinkWire.text(body);machine=UUID.fromString(LinkWire.text(body));system=Path.of(LinkWire.text(body));save=Path.of(LinkWire.text(body));int count=body.readInt();
                            if(count<0||count>256||!expectedLibrary.matches("[a-f0-9]{64}")||!expectedRom.matches("[a-f0-9]{64}")||Files.size(rom)<1||Files.size(rom)>8L*1024*1024*1024||!hash(library).equals(expectedLibrary)||!hash(rom).equals(expectedRom))throw new IOException("3DS worker file identity differs");
                            options=new HashMap<>();for(int i=0;i<count;i++)options.put(LinkWire.text(body),LinkWire.text(body));LinkWire.end(body);
                            options.putAll(Link3dsIdentity.singleOptions(machine));room=new Link3dsNative(library);
                            Link3dsNative.Status status=room.status();result=LinkWire.body(out->writeStatus(out,status));
                        } else if(packet.operation()==LinkWire.QUIT) {
                            LinkWire.end(body);quit=true;if(core!=null){core.close();core=null;}if(room!=null){room.close();room=null;}result=new byte[0];
                        } else {
                            if(room==null)throw new IOException("3DS frontend is not initialized");
                            switch(packet.operation()) {
                                case HOST->{int port=body.readInt();String password=LinkWire.text(body);LinkWire.end(body);if(core!=null)throw new IOException("3DS Room must be created before NWM");room.host(port,password);result=new byte[0];}
                                case JOIN->{int port=body.readInt();String password=LinkWire.text(body);LinkWire.end(body);if(core!=null)throw new IOException("3DS Room must be joined before NWM");room.join(port,password,machine);result=new byte[0];}
                                case STATUS->{LinkWire.end(body);Link3dsNative.Status status=room.status();result=LinkWire.body(out->writeStatus(out,status));}
                                case BOOT->{LinkWire.end(body);if(core!=null||!room.status().joined())throw new IOException("3DS NWM startup requires a joined private Room");core=new LibretroCore(library,rom,system,save,options,"n3ds");CoreInfo info=core.info();result=LinkWire.body(out->{LinkWire.text(out,info.name());LinkWire.text(out,info.version());out.writeInt(info.width());out.writeInt(info.height());out.writeDouble(info.fps());out.writeDouble(info.sampleRate());out.writeDouble(info.displayAspect());});}
                                case LinkWire.STEP->{if(core==null)throw new IOException("3DS core not booted");PortInput state=InputCodec.readPort(body);LinkWire.end(body);AvFrame raw=core.step(new InputFrame(List.of(state)));AvFrame frame=new AvFrame(frameNumber++,raw.width(),raw.height(),raw.rotation(),raw.rgba(),raw.stereoPcm(),raw.sampleRate(),raw.shutdown(),raw.displayAspect(),raw.surfaces());result=LinkWire.body(out->LinkWire.frame(out,frame));}
                                case LinkWire.EXPORT->{if(core==null)throw new IOException("3DS core not booted");LinkWire.end(body);String name="endpoint-"+UUID.randomUUID()+".mxs1";core.saveStateTo(spool.resolve(name));result=LinkWire.body(out->LinkWire.text(out,name));}
                                case LinkWire.RESTORE->{if(core==null||frameNumber!=0)throw new IOException("A 3DS ordinary state may restore only before wireless execution");String name=LinkWire.text(body);LinkWire.end(body);if(!name.matches("endpoint-[a-f0-9-]{36}\\.mxs1"))throw new IOException("Invalid 3DS state spool");Path source=spool.resolve(name);ordinary(source);if(Files.size(source)<16||Files.size(source)>512L*1024*1024)throw new IOException("3DS state exceeds bound");core.loadStateFrom(source);result=new byte[0];}
                                default->throw new IOException("Unknown 3DS worker operation");
                            }
                        }
                        response=LinkWire.body(out->{out.writeByte(0);out.write(result);});
                    } catch(Exception|LinkageError failure) {
                        String message=failure instanceof IOException?String.valueOf(failure.getMessage()):"3DS worker rejected "+failure.getClass().getSimpleName();String bounded=message.length()>4096?message.substring(0,4096):message;
                        response=LinkWire.body(out->{out.writeByte(1);LinkWire.text(out,bounded);});
                    }
                    LinkWire.send(output,packet.operation()|0x8000,packet.sequence(),response);
                }
            } finally {try{if(core!=null)core.close();}finally{if(room!=null)room.close();}}
        }
    }
}
