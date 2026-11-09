package net.muxigame.console.runtime;

import com.google.gson.*;
import com.sun.jna.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;

/** Private offline diagnostic process. No Minecraft, fabricated frame or injected game memory. */
public final class Link3dsRoomWorker {
    public interface Room extends Library {
        int muxi_azahar_link_init();
        int muxi_azahar_link_host(short port,String password);
        int muxi_azahar_link_join(short port,String nickname,String consoleId,byte[] mac,String password);
        int muxi_azahar_link_status(Pointer output,LibretroCore.SizeT capacity);
        int muxi_azahar_profile_state_abi();
        void muxi_azahar_link_leave();
        void muxi_azahar_link_shutdown();
    }
    private static final Gson JSON=new Gson();
    private static JsonObject status(Room room)throws IOException {
        try(Memory bytes=new Memory(72)) {
            if(room.muxi_azahar_link_status(bytes,new LibretroCore.SizeT(72))!=1)throw new IOException("Room status rejected");
            JsonObject result=new JsonObject();String[] integers={"size","abi","initialized","roomOpen","memberState","lastError"};
            for(int i=0;i<integers.length;i++)result.addProperty(integers[i],Integer.toUnsignedLong(bytes.getInt(i*4L)));
            String[] longs={"txPackets","txBytes","rxPackets","rxBytes","txDataPackets","rxDataPackets"};
            for(int i=0;i<longs.length;i++)result.addProperty(longs[i],bytes.getLong(24+i*8L));
            result.addProperty("profileStateAbi",room.muxi_azahar_profile_state_abi());return result;
        }
    }
    private static void picture(AvFrame frame,Path path)throws IOException {
        BufferedImage result=new BufferedImage(frame.width(),frame.height(),BufferedImage.TYPE_INT_ARGB);byte[] rgba=frame.rgba();
        for(int i=0;i<frame.width()*frame.height();i++)result.setRGB(i%frame.width(),i/frame.width(),((rgba[i*4+3]&255)<<24)|((rgba[i*4]&255)<<16)|((rgba[i*4+1]&255)<<8)|(rgba[i*4+2]&255));
        if(!ImageIO.write(result,"png",path.toFile()))throw new IOException("PNG writer unavailable");
    }
    public static void main(String[] arguments)throws Exception {
        Path library=Path.of(arguments[0]).toAbsolutePath();Room room=Native.load(library.toString(),Room.class);LibretroCore core=null;LibretroCore.Api raw=null;AvFrame frame=null;
        try(BufferedReader input=new BufferedReader(new InputStreamReader(System.in,java.nio.charset.StandardCharsets.UTF_8))) {
            if(room.muxi_azahar_link_init()!=1)throw new IOException("Room initialization failed");
            for(String line;(line=input.readLine())!=null;) {
                if(line.length()>16384)throw new IOException("Diagnostic command exceeds bound");JsonObject command=JsonParser.parseString(line).getAsJsonObject(),reply=new JsonObject();String action=command.get("action").getAsString();
                try {
                    switch(action) {
                        case "host"->{if(room.muxi_azahar_link_host((short)command.get("port").getAsInt(),command.get("password").getAsString())!=1)throw new IOException("Loopback room creation failed");}
                        case "join"->{int role=command.get("role").getAsInt();byte[] mac={(byte)2,0x4d,0x58,0x33,0x44,(byte)(role+1)};String id=role==0?"1".repeat(64):"2".repeat(64);if(room.muxi_azahar_link_join((short)command.get("port").getAsInt(),role==0?"ConsoleLeft":"ConsoleRight",id,mac,command.get("password").getAsString())!=1)throw new IOException("Loopback join failed");}
                        case "core"->{if(core!=null)throw new IOException("Core already exists");Path profile=Path.of(command.get("profile").getAsString()),system=profile.resolve("system");Files.createDirectories(system);core=new LibretroCore(library,Path.of(command.get("rom").getAsString()),system,profile,Map.of("citra_graphics_api","OpenGL","citra_resolution_factor","1","citra_layout_option","default","citra_use_cpu_jit","enabled"),"n3ds");raw=Native.load(library.toString(),LibretroCore.Api.class);reply.addProperty("info",core.info().toString());}
                        case "step"->{if(core==null)throw new IOException("No core");int count=command.get("count").getAsInt(),buttons=command.has("buttons")?command.get("buttons").getAsInt():0;if(count<1||count>120)throw new IOException("Step bound");long pcmSamples=0,nonzeroSamples=0;for(int i=0;i<count;i++){frame=core.step(new InputFrame(List.of(new PortInput(buttons))));short[] pcm=frame.stereoPcm();pcmSamples+=pcm.length;for(short sample:pcm)if(sample!=0)nonzeroSamples++;}reply.addProperty("pcmSamples",pcmSamples);reply.addProperty("nonzeroPcmSamples",nonzeroSamples);reply.addProperty("sampleRate",frame.sampleRate());reply.addProperty("frame",frame.number());reply.addProperty("width",frame.width());reply.addProperty("height",frame.height());if(command.has("picture"))picture(frame,Path.of(command.get("picture").getAsString()));}
                        // This deliberately tests this new native serialization ABI directly; it is not a product MXS2 bypass.
                        case "native_save"->{if(core==null||raw==null)throw new IOException("No core");long size=raw.retro_serialize_size().longValue();if(size<1||size>256L*1024*1024)throw new IOException("Native state bound");Path target=Path.of(command.get("path").getAsString());try(Memory memory=new Memory(size);OutputStream output=Files.newOutputStream(target,StandardOpenOption.CREATE_NEW)){if(raw.retro_serialize(memory,new LibretroCore.SizeT(size))==0)throw new IOException("Native serialize rejected");byte[] buffer=new byte[65536];for(long at=0;at<size;){int n=(int)Math.min(buffer.length,size-at);memory.read(at,buffer,0,n);output.write(buffer,0,n);at+=n;}}reply.addProperty("size",size);}
                        case "native_load"->{if(core==null||raw==null)throw new IOException("No core");Path source=Path.of(command.get("path").getAsString());long size=Files.size(source);if(size<1||size>256L*1024*1024)throw new IOException("Native state bound");try(Memory memory=new Memory(size);InputStream file=Files.newInputStream(source)){byte[] buffer=new byte[65536];long at=0;for(int n;(n=file.read(buffer))!=-1;){if(at+n>size)throw new IOException("Native state grew");memory.write(at,buffer,0,n);at+=n;}if(at!=size||raw.retro_unserialize(memory,new LibretroCore.SizeT(size))==0)throw new IOException("Native restore rejected");}}
                        case "status"->{}
                        case "leave"->room.muxi_azahar_link_leave();
                        case "quit"->{if(core!=null){core.close();core=null;}room.muxi_azahar_link_shutdown();reply.addProperty("complete",true);System.out.println(JSON.toJson(reply));System.out.flush();return;}
                        default->throw new IOException("Unknown diagnostic action");
                    }
                    reply.addProperty("complete",true);reply.add("status",status(room));
                } catch(Throwable error) {reply.addProperty("complete",false);reply.addProperty("error",error.getClass().getSimpleName()+": "+error.getMessage());error.printStackTrace(System.err);}
                System.out.println(JSON.toJson(reply));System.out.flush();
            }
        } finally {if(core!=null)core.close();room.muxi_azahar_link_shutdown();}
    }
}
