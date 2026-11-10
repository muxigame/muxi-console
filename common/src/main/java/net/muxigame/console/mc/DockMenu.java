package net.muxigame.console.mc;

import com.google.gson.JsonObject;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * One persistent, shared physical slot; vanilla menu packets own every transfer.
 * A dock holds a whole handheld; a desktop console holds its cartridge or disc.
 */
public final class DockMenu extends AbstractContainerMenu {
    private final DeviceBlock.Kind kind;
    private final BlockPos position;
    private final UUID identity;
    private final DeviceEntity device;
    private final Player viewer;
    private final Container contents;
    private final ContainerData data;

    public static DockMenu dock(int id,Inventory inventory,FriendlyByteBuf buffer){
        return new DockMenu(DeviceBlock.Kind.DOCK,id,inventory,buffer.readBlockPos(),buffer.readUUID(),null);
    }
    public static DockMenu desk(int id,Inventory inventory,FriendlyByteBuf buffer){
        return new DockMenu(DeviceBlock.Kind.DESK,id,inventory,buffer.readBlockPos(),buffer.readUUID(),null);
    }
    public DockMenu(int id,Inventory inventory,DeviceEntity device){
        this(device.kind(),id,inventory,device.getBlockPos(),device.identity,device);
    }
    private DockMenu(DeviceBlock.Kind kind,int id,Inventory inventory,BlockPos position,UUID identity,DeviceEntity device){
        super(type(kind),id);
        if(kind!=DeviceBlock.Kind.DOCK&&kind!=DeviceBlock.Kind.DESK)throw new IllegalArgumentException("Only docks and desktop consoles have a machine slot");
        this.kind=kind;this.position=position.immutable();this.identity=identity;this.device=device;this.viewer=inventory.player;
        contents=device==null?new SimpleContainer(1):new MachineContents();
        data=device==null?new SimpleContainerData(4):new ContainerData(){
            @Override public int get(int index){return switch(index){case 0->device.powered?1:0;case 1->device.controllerCapacity();case 2->ConsoleServer.machineSlotLocked(device)?1:0;case 3->device.hardwareModel();default->0;};}
            @Override public void set(int index,int value){}
            @Override public int getCount(){return 4;}
        };
        addDataSlots(data);
        addSlot(new Slot(contents,0,80,28){
            @Override public boolean mayPlace(ItemStack item){return stillValid(viewer)&&!locked()&&acceptsHere(item)&&(device==null||ConsoleServer.machineSlotAccepts(viewer,device,item));}
            @Override public boolean mayPickup(Player player){return stillValid(player)&&!locked();}
            @Override public int getMaxStackSize(){return 1;}
        });
        for(int row=0;row<3;row++)for(int col=0;col<9;col++)addSlot(new Slot(inventory,9+row*9+col,8+18*col,140+18*row));
        for(int col=0;col<9;col++)addSlot(new Slot(inventory,col,8+18*col,198));
    }
    private static MenuType<DockMenu> type(DeviceBlock.Kind kind){return kind==DeviceBlock.Kind.DESK?ConsoleContent.DESK_MENU.get():ConsoleContent.DOCK_MENU.get();}
    /** Item shape only; the server additionally checks content, idleness and the desk's model. */
    public boolean acceptsHere(ItemStack item){return desk()?acceptsMedia(item,model()):accepts(item);}
    public static boolean accepts(ItemStack item){
        if(!item.is(ConsoleContent.HANDHELD.get())||item.getCount()!=1)return false;
        var body=HardwareCatalog.body(HardwareCatalog.model(item));
        if(body==null||!body.handheld())return false;
        var card=CardData.read(item);
        return (!CardData.hasInserted(item)||card.isPresent())&&card.map(c->HardwareCatalog.accepts(body.id(),c.platform())).orElse(true);
    }
    public static boolean acceptsMedia(ItemStack item,int model){
        if(!item.is(ConsoleContent.CARTRIDGE.get())||item.getCount()!=1)return false;
        var card=CardData.read(item);
        // Before the first data sync the client does not know the model yet; the server decides.
        return card.isPresent()&&(model==0||HardwareCatalog.accepts(model,card.get().platform()));
    }
    public boolean desk(){return kind==DeviceBlock.Kind.DESK;}
    public BlockPos position(){return position;}
    public boolean powered(){return data.get(0)!=0;}
    public int capacity(){return data.get(1);}
    public boolean locked(){return data.get(2)!=0;}
    public int model(){return data.get(3);}
    public boolean matches(DeviceEntity candidate){return device==candidate&&identity.equals(candidate.identity)&&stillValid(viewer);}
    public JsonObject context(){
        ItemStack item=contents.getItem(0);JsonObject j=new JsonObject();
        j.addProperty("position",position.asLong());j.addProperty("identity",identity.toString());j.addProperty("containerId",containerId);j.addProperty("kind",desk()?"desk":"dock");
        j.addProperty("docked",!item.isEmpty());j.addProperty("powered",powered());j.addProperty("ports",capacity());j.addProperty("model",desk()?model():HardwareCatalog.model(item));
        CardData.read(item).ifPresent(card->{JsonObject c=new JsonObject();c.addProperty("instance",card.instanceId().toString());c.addProperty("platform",card.platform().id());c.addProperty("hash",card.sha256());c.addProperty("size",card.size());c.addProperty("title",card.title());j.add("card",c);});
        return j;
    }
    @Override public boolean stillValid(Player player){
        if(player!=viewer||!player.level().hasChunkAt(position)||player.distanceToSqr(position.getCenter())>64)return false;
        if(!(player.level().getBlockEntity(position) instanceof DeviceEntity current)||current.kind()!=kind||!identity.equals(current.identity))return false;
        return device==null||!device.isRemoved()&&device==current&&device.getLevel()==player.level();
    }
    @Override public void clicked(int index,int button,ClickType type,Player player){
        if(!stillValid(player)){player.closeContainer();return;}
        if(index==0){
            if(locked())return;
        }
        super.clicked(index,button,type,player);
    }
    @Override public boolean canDragTo(Slot slot){return (slot!=slots.getFirst()||!locked())&&super.canDragTo(slot);}
    @Override public boolean canTakeItemForPickAll(ItemStack item,Slot slot){return (slot!=slots.getFirst()||!locked())&&super.canTakeItemForPickAll(item,slot);}
    @Override public ItemStack quickMoveStack(Player player,int index){
        if(!stillValid(player)||index<0||index>=slots.size())return ItemStack.EMPTY;
        Slot slot=slots.get(index);if(!slot.hasItem()||!slot.mayPickup(player))return ItemStack.EMPTY;
        ItemStack item=slot.getItem(),copy=item.copy();
        if(index==0){if(!moveItemStackTo(item,1,37,true))return ItemStack.EMPTY;}
        else if(slots.getFirst().mayPlace(item)&&!slots.getFirst().hasItem()){if(!moveItemStackTo(item,0,1,false))return ItemStack.EMPTY;}
        else if(index<28){if(!moveItemStackTo(item,28,37,false))return ItemStack.EMPTY;}
        else if(!moveItemStackTo(item,1,28,false))return ItemStack.EMPTY;
        if(item.isEmpty())slot.setByPlayer(ItemStack.EMPTY);else slot.setChanged();
        slot.onTake(player,item);return copy;
    }
    private final class MachineContents implements Container {
        private ItemStack held(){return desk()?device.card:device.dockedHandheld;}
        private void hold(ItemStack item){if(desk())device.card=item;else device.dockedHandheld=item;}
        @Override public int getContainerSize(){return 1;}
        @Override public boolean isEmpty(){return held().isEmpty();}
        @Override public ItemStack getItem(int index){return index==0?held():ItemStack.EMPTY;}
        @Override public ItemStack removeItem(int index,int count){
            if(index!=0||count<=0||!stillValid(viewer)||ConsoleServer.machineSlotLocked(device))return ItemStack.EMPTY;
            ItemStack removed=held().split(count);if(!removed.isEmpty())setChanged();return removed;
        }
        @Override public ItemStack removeItemNoUpdate(int index){
            if(index!=0||!stillValid(viewer)||ConsoleServer.machineSlotLocked(device))return ItemStack.EMPTY;
            ItemStack removed=held();hold(ItemStack.EMPTY);if(!removed.isEmpty())setChanged();return removed;
        }
        @Override public void setItem(int index,ItemStack item){
            if(index!=0)return;
            // Menu transfer paths already checked mayPlace/mayPickup in this same server-thread operation.
            if(!item.isEmpty()&&!desk())MachineData.ensure(item);
            hold(item);setChanged();
        }
        @Override public void setChanged(){device.powered=false;device.changed();}
        @Override public boolean stillValid(Player player){return DockMenu.this.stillValid(player);}
        @Override public int getMaxStackSize(){return 1;}
        @Override public void clearContent(){if(!ConsoleServer.machineSlotLocked(device)){hold(ItemStack.EMPTY);setChanged();}}
    }
}
