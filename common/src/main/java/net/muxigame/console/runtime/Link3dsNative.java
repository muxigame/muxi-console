package net.muxigame.console.runtime;

import com.sun.jna.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/** Room ABI1 with separately fingerprinted native-state ABI1/2. Joined alone is not UDS proof. */
public final class Link3dsNative implements AutoCloseable {
    public record Status(int abi,boolean initialized,boolean roomOpen,int memberState,long lastError,
                         long sentPackets,long sentBytes,long receivedPackets,long receivedBytes,
                         long sentDataPackets,long receivedDataPackets,int profileStateAbi) {
        public boolean joined() { return memberState==3||memberState==4; }
    }
    public interface Api extends Library {
        int muxi_azahar_link_init();int muxi_azahar_link_host(short port,String password);
        int muxi_azahar_link_join(short port,String nickname,String consoleId,byte[] mac,String password);
        int muxi_azahar_link_status(Pointer output,LibretroCore.SizeT capacity);
        int muxi_azahar_profile_state_abi();void muxi_azahar_link_leave();void muxi_azahar_link_shutdown();
    }
    private final Api api;private boolean closed;
    Link3dsNative(Path library)throws IOException {
        try {api=Native.load(library.toAbsolutePath().toString(),Api.class,Map.of(Library.OPTION_STRING_ENCODING,"UTF-8"));
            if(api.muxi_azahar_link_init()!=1||status().abi()!=1||!supportedProfileAbi(api.muxi_azahar_profile_state_abi()))throw new IOException("Pinned 3DS core lacks a supported Room/native-profile ABI");
        }catch(LinkageError failure){throw new IOException("3DS core lacks the required private-room extension",failure);}
    }
    static boolean supportedProfileAbi(int value){return value==1||value==2;}
    private void active()throws IOException { if(closed)throw new IOException("3DS native Room closed"); }
    private static void address(int port,String password)throws IOException {if(port<1024||port>65535||password==null||!password.matches("[a-f0-9]{32,64}"))throw new IOException("Invalid private 3DS Room address");}
    void host(int port,String password)throws IOException {active();address(port,password);if(api.muxi_azahar_link_host((short)port,password)!=1)throw new IOException("Private 3DS loopback room could not bind");}
    void join(int port,String password,UUID machine)throws IOException {active();address(port,password);if(api.muxi_azahar_link_join((short)port,Link3dsIdentity.nickname(machine),Link3dsIdentity.consoleId(machine),Link3dsIdentity.mac(machine),password)!=1)throw new IOException("Private 3DS room join failed");}
    Status status()throws IOException {
        active();try(Memory bytes=new Memory(72)) {
            if(api.muxi_azahar_link_status(bytes,new LibretroCore.SizeT(72))!=1||bytes.getInt(0)!=72)throw new IOException("Invalid 3DS Room status size");
            int abi=bytes.getInt(4),state=bytes.getInt(16);if(abi!=1||state<0||state>4)throw new IOException("Invalid 3DS Room ABI/state");
            return new Status(abi,bytes.getInt(8)!=0,bytes.getInt(12)!=0,state,Integer.toUnsignedLong(bytes.getInt(20)),bytes.getLong(24),bytes.getLong(32),bytes.getLong(40),bytes.getLong(48),bytes.getLong(56),bytes.getLong(64),api.muxi_azahar_profile_state_abi());
        }
    }
    void leave()throws IOException {active();api.muxi_azahar_link_leave();}
    /** The caller closes Core::System first so its NWM callback is unbound before network teardown. */
    @Override public void close(){if(!closed){api.muxi_azahar_link_shutdown();closed=true;}}
}
