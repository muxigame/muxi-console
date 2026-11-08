package net.muxigame.console.client;

import com.google.gson.JsonObject;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.HumanoidArm;
import net.muxigame.console.MuxiConsole;
import net.muxigame.console.mc.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderHandEvent;

/** Map-like support arms, with the original authored body and live screen in one transform. */
@EventBusSubscriber(modid=MuxiConsole.ID,value=Dist.CLIENT)
public final class HandheldPresentation {
    private static long sequence;
    private static JsonObject main=new JsonObject(),off=new JsonObject();
    public static JsonObject snapshot(){JsonObject result=new JsonObject();result.add("main",main.deepCopy());result.add("off",off.deepCopy());return result;}
    @SubscribeEvent public static void hand(RenderHandEvent event){
        Minecraft mc=Minecraft.getInstance();if(mc.player==null)return;
        boolean mainHeld=mc.player.getMainHandItem().is(ConsoleContent.HANDHELD.get()),offHeld=mc.player.getOffhandItem().is(ConsoleContent.HANDHELD.get());
        if(!mainHeld&&!offHeld)return;
        boolean isMain=event.getHand()==InteractionHand.MAIN_HAND;
        if(!(isMain?mainHeld:offHeld)){
            if(mc.player.getItemInHand(event.getHand()).isEmpty())event.setCanceled(true);
            return;
        }
        event.setCanceled(true);
        var item=mc.player.getItemInHand(event.getHand());int model=HardwareCatalog.model(item);
        var body=HardwareCatalog.body(model);float[] bounds=body==null?new float[]{0,3,5.8f,16,12.4f,8.5f}:body.bounds();
        boolean right=isMain==(mc.player.getMainArm()==HumanoidArm.RIGHT),two=isMain&&mc.player.getOffhandItem().isEmpty();
        float side=right?1:-1,equip=event.getEquipProgress();
        float sway=(float)Math.sin(Math.sqrt(event.getSwingProgress())*Math.PI),bob=(float)Math.sin(Math.sqrt(event.getSwingProgress())*Math.PI*2);
        PoseStack pose=event.getPoseStack();
        float width=(bounds[3]-bounds[0])/16,height=(bounds[4]-bounds[1])/16;
        float scale=Math.min((two?.83f:.61f)/Math.max(width,.01f),(two?.64f:.49f)/Math.max(height,.01f));
        float centerX=two?0:side*.31f,centerY=-.16f-equip*.55f+bob*.02f,depth=-.94f-sway*.04f;
        float support=width*scale/2-.085f;
        boolean leftDrawn=false,rightDrawn=false;
        if(two){leftDrawn=arm(event,-1,centerX-support,centerY-height*scale*.30f,sway);rightDrawn=arm(event,1,centerX+support,centerY-height*scale*.30f,sway);}
        else if(right)rightDrawn=arm(event,side,centerX+side*(width*scale*.25f),centerY-height*scale*.30f,sway);
        else leftDrawn=arm(event,side,centerX+side*(width*scale*.25f),centerY-height*scale*.30f,sway);
        pose.pushPose();
        int highlighted;
        try{
            pose.translate(centerX,centerY,depth);
            pose.mulPose(Axis.XP.rotationDegrees(8+sway*3));pose.mulPose(Axis.YP.rotationDegrees(180+(two?0:-side*5)));pose.mulPose(Axis.ZP.rotationDegrees(two?-sway:side*3));
            pose.scale(scale,scale,scale);
            pose.translate(-(bounds[0]+bounds[3])/32,-(bounds[1]+bounds[4])/32,-(bounds[2]+bounds[5])/32);
            highlighted=HandheldRenderer.presented(item,pose,event.getMultiBufferSource(),event.getPackedLight());
        }finally{pose.popPose();}
        var pad=CardData.read(item).map(card->ConsoleClient.INSTANCE.heldInputVisual(card.instanceId())).orElse(net.muxigame.console.runtime.PortInput.IDLE);
        JsonObject frame=new JsonObject();frame.addProperty("sequence",++sequence);frame.addProperty("renderedAtNanos",System.nanoTime());frame.addProperty("model",model);frame.addProperty("hand",isMain?"main":"off");frame.addProperty("arm",right?"right":"left");frame.addProperty("twoHands",two);frame.addProperty("armsVisible",leftDrawn||rightDrawn);frame.addProperty("leftArmDrawn",leftDrawn);frame.addProperty("rightArmDrawn",rightDrawn);frame.addProperty("renderedArms",(leftDrawn?1:0)+(rightDrawn?1:0));frame.addProperty("buttons",pad.buttons());frame.addProperty("surfaceMask",HandheldFeedback.mask(pad));frame.addProperty("highlightedSurfaces",highlighted);
        if(isMain)main=frame;else off=frame;
    }
    private static boolean arm(RenderHandEvent event,float side,float contactX,float contactY,float sway){
        Minecraft mc=Minecraft.getInstance();if(mc.player==null||mc.player.isInvisible())return false;
        PoseStack pose=event.getPoseStack();pose.pushPose();
        try{
            // The wrist reaches the lower grip; the arm extends down out of view rather than over the LCD.
            pose.translate(contactX-side*.36f,contactY+.34f,-.72f-sway*.04f);
            pose.mulPose(Axis.YP.rotationDegrees(180));pose.mulPose(Axis.XP.rotationDegrees(45));pose.mulPose(Axis.ZP.rotationDegrees(-side*41));
            pose.translate(side*.30f,-1.10f,.45f);
            if(mc.getEntityRenderDispatcher().getRenderer(mc.player) instanceof PlayerRenderer player){if(side>0)player.renderRightHand(pose,event.getMultiBufferSource(),event.getPackedLight(),mc.player);else player.renderLeftHand(pose,event.getMultiBufferSource(),event.getPackedLight(),mc.player);return true;}
            return false;
        }finally{pose.popPose();}
    }
    private HandheldPresentation(){}
}
