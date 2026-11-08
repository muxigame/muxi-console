package net.muxigame.console.client;

import com.mojang.blaze3d.vertex.*;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.muxigame.console.mc.*;
import org.joml.Matrix4f;
import org.joml.Matrix3f;
import org.joml.Vector3f;

/** Draws only the exposed portion of the actual card model, never a synthetic label or replacement item. */
public final class InsertedMediaRenderer {
    public record Vertex(float x,float y,float z,float u,float v,float r,float g,float b,float a){
        public Vertex(float x,float y,float z,float u,float v){this(x,y,z,u,v,1,1,1,1);}
        float axis(int axis){return axis==0?x:axis==1?y:z;}
        Vertex toward(Vertex other,float f){return new Vertex(x+(other.x-x)*f,y+(other.y-y)*f,z+(other.z-z)*f,u+(other.u-u)*f,v+(other.v-v)*f,r+(other.r-r)*f,g+(other.g-g)*f,b+(other.b-b)*f,a+(other.a-a)*f);}
    }
    public record Audit(long sequence,UUID card,int body,int media,int sourceQuads,int visibleQuads,float[] bounds){public Audit{bounds=bounds.clone();}@Override public float[] bounds(){return bounds.clone();}}
    private static long sequence;private static Audit audit=new Audit(0,null,0,0,0,0,new float[6]);
    public static Audit audit(){return audit;}
    public static List<Vertex> clip(List<Vertex> polygon,int axis,float value,boolean above){
        if(polygon.isEmpty())return List.of();List<Vertex> result=new ArrayList<>();Vertex previous=polygon.get(polygon.size()-1);boolean previousInside=above?previous.axis(axis)>=value:previous.axis(axis)<=value;
        for(Vertex current:polygon){boolean inside=above?current.axis(axis)>=value:current.axis(axis)<=value;
            if(inside!=previousInside){float delta=current.axis(axis)-previous.axis(axis);if(Math.abs(delta)>1e-9f)result.add(previous.toward(current,(value-previous.axis(axis))/delta));}
            if(inside)result.add(current);previous=current;previousInside=inside;
        }
        return List.copyOf(result);
    }
    public static int draw(int body,ItemStack card,PoseStack pose,MultiBufferSource buffers,int light,int overlay){return draw(body,card,pose,buffers,light,overlay,false);}
    public static int drawDesktop(int body,ItemStack card,PoseStack pose,MultiBufferSource buffers,int light,int overlay){return draw(body,card,pose,buffers,light,overlay,true);}
    private static int draw(int body,ItemStack card,PoseStack pose,MultiBufferSource buffers,int light,int overlay,boolean desktop){
        var placement=desktop?HardwareCatalog.desktopMediaPose(body,card):HardwareCatalog.mediaPose(body,card);if(placement.isEmpty())return 0;
        var coordinates=placement.get();var slot=coordinates.slot();Matrix4f transform=coordinates.transform();Matrix3f normals=transform.normal(new Matrix3f());
        Minecraft mc=Minecraft.getInstance();var model=mc.getItemRenderer().getModel(card,mc.level,mc.player,0);
        var out=ItemRenderer.getFoilBufferDirect(buffers,RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS),true,card.hasFoil());RandomSource random=RandomSource.create(42);
        int total=0,count=0;float[] bounds={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY,Float.NEGATIVE_INFINITY};
        for(int side=0;side<=6;side++){
            random.setSeed(42);
            for(BakedQuad quad:model.getQuads(null,side==6?null:Direction.values()[side],random)){
                total++;int[] data=quad.getVertices();int stride=data.length/4;if(stride<6)continue;
                int tint=quad.isTinted()?mc.getItemColors().getColor(card,quad.getTintIndex()):0xffffffff;List<Vertex> polygon=new ArrayList<>(4);
                for(int i=0;i<4;i++){
                    int at=i*stride;Vector3f v=transform.transformPosition(new Vector3f(Float.intBitsToFloat(data[at]),Float.intBitsToFloat(data[at+1]),Float.intBitsToFloat(data[at+2])));int color=data[at+3];
                    polygon.add(new Vertex(v.x,v.y,v.z,Float.intBitsToFloat(data[at+4]),Float.intBitsToFloat(data[at+5]),
                        (color&255)/255f*((tint>>16)&255)/255f,((color>>8)&255)/255f*((tint>>8)&255)/255f,((color>>16)&255)/255f*(tint&255)/255f,(color>>>24)/255f));
                }
                polygon=clip(polygon,slot.clipAxis(),slot.clipValue()/16,slot.above());if(polygon.size()<3)continue;
                Vector3f normal=normals.transform(new Vector3f(quad.getDirection().getStepX(),quad.getDirection().getStepY(),quad.getDirection().getStepZ())).normalize();
                for(Vertex v:polygon){bounds[0]=Math.min(bounds[0],v.x*16);bounds[1]=Math.min(bounds[1],v.y*16);bounds[2]=Math.min(bounds[2],v.z*16);bounds[3]=Math.max(bounds[3],v.x*16);bounds[4]=Math.max(bounds[4],v.y*16);bounds[5]=Math.max(bounds[5],v.z*16);}
                if(polygon.size()==4){for(Vertex v:polygon)vertex(out,pose,v,normal,light,overlay);count++;}
                else for(int at=1;at<polygon.size()-1;at++){vertex(out,pose,polygon.get(0),normal,light,overlay);vertex(out,pose,polygon.get(at),normal,light,overlay);vertex(out,pose,polygon.get(at+1),normal,light,overlay);vertex(out,pose,polygon.get(at+1),normal,light,overlay);count++;}
            }
        }
        audit=new Audit(++sequence,CardData.read(card).map(c->c.instanceId()).orElse(null),body,HardwareCatalog.model(card),total,count,count==0?new float[6]:bounds);return count;
    }
    private static void vertex(VertexConsumer out,PoseStack pose,Vertex v,Vector3f normal,int light,int overlay){
        out.addVertex(pose.last(),v.x,v.y,v.z).setColor(v.r,v.g,v.b,v.a).setUv(v.u,v.v).setOverlay(overlay).setLight(light).setNormal(pose.last(),normal.x,normal.y,normal.z);
    }
    private InsertedMediaRenderer(){}
}
