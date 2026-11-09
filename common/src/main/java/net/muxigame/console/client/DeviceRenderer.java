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
import net.muxigame.console.runtime.AvFrame;

/** Original screen geometry contract; frontend picture is independent of hardware model implementation. */
public final class DeviceRenderer implements BlockEntityRenderer<DeviceEntity> {
    public DeviceRenderer(BlockEntityRendererProvider.Context context){}
    @Override public net.minecraft.world.phys.AABB getRenderBoundingBox(DeviceEntity device){return device.kind()==DeviceBlock.Kind.SCREEN?ScreenLayout.bounds(device):new net.minecraft.world.phys.AABB(device.getBlockPos());}
    public void render(DeviceEntity device,float partial,PoseStack stack,MultiBufferSource buffers,int light,int overlay){
        if(device.kind()==DeviceBlock.Kind.DOCK){dock(device,partial,stack,buffers,light,overlay);return;}
        long console;List<HardwareCatalog.Plane> planes;
        if(device.kind()==DeviceBlock.Kind.SCREEN){if(!device.screenPrimary()||device.linkedConsole==null)return;console=device.linkedConsole.asLong();planes=List.of(ScreenLayout.plane(device));}
        else if(device.kind()==DeviceBlock.Kind.DESK){console=device.getBlockPos().asLong();planes=HardwareCatalog.screens(device.getBlockState().getValue(DeviceBlock.MODEL));}
        else return;
        ResourceLocation picture=picture(console);var media=device.kind()==DeviceBlock.Kind.DESK?device.card:
            device.getLevel()!=null&&device.getLevel().getBlockEntity(net.minecraft.core.BlockPos.of(console)) instanceof DeviceEntity source?source.media():net.minecraft.world.item.ItemStack.EMPTY;
        var progress=LcdLoadingRenderer.deviceProgress(console,media);
        Direction facing=device.getBlockState().getValue(DeviceBlock.FACING);stack.pushPose();
        try{
            stack.translate(.5,0,.5);stack.mulPose(Axis.YP.rotationDegrees(switch(facing){case EAST->-90;case SOUTH->180;case WEST->90;default->0;}));stack.translate(-.5,0,-.5);
            if(device.kind()==DeviceBlock.Kind.DESK)InsertedMediaRenderer.drawDesktop(device.getBlockState().getValue(DeviceBlock.MODEL),device.card,stack,buffers,light,overlay);
            for(var plane:planes){if(LcdLoadingRenderer.visible(progress))LcdLoadingRenderer.draw(stack,buffers,plane,progress,"device/"+console);else if(picture!=null)screen(stack,buffers,picture,plane);}
            if(device.kind()==DeviceBlock.Kind.DESK)HandheldFeedback.draw(stack,buffers,HardwareCatalog.regions(device.getBlockState().getValue(DeviceBlock.MODEL)),ConsoleClient.INSTANCE.deviceInputVisual(console),ConsoleClient.INSTANCE.visualPort());
        }finally{stack.popPose();}
    }
    private static ResourceLocation picture(long console){ResourceLocation picture=console==ConsoleClient.INSTANCE.screenConsole()?ConsoleClient.INSTANCE.picture():null;return picture==null?BrowserLinkClient.INSTANCE.pictureFor(console):picture;}
    private static void dock(DeviceEntity device,float partial,PoseStack stack,MultiBufferSource buffers,int light,int overlay){
        var item=device.dockedHandheld;if(item.isEmpty()||!item.is(ConsoleContent.HANDHELD.get()))return;
        float[] b=HardwareCatalog.presentationBounds(item);
        float scale=.7f;float centerX=(b[0]+b[3])/32f,centerZ=(b[2]+b[5])/32f;
        // The lowest authored vertex stays at 4/16, one pixel above the 3/16 base: no vertical bob.
        stack.pushPose();stack.translate(.5,.25-scale*b[1]/16f,.5);
        float angle=device.getLevel()==null?0:((device.getLevel().getGameTime()%7200)+partial)*.5f;
        stack.mulPose(Axis.YP.rotationDegrees(angle));stack.scale(scale,scale,scale);stack.translate(-centerX,0,-centerZ);
        HandheldRenderer.docked(item,device.getBlockPos().asLong(),picture(device.getBlockPos().asLong()),stack,buffers,light,overlay);stack.popPose();
    }
    public static void screen(PoseStack stack,MultiBufferSource buffers,ResourceLocation picture,HardwareCatalog.Plane p){
        quad(stack,buffers,picture,p,0,0,0,1,1);
        var source=surface(picture,p);if(source==null&&!p.surface().equals("primary"))return;
        var texture=Minecraft.getInstance().getTextureManager().getTexture(picture);
        if(!(texture instanceof DynamicTexture dynamic)||dynamic.getPixels()==null)return;
        int width=dynamic.getPixels().getWidth(),height=dynamic.getPixels().getHeight();
        if(source!=null&&((long)source.x()+source.width()>width||(long)source.y()+source.height()>height))return;
        float u0=source==null?0:source.x()/(float)width,v0=source==null?0:source.y()/(float)height;
        float u1=source==null?1:(source.x()+source.width())/(float)width,v1=source==null?1:(source.y()+source.height())/(float)height;
        float[] fitted=VideoLayout.fit(p.x0(),p.y0(),p.x1(),p.y1(),aspect(picture,p));
        quad(stack,buffers,picture,new HardwareCatalog.Plane(fitted[0],fitted[1],fitted[2],fitted[3],p.z()-.002f),255,u0,v0,u1,v1);
    }
    public static AvFrame.Surface surface(ResourceLocation picture,HardwareCatalog.Plane plane){
        if(picture==null)return null;
        var surfaces=picture.equals(ConsoleClient.INSTANCE.picture())?ConsoleClient.INSTANCE.surfaces():BrowserLinkClient.INSTANCE.surfacesFor(picture);
        return surfaces.stream().filter(surface->surface.id().equals(plane.surface())).findFirst().orElse(null);
    }
    public static double aspect(ResourceLocation picture,HardwareCatalog.Plane plane){
        var source=surface(picture,plane);if(source!=null)return source.nativeWidth()/(double)source.nativeHeight();
        if(plane.nativeWidth()>0)return plane.nativeWidth()/(double)plane.nativeHeight();
        var texture=picture==null?null:Minecraft.getInstance().getTextureManager().getTexture(picture);
        if(texture instanceof DynamicTexture dynamic&&dynamic.getPixels()!=null)return dynamic.getPixels().getWidth()/(double)dynamic.getPixels().getHeight();
        return (plane.x1()-plane.x0())/(double)(plane.y1()-plane.y0());
    }
    private static void quad(PoseStack stack,MultiBufferSource buffers,ResourceLocation picture,HardwareCatalog.Plane p,int color,float u0,float v0,float u1,float v1){
        Matrix4f matrix=stack.last().pose();var consumer=buffers.getBuffer(RenderType.text(picture));int full=LightTexture.FULL_BRIGHT;float z=(p.z()-.002f)/16f;
        consumer.addVertex(matrix,p.x0()/16f,p.y0()/16f,z).setColor(color,color,color,255).setUv(u1,v1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(full).setNormal(stack.last(),0,0,-1);
        consumer.addVertex(matrix,p.x0()/16f,p.y1()/16f,z).setColor(color,color,color,255).setUv(u1,v0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(full).setNormal(stack.last(),0,0,-1);
        consumer.addVertex(matrix,p.x1()/16f,p.y1()/16f,z).setColor(color,color,color,255).setUv(u0,v0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(full).setNormal(stack.last(),0,0,-1);
        consumer.addVertex(matrix,p.x1()/16f,p.y0()/16f,z).setColor(color,color,color,255).setUv(u0,v1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(full).setNormal(stack.last(),0,0,-1);
    }
}
