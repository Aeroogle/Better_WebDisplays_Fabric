package net.montoyo.wd.entity;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.montoyo.wd.registry.WDRegistries;
import net.montoyo.wd.utilities.data.BlockSide;
import org.jetbrains.annotations.Nullable;

public class KeyboardBlockEntity extends BlockEntity {
    private BlockPos linkedPos = null;
    private BlockSide linkedSide = null;

    public KeyboardBlockEntity(BlockPos pos, BlockState state) {
        super(WDRegistries.KEYBOARD_BLOCK_ENTITY, pos, state);
    }

    public void setLinked(BlockPos pos, BlockSide side) {
        this.linkedPos = pos;
        this.linkedSide = side;
        setChanged();
    }

    public void clearLinked() {
        this.linkedPos = null;
        this.linkedSide = null;
        setChanged();
    }

    public @Nullable BlockPos getLinkedPos() { return linkedPos; }
    public @Nullable BlockSide getLinkedSide() { return linkedSide; }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        if (linkedPos != null) {
            tag.putInt("lx", linkedPos.getX());
            tag.putInt("ly", linkedPos.getY());
            tag.putInt("lz", linkedPos.getZ());
            tag.putInt("ls", linkedSide.id);
        }
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        if (tag.contains("lx")) {
            linkedPos = new BlockPos(tag.getInt("lx"), tag.getInt("ly"), tag.getInt("lz"));
            linkedSide = BlockSide.fromInt(tag.getInt("ls"));
        } else {
            linkedPos = null;
            linkedSide = null;
        }
    }

    @Override
    public CompoundTag getUpdateTag() {
        CompoundTag tag = super.getUpdateTag();
        saveAdditional(tag);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
