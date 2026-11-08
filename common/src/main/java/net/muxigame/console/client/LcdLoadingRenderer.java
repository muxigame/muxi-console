package net.muxigame.console.client;

import com.mojang.blaze3d.vertex.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.muxigame.console.mc.*;
import java.util.*;

/** Temporary LCD content taken from the real local transfer/startup state. */
public final class LcdLoadingRenderer {
    public record Audit(long sequence,long renderedAtNanos,UUID card,String stage,long bytes,long total,int percent,String text,String context,HardwareCatalog.Plane plane){}
    private static long sequence;private static Audit audit=new Audit(0,0,null,"idle",0,0,-1,"","",null);
    private static final Map<String,Audit> latest=new LinkedHashMap<>();
    public static Audit snapshot(){return audit;}
    public static Map<String,Audit> snapshots(){return Map.copyOf(latest);}
    private static final ResourceLocation WHITE=ResourceLocation.fromNamespaceAndPath("muxi_console","textures/block/hardware/feedback.png");
    public static boolean visible(ConsoleClient.ProgressSnapshot progress){return progress!=null&&(progress.loading()||!progress.error().isEmpty());}
    public static ConsoleClient.ProgressSnapshot deviceProgress(long console,ItemStack media){
        var client=ConsoleClient.INSTANCE;var progress=client.progressSnapshot();
        return console==client.screenConsole()&&CardData.read(media).filter(card->card.instanceId().equals(progress.card())).isPresent()?progress:null;
    }
    public static void draw(PoseStack pose,MultiBufferSource buffers,HardwareCatalog.Plane plane,ConsoleClient.ProgressSnapshot progress){draw(pose,buffers,plane,progress,"held");}
    public static void draw(PoseStack pose,MultiBufferSource buffers,HardwareCatalog.Plane plane,ConsoleClient.ProgressSnapshot progress,String context){
        if(!visible(progress))return;
        audit=new Audit(++sequence,System.nanoTime(),progress.card(),progress.stage(),progress.bytes(),progress.total(),progress.percent(),progress.text(),context,plane);latest.put(context,audit);if(latest.size()>32)latest.remove(latest.keySet().iterator().next());float x0=plane.x0()/16,x1=plane.x1()/16,y0=plane.y0()/16,y1=plane.y1()/16,z=(plane.z()-.026f)/16,w=x1-x0,h=y1-y0;
        quad(pose,buffers,x0,y0,x1,y1,z,12,18,24);
        Font font=Minecraft.getInstance().font;String label=progress.error().isEmpty()?progress.text():"加载失败";
        int pixels=112;label=font.plainSubstrByWidth(label,pixels-8);float scale=w/pixels;
        pose.pushPose();
        try{pose.translate(x1,y1-h*.35f,z-.001f);pose.scale(-scale,-scale,scale);font.drawInBatch(label,(pixels-font.width(label))/2f,0,progress.error().isEmpty()?0xffdcebf3:0xffffa5a5,false,pose.last().pose(),buffers,Font.DisplayMode.NORMAL,0,LightTexture.FULL_BRIGHT);}
        finally{pose.popPose();}
        float left=x0+w*.12f,right=x1-w*.12f,bottom=y0+h*.2f,top=bottom+h*.055f;
        quad(pose,buffers,left,bottom,right,top,z-.0015f,42,57,69);
        if(progress.percent()>=0){float amount=Math.max(0,Math.min(100,progress.percent()))/100f;if(amount>0)quad(pose,buffers,right-(right-left)*amount,bottom,right,top,z-.002f,93,199,255);}
        else if(progress.loading()){
            float travel=(System.nanoTime()/1_000_000L%1300)/1300f,range=(right-left)*.72f,width=(right-left)*.28f,start=left+range*travel;
            quad(pose,buffers,start,bottom,start+width,top,z-.002f,93,199,255);
        }
    }
    private static void quad(PoseStack pose,MultiBufferSource buffers,float x0,float y0,float x1,float y1,float z,int r,int g,int b){
        var out=buffers.getBuffer(RenderType.text(WHITE));
        out.addVertex(pose.last(),x0,y0,z).setColor(r,g,b,255).setUv(0,1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose.last(),0,0,-1);
        out.addVertex(pose.last(),x0,y1,z).setColor(r,g,b,255).setUv(0,0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose.last(),0,0,-1);
        out.addVertex(pose.last(),x1,y1,z).setColor(r,g,b,255).setUv(1,0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose.last(),0,0,-1);
        out.addVertex(pose.last(),x1,y0,z).setColor(r,g,b,255).setUv(1,1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose.last(),0,0,-1);
    }
    private LcdLoadingRenderer(){}
}
