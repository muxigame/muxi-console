package net.muxigame.console.client;

import com.mojang.blaze3d.vertex.PoseStack;
import java.util.*;
import net.muxigame.console.mc.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.*;
import net.minecraft.client.renderer.entity.ItemRenderer;
import net.minecraft.client.renderer.block.model.ItemOverrides;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.*;
import net.neoforged.neoforge.client.model.BakedModelWrapper;

/** Draws the authored baked body and real running picture under precisely the same item transform. */
public final class HandheldRenderer extends BlockEntityWithoutLevelRenderer {
    public HandheldRenderer(){super(Minecraft.getInstance().getBlockEntityRenderDispatcher(),Minecraft.getInstance().getEntityModels());}
    public static BakedModel wrap(BakedModel model){return model instanceof LiveModel?model:new LiveModel(model);}
    public static final class LiveModel extends BakedModelWrapper<BakedModel>{
        private final ItemOverrides overrides;
        public LiveModel(BakedModel original){super(original);overrides=new ItemOverrides(){
            @Override public BakedModel resolve(BakedModel ignored,ItemStack item,ClientLevel level,LivingEntity entity,int seed){BakedModel selected=original.getOverrides().resolve(original,item,level,entity,seed);return selected==null?LiveModel.this:wrap(selected);}
        };}
        @Override public boolean isCustomRenderer(){return true;}
        @Override public ItemOverrides getOverrides(){return overrides;}
        @Override public BakedModel applyTransform(ItemDisplayContext display,PoseStack pose,boolean left){BakedModel transformed=originalModel.applyTransform(display,pose,left);return transformed==originalModel?this:wrap(transformed);}
        public BakedModel body(){return originalModel;}
    }
    @Override public void renderByItem(ItemStack item,ItemDisplayContext display,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        if(item.is(ConsoleContent.CONTROLLER.get())){
            var input=display==ItemDisplayContext.FIRST_PERSON_LEFT_HAND||display==ItemDisplayContext.FIRST_PERSON_RIGHT_HAND?ConsoleClient.INSTANCE.localInputVisual():net.muxigame.console.runtime.PortInput.IDLE;
            body(item,pose,buffers,light,overlay,input);
            return;
        }
        body(item,pose,buffers,light,overlay,input(item));
        live(item,pose,buffers);
    }
    private static net.muxigame.console.runtime.PortInput input(ItemStack item){return CardData.read(item).map(c->ConsoleClient.INSTANCE.heldInputVisual(c.instanceId())).orElse(net.muxigame.console.runtime.PortInput.IDLE);}
    private static void live(ItemStack item,PoseStack pose,MultiBufferSource buffers){
        var state=ConsoleClient.INSTANCE.snapshot();var picture=ConsoleClient.INSTANCE.picture();
        if(!state.ready()||!state.room().startsWith("handheld/"))return;
        var card=CardData.read(item).filter(c->state.room().equals("handheld/"+c.instanceId()));if(card.isEmpty())return;
        if(picture!=null)picture(item,picture,pose,buffers);
    }
    public static int presented(ItemStack item,PoseStack pose,MultiBufferSource buffers,int light){
        int highlighted=body(item,pose,buffers,light,net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,input(item));live(item,pose,buffers);return highlighted;
    }
    private static int body(ItemStack item,PoseStack pose,MultiBufferSource buffers,int light,int overlay,net.muxigame.console.runtime.PortInput input){
        Minecraft mc=Minecraft.getInstance();BakedModel selected=mc.getItemRenderer().getModel(item,mc.level,mc.player,0);BakedModel body=selected instanceof LiveModel live?live.body():selected;
        return HandheldFeedback.body(body,item,pose,buffers,light,overlay,item.is(ConsoleContent.CONTROLLER.get())?HardwareCatalog.controllerRegions():HardwareCatalog.regions(HardwareCatalog.model(item)),input,0);
    }
    private static void picture(ItemStack item,net.minecraft.resources.ResourceLocation picture,PoseStack pose,MultiBufferSource buffers){
        int id=HardwareCatalog.model(item);List<HardwareCatalog.Plane> planes=id==0?List.of(new HardwareCatalog.Plane(4.3f,5.2f,11.7f,10.75f,5.98f)):HardwareCatalog.screens(id);
        for(var plane:planes)DeviceRenderer.screen(pose,buffers,picture,plane);
    }
    /** Already in authored model coordinates: no BEWLR or hand-presentation recursion, no global picture override. */
    public static void docked(ItemStack item,long console,net.minecraft.resources.ResourceLocation sourcePicture,PoseStack pose,MultiBufferSource buffers,int light,int overlay){
        body(item,pose,buffers,light,overlay,ConsoleClient.INSTANCE.deviceInputVisual(console));if(sourcePicture!=null)picture(item,sourcePicture,pose,buffers);
    }

    public static ItemStack heldForSession(){
        var mc=Minecraft.getInstance();if(mc.player==null)return ItemStack.EMPTY;String room=ConsoleClient.INSTANCE.snapshot().room();
        for(var hand:net.minecraft.world.InteractionHand.values()){var item=mc.player.getItemInHand(hand);if(item.is(ConsoleContent.HANDHELD.get())&&CardData.read(item).filter(c->room.equals("handheld/"+c.instanceId())).isPresent())return item;}
        return ItemStack.EMPTY;
    }

}
