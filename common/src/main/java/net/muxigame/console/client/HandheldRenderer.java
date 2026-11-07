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
        Minecraft mc=Minecraft.getInstance();BakedModel selected=mc.getItemRenderer().getModel(item,mc.level,mc.player,0);BakedModel body=selected instanceof LiveModel live?live.body():selected;
        mc.getItemRenderer().renderModelLists(body,item,light,overlay,pose,ItemRenderer.getFoilBufferDirect(buffers,RenderType.entityCutoutNoCull(TextureAtlas.LOCATION_BLOCKS),true,item.hasFoil()));
        var state=ConsoleClient.INSTANCE.snapshot();var picture=ConsoleClient.INSTANCE.picture();
        if(picture==null||!state.ready()||!state.room().startsWith("handheld/"))return;
        if(CardData.read(item).filter(c->state.room().equals("handheld/"+c.instanceId())).isEmpty())return;
        int id=HardwareCatalog.model(item);List<HardwareCatalog.Plane> planes=id==0?List.of(new HardwareCatalog.Plane(4.3f,5.2f,11.7f,10.75f,5.98f)):HardwareCatalog.screens(id);
        for(var plane:planes)DeviceRenderer.screen(pose,buffers,picture,plane);
    }
}
