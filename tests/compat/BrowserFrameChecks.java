package net.muxigame.console.compat;

import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;

/** Executable checks of actual PNG byte formats and failure bounds, independent of Minecraft/CEF. */
public final class BrowserFrameChecks {
    private static int checks;
    private static void check(boolean result,String label){checks++;if(!result)throw new AssertionError(label);}
    private static void rejected(byte[] png,String label){try{BrowserFrameCodec.decode(png);throw new AssertionError("Accepted "+label);}catch(IOException expected){checks++;}}
    private static byte[] chunk(String type,byte[] body)throws IOException{ByteArrayOutputStream out=new ByteArrayOutputStream();DataOutputStream d=new DataOutputStream(out);byte[] t=type.getBytes(StandardCharsets.US_ASCII);CRC32 crc=new CRC32();crc.update(t);crc.update(body);d.writeInt(body.length);d.write(t);d.write(body);d.writeInt((int)crc.getValue());return out.toByteArray();}
    private static byte[] custom(int w,int h,byte[] raw,byte color,byte filter)throws IOException{ByteArrayOutputStream zip=new ByteArrayOutputStream();try(var z=new DeflaterOutputStream(zip)){z.write(raw);}ByteArrayOutputStream png=new ByteArrayOutputStream();png.write(new byte[]{(byte)137,80,78,71,13,10,26,10});png.write(chunk("IHDR",ByteBuffer.allocate(13).putInt(w).putInt(h).put((byte)8).put(color).put((byte)0).put((byte)0).put(filter).array()));png.write(chunk("IDAT",zip.toByteArray()));png.write(chunk("IEND",new byte[0]));return png.toByteArray();}
    public static void main(String[] args)throws Exception{
        byte[] pixels={(byte)255,1,2,(byte)255,3,(byte)254,5,(byte)255,6,7,(byte)253,(byte)255,8,9,10,(byte)128};byte[] png=BrowserFrameCodec.encode(2,2,pixels);var image=BrowserFrameCodec.decode(png);
        check(image.width()==2&&image.height()==2,"size preserved");check(Arrays.equals(image.rgba(),pixels),"RGBA byte identity");var independent=ImageIO.read(new ByteArrayInputStream(png));check(independent!=null&&independent.getRGB(0,0)==0xffff0102,"independent PNG decoder and RGB order");check(independent.getRGB(1,1)==0x8008090a,"alpha order");
        Random random=new Random(9911);for(int i=0;i<24;i++){int w=1+random.nextInt(100),h=1+random.nextInt(80);byte[] data=new byte[w*h*4];random.nextBytes(data);byte[] encoded=BrowserFrameCodec.encode(w,h,data);var decoded=BrowserFrameCodec.decode(encoded);check(encoded.length<=BrowserFrameCodec.MAX_BYTES,"network packet bound");check(decoded.width()<=w&&decoded.height()<=h,"never enlarge");check(ImageIO.read(new ByteArrayInputStream(encoded))!=null,"real PNG interoperability");}
        byte[] large=new byte[1024*768*4];random.nextBytes(large);byte[] scaled=BrowserFrameCodec.encode(1024,768,large,256);var reduced=BrowserFrameCodec.decode(scaled);check(scaled.length<=24576,"random footage shrinks to wire bound");check(reduced.width()<=256&&reduced.height()<=192,"configured max width");check(Math.abs(reduced.width()/(double)reduced.height()-4/3.0)<.05,"aspect preserved within rounding");
        byte[] broken=png.clone();broken[0]=0;rejected(broken,"signature");broken=png.clone();broken[broken.length-1]^=1;rejected(broken,"CRC");rejected(Arrays.copyOf(png,png.length-1),"truncated tail");rejected(Arrays.copyOf(png,png.length+1),"trailing bytes");rejected(new byte[24577],"oversized packet");
        rejected(custom(513,1,new byte[2053],(byte)6,(byte)0),"oversized width");rejected(custom(1,513,new byte[2565],(byte)6,(byte)0),"oversized height");rejected(custom(1,1,new byte[5],(byte)2,(byte)0),"unsupported color");rejected(custom(1,1,new byte[5],(byte)6,(byte)1),"interlace");rejected(custom(1,1,new byte[65536],(byte)6,(byte)0),"decompression overflow");rejected(custom(1,1,new byte[4],(byte)6,(byte)0),"short pixels");rejected(custom(1,1,new byte[]{1,0,0,0,0},(byte)6,(byte)0),"unsupported row filter");
        ByteArrayOutputStream extra=new ByteArrayOutputStream();extra.write(png,0,png.length-12);extra.write(chunk("tEXt","payload".getBytes(StandardCharsets.US_ASCII)));extra.write(png,png.length-12,12);rejected(extra.toByteArray(),"metadata/extra chunk");
        for(int size=0;size<png.length;size++)rejected(Arrays.copyOf(png,size),"all truncated prefixes "+size);
        BrowserCanvas canvas=new BrowserCanvas(),other=new BrowserCanvas();check(!canvas.url().equals(other.url()),"per-binding document identity");String html=new String(Base64.getDecoder().decode(canvas.url().substring(canvas.url().indexOf(',')+1)),StandardCharsets.UTF_8);check(html.contains("connect-src 'none'")&&html.contains("form-action 'none'"),"no network or forms");check(!html.contains("<script")&&!html.contains("http:")&&!html.contains("https:")&&!html.contains("file:"),"document has no external asset or script");String script=canvas.script(Long.MAX_VALUE,png);check(script.contains("BigInt('9223372036854775807')"),"lossless sequence beyond JS number precision");check(script.contains("location.href!==")&&script.contains("drawImage"),"document-bound real PNG rendering");check(!script.contains("fetch(")&&!script.contains("WebSocket")&&!script.contains("rom"),"pixels only script");
        try{BrowserFrameCodec.encode(1,1,new byte[4],63);throw new AssertionError("Invalid configured width accepted");}catch(IOException expected){checks++;}
        try{BrowserFrameCodec.encode(Integer.MAX_VALUE,Integer.MAX_VALUE,new byte[4]);throw new AssertionError("Source dimension overflow accepted");}catch(IOException expected){checks++;}
        try{BrowserFrameCodec.encode(2,2,new byte[4]);throw new AssertionError("Mismatched RGBA buffer accepted");}catch(IOException expected){checks++;}
        try{canvas.script(-1,png);throw new AssertionError("Negative browser sequence accepted");}catch(IllegalArgumentException expected){checks++;}
        System.out.println("BrowserFrameChecks: "+checks+" passed");
    }
}
