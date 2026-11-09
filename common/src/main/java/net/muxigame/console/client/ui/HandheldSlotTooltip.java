package net.muxigame.console.client.ui;

import com.mojang.datafixers.util.Either;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;
import net.muxigame.console.mc.CardData;
import net.muxigame.console.mc.ConsoleContent;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;

/** One read-only slot in the ordinary inventory tooltip; the real item remains in its menu slot. */
public final class HandheldSlotTooltip implements ClientTooltipComponent {
    public record Contents(ItemStack card) implements TooltipComponent {
        public Contents { card=card.copy(); }
        @Override public ItemStack card(){return card.copy();}
    }
    private final ItemStack card;
    public HandheldSlotTooltip(Contents data){card=data.card();}
    public static void gather(RenderTooltipEvent.GatherComponents event){
        if(event.getItemStack().is(ConsoleContent.HANDHELD.get()))
            event.getTooltipElements().add(Either.right(new Contents(CardData.inserted(event.getItemStack()))));
    }
    private Component name(){return card.isEmpty()?Component.translatable("tooltip.muxi_console.slot_empty"):card.getHoverName();}
    @Override public int getHeight(){return 34;}
    @Override public int getWidth(Font font){return Math.max(110,Math.min(210,font.width(name())+26));}
    @Override public void renderImage(Font font,int x,int y,GuiGraphics graphics){
        graphics.drawString(font,Component.translatable("tooltip.muxi_console.slot"),x,y,0xffaaaaaa,false);
        graphics.fill(x,y+12,x+18,y+30,0xff373342);graphics.fill(x+1,y+13,x+17,y+29,0xff77717f);
        graphics.fill(x+1,y+13,x+17,y+14,0xff24202c);graphics.fill(x+1,y+13,x+2,y+29,0xff24202c);
        if(!card.isEmpty()){graphics.renderItem(card,x+1,y+13);graphics.renderItemDecorations(font,card,x+1,y+13);}
        graphics.drawString(font,font.plainSubstrByWidth(name().getString(),getWidth(font)-26),x+24,y+17,0xffeeeeee,false);
    }
}
