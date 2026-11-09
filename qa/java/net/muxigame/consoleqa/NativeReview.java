package net.muxigame.consoleqa;

import com.google.gson.JsonObject;
import com.google.gson.JsonArray;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.util.zip.CRC32;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.muxigame.console.compat.BrowserLinkClient;
import net.muxigame.console.compat.WebDisplaysBridge;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.system.MemoryUtil;

/** Reads the actual third-party CEF renderer GPU texture. Never constructs a browser or renderer. */
final class NativeReview {
    static JsonObject picture(Minecraft client){
        JsonObject out=new JsonObject();var location=net.muxigame.console.client.ConsoleClient.INSTANCE.picture();out.addProperty("present",location!=null);if(location==null)return out;
        var texture=client.getTextureManager().getTexture(location);if(!(texture instanceof net.minecraft.client.renderer.texture.DynamicTexture dynamic))return out;var image=dynamic.getPixels();if(image==null)return out;int width=image.getWidth(),height=image.getHeight();out.addProperty("width",width);out.addProperty("height",height);
        for(int quarter:new int[]{1,3}){int pixel=image.getPixelRGBA(width/2,height*quarter/4);JsonArray rgb=new JsonArray();rgb.add(pixel&255);rgb.add(pixel>>>8&255);rgb.add(pixel>>>16&255);out.add(quarter==1?"upper":"lower",rgb);}
        for(int quarter:new int[]{1,3}){int pixel=image.getPixelRGBA(width*quarter/4,height/2);JsonArray rgb=new JsonArray();rgb.add(pixel&255);rgb.add(pixel>>>8&255);rgb.add(pixel>>>16&255);out.add(quarter==1?"left":"right",rgb);}
        long count=0,sum=0;for(int x=width*77/100;x<width*82/100;x++)for(int y=height/12;y<height;y++){int pixel=image.getPixelRGBA(x,y);if((pixel&255)>150&&(pixel>>>8&255)>150&&(pixel>>>16&255)>150){count++;sum+=y;}}out.addProperty("rightPaddlePixels",count);out.addProperty("rightPaddleY",count==0?-1:sum/(double)count/height);return out;
    }
    static void view(Minecraft client,boolean held){
        client.setScreen(null);client.options.fov().set(45);client.options.bobView().set(false);
        client.options.hideGui=!held;client.options.setCameraType(CameraType.FIRST_PERSON);
        client.getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
    }
    static JsonObject browser(Minecraft client,long position,String side,boolean pixels){
        JsonObject out=new JsonObject();var s=BrowserLinkClient.INSTANCE.snapshot();
        out.addProperty("bindings",s.bindings());out.addProperty("surfaces",s.surfaces());out.addProperty("receivedFrames",s.receivedFrames());
        out.addProperty("javascriptSubmissions",s.javascriptSubmissions());out.addProperty("readyBrowsers",s.readyBrowsers());out.addProperty("publishing",s.publishing());
        out.addProperty("rgbaCrc",s.rgbaCrc());out.addProperty("lastError",s.lastError());
        if(client.level==null)return out;
        try{
            BlockPos origin=BlockPos.of(position);out.addProperty("block",net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(client.level.getBlockState(origin).getBlock()).toString());int blocks=0;for(int x=14;x<=16;x++)for(int y=90;y<=91;y++)if(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(client.level.getBlockState(new BlockPos(x,y,8)).getBlock()).toString().equals("webdisplays:screen"))blocks++;out.addProperty("fixtureBlocks",blocks);
            var target=WebDisplaysBridge.at(client.level,BlockPos.of(position),side);out.addProperty("surfaceExists",target.isPresent());
            if(target.isEmpty())return out;
            out.addProperty("url",WebDisplaysBridge.url(target.get()));out.addProperty("mayChange",WebDisplaysBridge.mayChange(target.get(),client.player.getUUID()));
            Object linked=BrowserLinkClient.INSTANCE.nativeBrowser(position,side).orElse(null),browser=linked!=null?linked:WebDisplaysBridge.browser(target.get());
            out.addProperty("browserPresent",browser!=null);out.addProperty("linkedNativeBrowser",linked!=null);
            if(browser==null)return out;
            out.addProperty("browserClass",browser.getClass().getName());out.addProperty("browserUrl",WebDisplaysBridge.browserUrl(browser));
            if(!pixels)return out;
            RenderSystem.assertOnRenderThread();Object renderer=browser.getClass().getMethod("getRenderer").invoke(browser);int texture=(int)renderer.getClass().getMethod("getTextureID").invoke(renderer);
            out.addProperty("texture",texture);out.addProperty("rendererClass",renderer.getClass().getName());
            if(texture<=0||!GL11.glIsTexture(texture))throw new IllegalStateException("Actual CEF texture is not initialized");
            JsonArray previousErrors=new JsonArray();for(int n=0;n<16;n++){int error=GL11.glGetError();if(error==GL11.GL_NO_ERROR)break;previousErrors.add(error);}out.add("previousGlErrors",previousErrors);
            int saved=GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D),packBuffer=GL11.glGetInteger(GL21.GL_PIXEL_PACK_BUFFER_BINDING);
            int[] packNames={GL11.GL_PACK_ALIGNMENT,GL11.GL_PACK_ROW_LENGTH,GL11.GL_PACK_SKIP_ROWS,GL11.GL_PACK_SKIP_PIXELS,GL12.GL_PACK_IMAGE_HEIGHT,GL12.GL_PACK_SKIP_IMAGES},packValues=new int[packNames.length];
            for(int n=0;n<packNames.length;n++)packValues[n]=GL11.glGetInteger(packNames[n]);ByteBuffer buffer=null;
            try{
                GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,0);for(int n=0;n<packNames.length;n++)GL11.glPixelStorei(packNames[n],n==0?1:0);
                GL11.glBindTexture(GL11.GL_TEXTURE_2D,texture);int width=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_WIDTH),height=GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D,0,GL11.GL_TEXTURE_HEIGHT);
                if(width<1||height<1||width>2048||height>2048)throw new IllegalStateException("Actual CEF texture size outside QA read budget");
                buffer=MemoryUtil.memAlloc(Math.multiplyExact(Math.multiplyExact(width,height),4));GL11.glGetTexImage(GL11.GL_TEXTURE_2D,0,GL11.GL_RGBA,GL11.GL_UNSIGNED_BYTE,buffer);
                CRC32 crc=new CRC32();long nonBlack=0,opaque=0,chromatic=0;int first=buffer.getInt(0);boolean uniform=true;
                for(int offset=0;offset<buffer.capacity();offset+=4){int r=buffer.get(offset)&255,g=buffer.get(offset+1)&255,b=buffer.get(offset+2)&255,a=buffer.get(offset+3)&255;crc.update(r);crc.update(g);crc.update(b);crc.update(a);if(r+g+b>24)nonBlack++;if(a>240)opaque++;if(Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b))>24)chromatic++;if(buffer.getInt(offset)!=first)uniform=false;}
                out.addProperty("gpuRead",true);out.addProperty("width",width);out.addProperty("height",height);out.addProperty("textureCrc",Long.toHexString(crc.getValue()));out.addProperty("nonBlackPixels",nonBlack);out.addProperty("opaquePixels",opaque);out.addProperty("chromaticPixels",chromatic);out.addProperty("uniform",uniform);
                int glError=GL11.glGetError();if(glError!=GL11.GL_NO_ERROR)throw new IllegalStateException("Actual CEF texture read GL error "+glError);
            }finally{if(buffer!=null)MemoryUtil.memFree(buffer);for(int n=0;n<packNames.length;n++)GL11.glPixelStorei(packNames[n],packValues[n]);GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER,packBuffer);GL11.glBindTexture(GL11.GL_TEXTURE_2D,saved);}
        }catch(Exception e){out.addProperty("gpuRead",false);out.addProperty("nativeError",e.toString());}
        return out;
    }
    private NativeReview(){}
}
