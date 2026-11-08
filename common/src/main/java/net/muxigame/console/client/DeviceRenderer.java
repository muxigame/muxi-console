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
    @Override public net.minecraft.world.phys.AABB getRenderBoundingBox(DeviceEntity device){return device.largeScreen()?ScreenLayout.bounds(device.screenAnchor(),device.getBlockState().getValue(DeviceBlock.FACING)):new net.minecraft.world.phys.AABB(device.getBlockPos());}
    public void render(DeviceEntity device,float partial,PoseStack stack,MultiBufferSource buffers,int light,int overlay){
        if(device.kind()==DeviceBlock.Kind.DOCK){dock(device,partial,stack,buffers,light,overlay);return;}
        long console;List<HardwareCatalog.Plane> planes;
        if(device.kind()==DeviceBlock.Kind.SCREEN){if(!device.screenPrimary()||device.linkedConsole==null)return;console=device.linkedConsole.asLong();planes=List.of(device.largeScreen()?new HardwareCatalog.Plane(-31.25f,.5f,15.25f,31.5f,6.90f):HardwareCatalog.television());}
        else if(device.kind()==DeviceBlock.Kind.DESK){console=device.getBlockPos().asLong();planes=HardwareCatalog.screens(device.getBlockState().getValue(DeviceBlock.MODEL));}
        else return;
        if(planes.isEmpty())return;
        ResourceLocation picture=picture(console);if(picture==null)return;
        Direction facing=device.getBlockState().getValue(DeviceBlock.FACING);stack.pushPose();stack.translate(.5,0,.5);stack.mulPose(Axis.YP.rotationDegrees(switch(facing){case EAST->-90;case SOUTH->180;case WEST->90;default->0;}));stack.translate(-.5,0,-.5);
        for(var plane:planes)screen(stack,buffers,picture,plane);
        stack.popPose();
    }
    private static ResourceLocation picture(long console){ResourceLocation picture=console==ConsoleClient.INSTANCE.screenConsole()?ConsoleClient.INSTANCE.picture():null;return picture==null?BrowserLinkClient.INSTANCE.pictureFor(console):picture;}
    private static void dock(DeviceEntity device,float partial,PoseStack stack,MultiBufferSource buffers,int light,int overlay){
        var item=device.dockedHandheld;if(item.isEmpty()||!item.is(ConsoleContent.HANDHELD.get()))return;
        var body=HardwareCatalog.body(HardwareCatalog.model(item));float[] b=body==null?new float[]{0,3,5.8f,16,12.4f,8.5f}:body.bounds();
        float scale=.7f;float centerX=(b[0]+b[3])/32f,centerZ=(b[2]+b[5])/32f;
        // The lowest authored vertex stays at 4/16, one pixel above the 3/16 base: no vertical bob.
        stack.pushPose();stack.translate(.5,.25-scale*b[1]/16f,.5);
        float angle=device.getLevel()==null?0:((device.getLevel().getGameTime()%7200)+partial)*.5f;
        stack.mulPose(Axis.YP.rotationDegrees(angle));stack.scale(scale,scale,scale);stack.translate(-centerX,0,-centerZ);
        HandheldRenderer.docked(item,picture(device.getBlockPos().asLong()),stack,buffers,light,overlay);stack.popPose();
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
