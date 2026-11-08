package net.muxigame.console.mc;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.muxigame.console.domain.DomainException;

/** One physical cartridge slot. Menu packets execute these transfers on the server's actual slot/cursor stacks. */
public final class HandheldInventory {
    public static final int CAPACITY=1;
    public static void checkInsertion(Player player,ItemStack handheld,ItemStack card){
        if(handheld.getCount()!=1||!handheld.is(ConsoleContent.HANDHELD.get()))throw new DomainException("slot","只能操作一台掌机");
        CardData.read(handheld).ifPresent(c->ConsoleServer.requireCardIdle(player,c.instanceId()));
        if(CardData.hasInserted(handheld))throw new DomainException("slot","卡槽已满，请先取出原卡带");
        if(card.getCount()!=1||!card.is(ConsoleContent.CARTRIDGE.get()))throw new DomainException("slot","卡槽只能放入一张已刻录卡带");
        var data=CardData.read(card).orElseThrow(()->new DomainException("card","卡带数据无效"));
        if(!HardwareCatalog.accepts(HardwareCatalog.model(handheld),data.platform()))throw new DomainException("hardware","卡带与掌机机型不兼容");
        ConsoleServer.requireCardIdle(player,data.instanceId());
    }
    private static ItemStack removable(Player player,ItemStack handheld){
        if(handheld.getCount()!=1)throw new DomainException("slot","只能操作一台掌机");
        ItemStack card=CardData.inserted(handheld);
        if(card.isEmpty())throw new DomainException("slot",CardData.hasInserted(handheld)?"卡槽数据异常，已保留原内容":"卡槽为空");
        ConsoleServer.requireCardIdle(player,CardData.read(card).orElseThrow().instanceId());return card;
    }
    /** The mouse holds a card; the hovered slot holds the handheld. */
    public static boolean onHandheld(ItemStack handheld,ItemStack carried,Slot slot,ClickAction action,Player player,SlotAccess cursor){
        if(action!=ClickAction.SECONDARY)return false;
        if(!slot.isActive()||slot.isFake()||!slot.allowModification(player)||slot.getItem()!=handheld)return true;
        if(player.level().isClientSide)return true;
        if(!carried.isEmpty()&&cursor.get()!=carried)return true;
        try{
            if(carried.isEmpty()){
                ItemStack card=removable(player,handheld);
                if(!cursor.get().isEmpty()||!cursor.set(card))return true;
                CardData.clear(handheld);
            }else{
                checkInsertion(player,handheld,carried);CardData.install(handheld,carried);carried.shrink(1);
            }
            slot.setChanged();player.getInventory().setChanged();
        }catch(DomainException denied){message(player,denied.getMessage());}
        return true;
    }
    /** The mouse holds the handheld; the hovered inventory slot is the explicit source or destination. */
    public static boolean onOther(ItemStack handheld,Slot slot,ClickAction action,Player player){
        if(action!=ClickAction.SECONDARY)return false;
        if(!slot.isActive()||slot.isFake()||!slot.allowModification(player))return true;
        if(player.level().isClientSide)return true;
        if(player.containerMenu.getCarried()!=handheld)return true;
        try{
            ItemStack other=slot.getItem();
            if(other.isEmpty()){
                ItemStack card=removable(player,handheld);
                if(!slot.mayPlace(card)||slot.getMaxStackSize(card)<1)return true;
                ItemStack remaining=slot.safeInsert(card);
                if(!remaining.isEmpty())return true;
                CardData.clear(handheld);
            }else{
                checkInsertion(player,handheld,other);if(!slot.mayPickup(player))return true;
                ItemStack taken=slot.safeTake(1,1,player);if(taken.isEmpty())return true;
                CardData.install(handheld,taken);
            }
            slot.setChanged();player.getInventory().setChanged();
        }catch(DomainException denied){message(player,denied.getMessage());}
        return true;
    }
    public static void insertFromHand(Player player,ItemStack handheld,ItemStack card){checkInsertion(player,handheld,card);CardData.install(handheld,card);card.shrink(1);player.getInventory().setChanged();}
    public static void ejectToInventory(Player player,ItemStack handheld){
        ItemStack card=removable(player,handheld);
        int slot=player.getInventory().getFreeSlot();
        if(slot<0)throw new DomainException("slot","物品栏已满，卡带仍保留在掌机中");
        player.getInventory().setItem(slot,card);
        CardData.clear(handheld);player.getInventory().setChanged();
    }
    private static void message(Player player,String text){if(player instanceof ServerPlayer)player.displayClientMessage(Component.literal(text),true);}
    private HandheldInventory(){}
}
