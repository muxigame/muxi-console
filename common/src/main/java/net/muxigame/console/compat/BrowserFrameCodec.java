package net.muxigame.console.compat;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Our deliberately small PNG subset: one RGBA image, filter zero, no metadata or executable content. */
public final class BrowserFrameCodec {
    public static final int MAX_BYTES=24576, MAX_WIDTH=512, MAX_HEIGHT=512, MAX_SOURCE_PIXELS=4194304;
    private static final byte[] SIGNATURE={(byte)137,80,78,71,13,10,26,10};
    public record Image(int width,int height,byte[] rgba){}
    public static byte[] encode(int width,int height,byte[] rgba)throws IOException{
        return encode(width,height,rgba,MAX_WIDTH);
    }
    public static byte[] encode(int width,int height,byte[] rgba,int maxWidth)throws IOException{
        if(width<1||height<1||(long)width*height>MAX_SOURCE_PIXELS||rgba==null||(long)width*height*4!=rgba.length)throw new IOException("Invalid source frame");
        if(maxWidth<64||maxWidth>MAX_WIDTH)throw new IOException("Invalid browser frame width");
        double scale=Math.min(1,Math.min(maxWidth/(double)width,MAX_HEIGHT/(double)height));
        int w=Math.max(1,(int)(width*scale)),h=Math.max(1,(int)(height*scale));
        while(true){byte[] png=png(width,height,rgba,w,h);if(png.length<=MAX_BYTES)return png;w=Math.max(1,w*4/5);h=Math.max(1,h*4/5);}
    }
    private static byte[] png(int sw,int sh,byte[] source,int w,int h)throws IOException{
        ByteArrayOutputStream compressed=new ByteArrayOutputStream();
        Deflater deflater=new Deflater(Deflater.BEST_SPEED);
        try(DeflaterOutputStream out=new DeflaterOutputStream(compressed,deflater)){
            byte[] row=new byte[w*4+1];
            for(int y=0;y<h;y++){int sy=(int)((long)y*sh/h);for(int x=0;x<w;x++){int sx=(int)((long)x*sw/w);System.arraycopy(source,(sy*sw+sx)*4,row,1+x*4,4);}out.write(row);}
        }finally{deflater.end();}
        ByteArrayOutputStream out=new ByteArrayOutputStream();out.write(SIGNATURE);
        ByteBuffer header=ByteBuffer.allocate(13).putInt(w).putInt(h).put((byte)8).put((byte)6).put((byte)0).put((byte)0).put((byte)0);
        chunk(out,"IHDR",header.array());chunk(out,"IDAT",compressed.toByteArray());chunk(out,"IEND",new byte[0]);return out.toByteArray();
    }
    private static void chunk(OutputStream out,String name,byte[] body)throws IOException{
        DataOutputStream data=new DataOutputStream(out);byte[] type=name.getBytes(StandardCharsets.US_ASCII);CRC32 crc=new CRC32();crc.update(type);crc.update(body);data.writeInt(body.length);data.write(type);data.write(body);data.writeInt((int)crc.getValue());
    }
    /** Fully checks bounded decompression, all CRCs and all pixels before a server forwards an image. */
    public static Image decode(byte[] png)throws IOException{
        if(png==null||png.length<57||png.length>MAX_BYTES)throw new IOException("Invalid PNG size");
        DataInputStream in=new DataInputStream(new ByteArrayInputStream(png));if(!Arrays.equals(in.readNBytes(8),SIGNATURE))throw new IOException("Invalid PNG signature");
        int w=0,h=0,index=0;ByteArrayOutputStream compressed=new ByteArrayOutputStream();boolean ended=false;
        while(in.available()>0){int length=in.readInt();if(length<0||length>in.available()-8)throw new IOException("Invalid PNG chunk size");byte[] type=in.readNBytes(4),body=in.readNBytes(length);int actual=in.readInt();CRC32 crc=new CRC32();crc.update(type);crc.update(body);if(actual!=(int)crc.getValue())throw new IOException("Invalid PNG CRC");String name=new String(type,StandardCharsets.US_ASCII);
            if(index==0&&name.equals("IHDR")&&length==13){ByteBuffer header=ByteBuffer.wrap(body);w=header.getInt();h=header.getInt();if(w<1||h<1||w>MAX_WIDTH||h>MAX_HEIGHT||header.get()!=8||header.get()!=6||header.get()!=0||header.get()!=0||header.get()!=0)throw new IOException("Unsupported PNG header");}
            else if(index==1&&name.equals("IDAT")&&length>0)compressed.write(body);
            else if(index==2&&name.equals("IEND")&&length==0&&in.available()==0)ended=true;
            else throw new IOException("Unsupported PNG chunks");index++;
        }
        if(!ended||index!=3)throw new IOException("Incomplete PNG");int expected=(w*4+1)*h;byte[] raw=new byte[expected];Inflater inflater=new Inflater();
        try{inflater.setInput(compressed.toByteArray());int n=0;while(n<expected){int read=inflater.inflate(raw,n,expected-n);if(read==0)break;n+=read;}byte[] extra=new byte[1];if(n!=expected||inflater.inflate(extra)!=0||!inflater.finished()||inflater.getRemaining()!=0)throw new IOException("Invalid PNG compression bounds");}
        catch(DataFormatException bad){throw new IOException("Invalid PNG compression",bad);}finally{inflater.end();}
        byte[] rgba=new byte[w*h*4];for(int y=0;y<h;y++){int offset=y*(w*4+1);if(raw[offset]!=0)throw new IOException("Unsupported PNG row filter");System.arraycopy(raw,offset+1,rgba,y*w*4,w*4);}return new Image(w,h,rgba);
    }
    private BrowserFrameCodec(){}
}
