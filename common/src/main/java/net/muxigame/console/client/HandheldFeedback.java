package net.muxigame.console.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.*;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.muxigame.console.mc.HardwareCatalog;
import net.muxigame.console.runtime.PortInput;
import java.util.*;

/** Recolors the actual complete button-cap geometry, including edges and side faces. */
public final class HandheldFeedback {
    private static final ResourceLocation WHITE=ResourceLocation.fromNamespaceAndPath("muxi_console","textures/block/hardware/feedback.png");
    private static final ResourceLocation SPRITE=ResourceLocation.fromNamespaceAndPath("muxi_console","block/hardware/feedback");
    private static final Map<BakedQuad,BakedQuad> BLUE=new WeakHashMap<>();
    public record Audit(long sequence,String item,int model,int buttons,int quads,int recoloredQuads,Map<String,Integer> directions,boolean allVerticesBlue,boolean allUseWhite){public Audit{directions=Map.copyOf(directions);}}
    private static long sequence;
    private static Audit audit=new Audit(0,"",0,0,0,0,Map.of(),true,true);
    /** Read-only evidence from the exact quads sent to the render buffer, not a synthetic mesh. */
    public static Audit audit(){return audit;}
    public static int mask(PortInput input){
        int mask=input.buttons();
        if(input.leftX()<-12000)mask|=1<<6;if(input.leftX()>12000)mask|=1<<7;
        if(input.leftY()<-12000)mask|=1<<4;if(input.leftY()>12000)mask|=1<<5;
        return mask;
    }
    public static int body(BakedModel model,ItemStack item,PoseStack pose,MultiBufferSource buffers,int light,int overlay,List<HardwareCatalog.ControlRegion> regions,PortInput input,int port){
        var renderer=Minecraft.getInstance().getItemRenderer();var consumer=ItemRenderer.getFoilBufferDirect(buffers,RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS),true,item.hasFoil());
        int mask=mask(input);String kind=item.is(net.muxigame.console.mc.ConsoleContent.CONTROLLER.get())?"controller":"handheld";
        if(mask==0||regions.isEmpty()){renderer.renderModelLists(model,item,light,overlay,pose,consumer);audit=new Audit(++sequence,kind,HardwareCatalog.model(item),mask,0,0,Map.of(),true,true);return 0;}
        int count=0,total=0;boolean colors=true,white=true;Map<String,Integer> directions=new TreeMap<>();RandomSource random=RandomSource.create(42);
        for(int side=0;side<=6;side++){
            random.setSeed(42);var quads=model.getQuads(null,side==6?null:Direction.values()[side],random);
            List<BakedQuad> normal=new ArrayList<>(),pressed=new ArrayList<>();
            for(var q:quads){total++;if(matches(q.getVertices(),regions,mask,port)){
                var colored=blue(q);pressed.add(colored);count++;directions.merge(q.getDirection().getName(),1,Integer::sum);
                int[] data=colored.getVertices();int stride=data.length/4;for(int v=0;v<4;v++)colors&=data[v*stride+3]==0xffffc75d;
                white&=colored.getSprite().contents().name().equals(SPRITE);
            }else normal.add(q);}
            renderer.renderQuadList(pose,consumer,normal,item,light,overlay);renderer.renderQuadList(pose,consumer,pressed,item,LightTexture.FULL_BRIGHT,overlay);
        }
        audit=new Audit(++sequence,kind,HardwareCatalog.model(item),mask,total,count,directions,colors,white);return count;
    }
    /** All vertices must belong to a real cap box; large neighbouring shell quads stay untouched. */
    public static boolean matches(int[] data,List<HardwareCatalog.ControlRegion> regions,int mask,int port){
        int stride=data.length/4;if(stride<6||data.length%4!=0)return false;
        for(var r:regions){
            if(r.port()!=port||(mask&(1<<r.button()))==0)continue;boolean inside=true;
            for(int i=0;i<4&&inside;i++){
                float x=Float.intBitsToFloat(data[i*stride])*16,y=Float.intBitsToFloat(data[i*stride+1])*16,z=Float.intBitsToFloat(data[i*stride+2])*16;
                inside=x>=r.x0()-.002f&&x<=r.x1()+.002f&&y>=r.y0()-.002f&&y<=r.y1()+.002f&&z>=r.z0()-.002f&&z<=r.z1()+.002f;
            }
            if(inside)return true;
        }
        return false;
    }
    private static BakedQuad blue(BakedQuad source){
        return BLUE.computeIfAbsent(source,q->{
            var sprite=Minecraft.getInstance().getTextureAtlas(TextureAtlas.LOCATION_BLOCKS).apply(SPRITE);int[] data=q.getVertices().clone();int stride=data.length/4;
            for(int i=0;i<4;i++){int at=i*stride;data[at+3]=0xffffc75d;data[at+4]=Float.floatToRawIntBits((sprite.getU0()+sprite.getU1())/2);data[at+5]=Float.floatToRawIntBits((sprite.getV0()+sprite.getV1())/2);if(stride>6)data[at+6]=LightTexture.FULL_BRIGHT;}
            return new BakedQuad(data,-1,q.getDirection(),sprite,q.isShade(),q.hasAmbientOcclusion());
        });
    }
    /** The static chunk mesh remains in place, so block controls receive opaque complete cap faces. */
    public static int draw(PoseStack pose,MultiBufferSource buffers,List<HardwareCatalog.ControlRegion> regions,PortInput input,int port){
        int mask=mask(input),count=0;if(mask==0)return 0;var out=buffers.getBuffer(RenderType.entityCutoutNoCull(WHITE));Set<String> seen=new HashSet<>();
        for(var p:regions){
            if(p.port()!=port||(mask&(1<<p.button()))==0)continue;
            String id=p.x0()+":"+p.y0()+":"+p.z0()+":"+p.x1()+":"+p.y1()+":"+p.z1();if(!seen.add(id))continue;
            float e=.0004f,x0=p.x0()/16-e,x1=p.x1()/16+e,y0=p.y0()/16-e,y1=p.y1()/16+e,z0=p.z0()/16-e,z1=p.z1()/16+e;
            face(out,pose,new float[][]{{x0,y0,z0},{x0,y1,z0},{x1,y1,z0},{x1,y0,z0}},0,0,-1);
            face(out,pose,new float[][]{{x1,y0,z1},{x1,y1,z1},{x0,y1,z1},{x0,y0,z1}},0,0,1);
            face(out,pose,new float[][]{{x0,y1,z0},{x0,y1,z1},{x1,y1,z1},{x1,y1,z0}},0,1,0);
            face(out,pose,new float[][]{{x0,y0,z1},{x0,y0,z0},{x1,y0,z0},{x1,y0,z1}},0,-1,0);
            face(out,pose,new float[][]{{x0,y0,z1},{x0,y1,z1},{x0,y1,z0},{x0,y0,z0}},-1,0,0);
            face(out,pose,new float[][]{{x1,y0,z0},{x1,y1,z0},{x1,y1,z1},{x1,y0,z1}},1,0,0);count+=6;
        }
        return count;
    }
    private static void face(com.mojang.blaze3d.vertex.VertexConsumer out,PoseStack pose,float[][] vertices,float nx,float ny,float nz){
        for(int i=0;i<4;i++){var v=vertices[i];out.addVertex(pose.last(),v[0],v[1],v[2]).setColor(93,199,255,255).setUv(i>=2?1:0,i==0||i==3?1:0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose.last(),nx,ny,nz);}
    }
    private HandheldFeedback(){}
}
