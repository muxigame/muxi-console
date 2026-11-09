package net.muxigame.console.runtime;

import com.sun.jna.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Worker-only frontend to our MIT adapter plus the separately licensed, pinned mGBA MPL core. */
final class LinkGbaNativeCore implements LinkBackend {
    public interface Api extends Library {
        int muxi_gba_link_abi();
        int muxi_gba_link_alive();
        int muxi_gba_link_open(String left,String right);
        void muxi_gba_link_close();
        int muxi_gba_link_step(int left,int right);
        Pointer muxi_gba_link_video(int endpoint);
        int muxi_gba_link_audio_rate(int endpoint);
        int muxi_gba_link_audio(int endpoint,short[] output,int capacity);
        long muxi_gba_link_export(int endpoint,byte[] output,long capacity);
        int muxi_gba_link_restore(int endpoint,byte[] input,long length);
        long muxi_gba_link_group_export(byte[] output,long capacity);
        int muxi_gba_link_group_restore(byte[] input,long length);
    }
    private static final int WIDTH=240,HEIGHT=160,MAX_SINGLE=4*1024*1024;
    private final LinkRuntime.Config config;
    private final Thread owner=Thread.currentThread();
    private Api api;private boolean closed;private long frame;
    LinkGbaNativeCore(LinkRuntime.Config config)throws IOException {
        this.config=config;
        try{
            if(!hash(config.core()).equals(config.coreSHA()))throw new IOException("GBA link adapter differs from its pin");
            for(var endpoint:config.endpoints()){
                if(!endpoint.platform().equals("gba")||!Files.isRegularFile(endpoint.content(),LinkOption.NOFOLLOW_LINKS)||Files.size(endpoint.content())<192||Files.size(endpoint.content())>32L*1024*1024||!hash(endpoint.content()).equals(endpoint.romSHA()))throw new IOException("Invalid GBA cable content identity");
            }
            api=Native.load(config.core().toAbsolutePath().toString(),Api.class,Map.of(Library.OPTION_STRING_ENCODING,"UTF-8"));
            if(api.muxi_gba_link_abi()!=1)throw new IOException("Unsupported GBA cable adapter ABI");
            if(api.muxi_gba_link_open(config.endpoints().get(0).content().toAbsolutePath().toString(),config.endpoints().get(1).content().toAbsolutePath().toString())!=1)throw new IOException("mGBA could not attach both cable boards");
            for(int i=0;i<2;i++){int rate=api.muxi_gba_link_audio_rate(i);if(rate<8000||rate>192000)throw new IOException("Invalid GBA hardware audio rate");}
        }catch(IOException|RuntimeException|LinkageError failure){try{close();}catch(IOException cleanup){failure.addSuppressed(cleanup);}throw failure;}
    }
    private static String hash(Path file)throws IOException {
        if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file))throw new IOException("Expected a regular GBA link file");
        try{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream input=Files.newInputStream(file,LinkOption.NOFOLLOW_LINKS)){byte[] buffer=new byte[32768];for(int n;(n=input.read(buffer))>=0;)if(n>0)digest.update(buffer,0,n);}return HexFormat.of().formatHex(digest.digest());}
        catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
    private void active()throws IOException {if(closed||api==null||Thread.currentThread()!=owner)throw new IOException("GBA cable call outside its active worker thread");}
    public CoreInfo info(){return new CoreInfo("mGBA link","7a12d6d / original cable ABI1",WIDTH,HEIGHT,16777216.0/280896,32768,1.5);}
    private static int keys(PortInput input){int hardware=0;int[] bits={8,0,2,3,7,6,4,5,11,10};for(int key=0;key<bits.length;key++)if((input.buttons()&(1<<bits[key]))!=0)hardware|=1<<key;return hardware;}
    public LinkRuntime.Frame step(PortInput left,PortInput right)throws IOException {
        active();if(api.muxi_gba_link_step(keys(left),keys(right))!=1)throw new IOException("GBA cable CPUs could not advance together");frame++;
        return new LinkRuntime.Frame(frame,picture(0),picture(1));
    }
    private AvFrame picture(int endpoint)throws IOException {
        Pointer source=api.muxi_gba_link_video(endpoint);if(source==null)throw new IOException("GBA video buffer unavailable");
        // This pinned software renderer stores red in the low byte; the high byte contains renderer tags.
        int[] pixels=source.getIntArray(0,WIDTH*HEIGHT);byte[] rgba=new byte[WIDTH*HEIGHT*4];
        for(int i=0;i<pixels.length;i++){rgba[i*4]=(byte)pixels[i];rgba[i*4+1]=(byte)(pixels[i]>>>8);rgba[i*4+2]=(byte)(pixels[i]>>>16);rgba[i*4+3]=(byte)255;}
        short[] buffer=new short[8192];int n=api.muxi_gba_link_audio(endpoint,buffer,buffer.length);if(n<0||n>buffer.length||(n&1)!=0)throw new IOException("Invalid independent GBA audio buffer");
        return new AvFrame(frame,WIDTH,HEIGHT,0,rgba,Arrays.copyOf(buffer,n),api.muxi_gba_link_audio_rate(endpoint),false,1.5);
    }
    private static void index(int endpoint)throws IOException {if(endpoint<0||endpoint>1)throw new IOException("Invalid GBA cable endpoint");}
    public byte[] exportEndpoint(int endpoint)throws IOException {
        active();index(endpoint);long size=api.muxi_gba_link_export(endpoint,null,0);if(size<16||size>MAX_SINGLE)throw new IOException("GBA single export exceeds bounds");byte[] bytes=new byte[(int)size];if(api.muxi_gba_link_export(endpoint,bytes,size)!=size)throw new IOException("GBA single export changed size");return bytes;
    }
    public void restoreEndpoint(int endpoint,byte[] bytes)throws IOException {
        active();index(endpoint);if(bytes==null||bytes.length<16||bytes.length>MAX_SINGLE)throw new IOException("Invalid GBA single snapshot bounds");
        var in=LinkWire.input(bytes);if(in.readInt()!=LinkNativeCore.SINGLE_MAGIC)throw new IOException("Invalid GBA single snapshot format");
        byte[] raw=LinkWire.bytes(in,MAX_SINGLE),sram=LinkWire.bytes(in,128*1024),rtc=LinkWire.bytes(in,64*1024);LinkWire.end(in);
        // Old ordinary mGBA snapshots may have newer SRAM inside the core blob
        // than in libretro's stale public backing. Preserve that actual progress.
        var normalized=MgbaStateNormalizer.normalize(raw,sram);
        byte[] selected=normalized.normalized()?LinkWire.body(out->{out.writeInt(LinkNativeCore.SINGLE_MAGIC);LinkWire.bytes(out,normalized.core());LinkWire.bytes(out,normalized.sram());LinkWire.bytes(out,rtc);}):bytes;
        if(api.muxi_gba_link_restore(endpoint,selected,selected.length)!=1)throw restoreFailure();
    }
    private byte[] nativeGroup()throws IOException {
        long size=api.muxi_gba_link_group_export(null,0);if(size<12||size>4*1024*1024)throw new IOException("GBA native coordinator snapshot exceeds bounds");byte[] group=new byte[(int)size];if(api.muxi_gba_link_group_export(group,size)!=size)throw new IOException("GBA coordinator export changed size");return group;
    }
    public byte[] saveLinkState()throws IOException {
        active();byte[][] singles={exportEndpoint(0),exportEndpoint(1)};byte[] group=nativeGroup();
        return LinkWire.body(out->{out.writeInt(LinkNativeCore.LINK_MAGIC);out.writeShort(2);LinkWire.text(out,"mgba");LinkWire.text(out,config.coreSHA());
            for(int i=0;i<2;i++){var endpoint=config.endpoints().get(i);LinkWire.text(out,endpoint.machineId().toString());LinkWire.text(out,endpoint.platform());LinkWire.text(out,endpoint.romSHA());LinkWire.bytes(out,singles[i]);}
            LinkWire.bytes(out,group);
        });
    }
    public void loadLinkState(byte[] bytes)throws IOException {
        active();if(bytes==null||bytes.length>LinkWire.MAX_BYTES)throw new IOException("GBA link snapshot exceeds bounds");var in=LinkWire.input(bytes);
        if(in.readInt()!=LinkNativeCore.LINK_MAGIC||in.readUnsignedShort()!=2||!LinkWire.text(in).equals("mgba")||!LinkWire.text(in).equals(config.coreSHA()))throw new IOException("GBA link backend or binary identity differs");
        for(int i=0;i<2;i++){var endpoint=config.endpoints().get(i);if(!LinkWire.text(in).equals(endpoint.machineId().toString())||!LinkWire.text(in).equals("gba")||!LinkWire.text(in).equals(endpoint.romSHA()))throw new IOException("GBA link snapshot belongs to different physical machines or content");
            byte[] single=LinkWire.bytes(in,MAX_SINGLE);if(single.length<16||LinkWire.input(single).readInt()!=LinkNativeCore.SINGLE_MAGIC)throw new IOException("Invalid GBA endpoint export");}
        byte[] group=LinkWire.bytes(in,4*1024*1024);LinkWire.end(in);
        if(api.muxi_gba_link_group_restore(group,group.length)!=1)throw restoreFailure();
    }
    private IOException restoreFailure(){return new IOException(api.muxi_gba_link_alive()==1?"GBA state was rejected; the previous pair was retained":"GBA state rollback failed; the worker closed both boards to protect committed saves");}
    @Override public void close()throws IOException {
        if(closed)return;if(Thread.currentThread()!=owner)throw new IOException("GBA adapter must close on its worker thread");closed=true;
        if(api!=null){api.muxi_gba_link_close();NativeLibrary.getInstance(config.core().toAbsolutePath().toString()).close();api=null;}
    }
}
