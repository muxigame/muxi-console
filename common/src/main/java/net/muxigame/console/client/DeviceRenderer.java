package net.muxigame.console.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.muxigame.console.mc.*;
import net.muxigame.console.compat.BrowserLinkClient;
import net.muxigame.console.domain.VideoLayout;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.blockentity.*;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import java.util.List;

/** Original screen geometry contract; frontend picture is independent of hardware model implementation. */
public final class DeviceRenderer implements BlockEntityRenderer<DeviceEntity> {
    public DeviceRenderer(BlockEntityRendererProvider.Context context){}
    public void render(DeviceEntity device,float partial,PoseStack stack,MultiBufferSource buffers,int light,int overlay){
        long console;List<HardwareCatalog.Plane> planes;
        if(device.kind()==DeviceBlock.Kind.SCREEN){if(device.linkedConsole==null)return;console=device.linkedConsole.asLong();planes=List.of(HardwareCatalog.television());}
        else if(device.kind()==DeviceBlock.Kind.DESK){console=device.getBlockPos().asLong();planes=HardwareCatalog.screens(device.getBlockState().getValue(DeviceBlock.MODEL));}
        else return;
        if(planes.isEmpty())return;
        ResourceLocation picture=console==ConsoleClient.INSTANCE.screenConsole()?ConsoleClient.INSTANCE.picture():null;
        if(picture==null)picture=BrowserLinkClient.INSTANCE.pictureFor(console);if(picture==null)return;
        Direction facing=device.getBlockState().getValue(DeviceBlock.FACING);stack.pushPose();stack.translate(.5,0,.5);stack.mulPose(Axis.YP.rotationDegrees(switch(facing){case EAST->-90;case SOUTH->180;case WEST->90;default->0;}));stack.translate(-.5,0,-.5);
        for(var plane:planes)screen(stack,buffers,picture,plane);
        stack.popPose();
    }
    public static void screen(PoseStack stack,MultiBufferSource buffers,ResourceLocation picture,HardwareCatalog.Plane p){
        double aspect=(p.x1()-p.x0())/(double)(p.y1()-p.y0());
        var texture=Minecraft.getInstance().getTextureManager().getTexture(picture);if(texture instanceof DynamicTexture dynamic&&dynamic.getPixels()!=null)aspect=dynamic.getPixels().getWidth()/(double)dynamic.getPixels().getHeight();
        float[] fitted=VideoLayout.fit(p.x0(),p.y0(),p.x1(),p.y1(),aspect);
        quad(stack,buffers,picture,p,0);
        quad(stack,buffers,picture,new HardwareCatalog.Plane(fitted[0],fitted[1],fitted[2],fitted[3],p.z()-.002f),255);
    }
    private static void quad(PoseStack stack,MultiBufferSource buffers,ResourceLocation picture,HardwareCatalog.Plane p,int color){
        Matrix4f matrix=stack.last().pose();var consumer=buffers.getBuffer(RenderType.text(picture));int full=LightTexture.FULL_BRIGHT;float z=(p.z()-.002f)/16f;
        consumer.addVertex(matrix,p.x0()/16f,p.y0()/16f,z).setColor(color,color,color,255).setUv(1,1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(full).setNormal(stack.last(),0,0,-1);
        consumer.addVertex(matrix,p.x0()/16f,p.y1()/16f,z).setColor(color,color,color,255).setUv(1,0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(full).setNormal(stack.last(),0,0,-1);
        consumer.addVertex(matrix,p.x1()/16f,p.y1()/16f,z).setColor(color,color,color,255).setUv(0,0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(full).setNormal(stack.last(),0,0,-1);
        consumer.addVertex(matrix,p.x1()/16f,p.y0()/16f,z).setColor(color,color,color,255).setUv(0,1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(full).setNormal(stack.last(),0,0,-1);
    }
}
