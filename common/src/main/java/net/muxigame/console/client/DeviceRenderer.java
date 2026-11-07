package net.muxigame.console.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.muxigame.console.mc.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.blockentity.*;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;

/** Original screen geometry contract; frontend picture is independent of hardware model implementation. */
public final class DeviceRenderer implements BlockEntityRenderer<DeviceEntity> {
    public DeviceRenderer(BlockEntityRendererProvider.Context context){}
    public void render(DeviceEntity device,float partial,PoseStack stack,MultiBufferSource buffers,int light,int overlay){
        if(device.kind()!=DeviceBlock.Kind.SCREEN||device.linkedConsole==null||device.linkedConsole.asLong()!=ConsoleClient.INSTANCE.screenConsole())return;
        ResourceLocation picture=ConsoleClient.INSTANCE.picture();if(picture==null)return;
        Direction facing=device.getBlockState().getValue(DeviceBlock.FACING);stack.pushPose();stack.translate(.5,0,.5);stack.mulPose(Axis.YP.rotationDegrees(switch(facing){case EAST->-90;case SOUTH->180;case WEST->90;default->0;}));stack.translate(-.5,0,-.5);
        Matrix4f matrix=stack.last().pose();var consumer=buffers.getBuffer(RenderType.entityTranslucent(picture));int full=LightTexture.FULL_BRIGHT;float z=6.90f/16f;
        consumer.addVertex(matrix,1.5f/16f,3f/16f,z).setColor(255,255,255,255).setUv(1,1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(full).setNormal(stack.last(),0,0,-1);
        consumer.addVertex(matrix,1.5f/16f,12.75f/16f,z).setColor(255,255,255,255).setUv(1,0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(full).setNormal(stack.last(),0,0,-1);
        consumer.addVertex(matrix,14.5f/16f,12.75f/16f,z).setColor(255,255,255,255).setUv(0,0).setOverlay(OverlayTexture.NO_OVERLAY).setLight(full).setNormal(stack.last(),0,0,-1);
        consumer.addVertex(matrix,14.5f/16f,3f/16f,z).setColor(255,255,255,255).setUv(0,1).setOverlay(OverlayTexture.NO_OVERLAY).setLight(full).setNormal(stack.last(),0,0,-1);
        stack.popPose();
    }
}
