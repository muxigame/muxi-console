package net.muxigame.console.mc;

import java.util.UUID;
import net.minecraft.core.*;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

public final class DeviceEntity extends BlockEntity {
    public UUID identity=UUID.randomUUID();
    public ItemStack card=ItemStack.EMPTY;
    public int ports=0;
    public boolean powered=false;
    public BlockPos linkedConsole;
    public DeviceEntity(BlockPos pos,BlockState state){super(ConsoleContent.DEVICE.get(),pos,state);}
    public DeviceBlock.Kind kind(){return ((DeviceBlock)getBlockState().getBlock()).kind;}
    public void changed(){setChanged();if(level!=null)level.sendBlockUpdated(worldPosition,getBlockState(),getBlockState(),3);}
    protected void saveAdditional(CompoundTag t,HolderLookup.Provider lookup){
        super.saveAdditional(t,lookup);t.putUUID("identity",identity);t.putInt("ports",ports);t.putBoolean("powered",powered);
        if(!card.isEmpty())t.put("card",card.save(lookup));if(linkedConsole!=null)t.putLong("screen_source",linkedConsole.asLong());
    }
    protected void loadAdditional(CompoundTag t,HolderLookup.Provider lookup){
        super.loadAdditional(t,lookup);if(t.hasUUID("identity"))identity=t.getUUID("identity");ports=Math.clamp(t.getInt("ports"),0,4);powered=t.getBoolean("powered");
        card=t.contains("card")?ItemStack.parseOptional(lookup,t.getCompound("card")):ItemStack.EMPTY;linkedConsole=t.contains("screen_source")?BlockPos.of(t.getLong("screen_source")):null;
    }
    public CompoundTag getUpdateTag(HolderLookup.Provider lookup){return saveWithoutMetadata(lookup);}
    public ClientboundBlockEntityDataPacket getUpdatePacket(){return ClientboundBlockEntityDataPacket.create(this);}
}
