package net.muxigame.console.client;

import com.google.gson.*;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.muxigame.console.MuxiConsole;
import net.muxigame.console.mc.*;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/** A short item-selection hint above the hotbar, never a persistent game overlay. */
@EventBusSubscriber(modid=MuxiConsole.ID,value=Dist.CLIENT)
public final class HandheldHint {
    private static final String TEXT="右键切换全屏模式，Esc退出全屏";
    private static String selection="";
    private static int ticks;
    private static long sequence;
    private static JsonObject rendered=new JsonObject();
    @SubscribeEvent public static void tick(ClientTickEvent.Post event){
        var mc=Minecraft.getInstance();String next="";
        if(mc.player!=null)for(var hand:InteractionHand.values()){
            var item=mc.player.getItemInHand(hand);if(!item.is(ConsoleContent.HANDHELD.get()))continue;
            next=hand+":"+(hand==InteractionHand.MAIN_HAND?mc.player.getInventory().selected:-1)+":"+HardwareCatalog.model(item)+":"+CardData.read(item).map(c->c.instanceId().toString()).orElse("empty");break;
        }
        if(!next.equals(selection)){selection=next;ticks=next.isEmpty()?0:60;}
        else if(ticks>0)ticks--;
    }
    @SubscribeEvent public static void render(RenderGuiEvent.Post event){
        var mc=Minecraft.getInstance();boolean visible=ticks>0&&mc.player!=null&&mc.screen==null&&!mc.options.hideGui;
        JsonObject out=new JsonObject();out.addProperty("sequence",++sequence);out.addProperty("text",TEXT);out.addProperty("ticks",ticks);out.addProperty("visible",visible);out.addProperty("selection",selection);
        int w=mc.getWindow().getGuiScaledWidth(),h=mc.getWindow().getGuiScaledHeight(),textWidth=mc.font.width(TEXT),x=(w-textWidth)/2,y=h-68;
        out.addProperty("surfaceWidth",w);out.addProperty("surfaceHeight",h);JsonArray bounds=new JsonArray();bounds.add(x);bounds.add(y);bounds.add(textWidth);bounds.add(mc.font.lineHeight);out.add("bounds",bounds);
        if(visible){int alpha=Math.min(255,ticks*255/20);event.getGuiGraphics().drawString(mc.font,TEXT,x,y,(alpha<<24)|0xffffff,true);}
        rendered=out;
    }
    public static JsonObject snapshot(){JsonObject out=rendered.deepCopy();out.addProperty("ticks",ticks);if(ticks==0)out.addProperty("visible",false);return out;}
    private HandheldHint(){}
}
