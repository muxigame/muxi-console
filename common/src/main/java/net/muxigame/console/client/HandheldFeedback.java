package net.muxigame.console.client;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.muxigame.console.mc.HardwareCatalog;
import net.muxigame.console.runtime.PortInput;
import java.util.List;

/** Highlights only the authored surface of the actual logical control; it never sends input. */
public final class HandheldFeedback {
    private static final ResourceLocation WHITE=ResourceLocation.fromNamespaceAndPath("muxi_console","textures/block/hardware/feedback.png");
    public static int mask(PortInput input){
        int mask=input.buttons();
        if(input.leftX()<-12000)mask|=1<<6;if(input.leftX()>12000)mask|=1<<7;
        if(input.leftY()<-12000)mask|=1<<4;if(input.leftY()>12000)mask|=1<<5;
        return mask;
    }
    public static int draw(PoseStack pose,MultiBufferSource buffers,List<HardwareCatalog.ControlSurface> surfaces,PortInput input,int port){
        int mask=mask(input),count=0;if(mask==0)return 0;
        var out=buffers.getBuffer(RenderType.entityTranslucent(WHITE));
        for(var p:surfaces){
            if(p.port()!=port||(mask&(1<<p.button()))==0)continue;count++;
            float x0=p.x0()/16,x1=p.x1()/16,y0=p.y0()/16,y1=p.y1()/16,z0=p.z0()/16,z1=p.z1()/16;
            if(p.facing().equals("north")){
                z0-=.0007f;
                vertex(out,pose,x0,y0,z0,0,1,0,0,-1);vertex(out,pose,x0,y1,z0,0,0,0,0,-1);
                vertex(out,pose,x1,y1,z0,1,0,0,0,-1);vertex(out,pose,x1,y0,z0,1,1,0,0,-1);
            }else{
                y0+=.0007f;
                vertex(out,pose,x0,y0,z0,0,0,0,1,0);vertex(out,pose,x0,y0,z1,0,1,0,1,0);
                vertex(out,pose,x1,y0,z1,1,1,0,1,0);vertex(out,pose,x1,y0,z0,1,0,0,1,0);
            }
        }
        return count;
    }
    private static void vertex(com.mojang.blaze3d.vertex.VertexConsumer out,PoseStack pose,float x,float y,float z,float u,float v,float nx,float ny,float nz){
        out.addVertex(pose.last(),x,y,z).setColor(93,199,255,210).setUv(u,v).setOverlay(OverlayTexture.NO_OVERLAY).setLight(LightTexture.FULL_BRIGHT).setNormal(pose.last(),nx,ny,nz);
    }
    private HandheldFeedback(){}
}
