package net.montoyo.wd.block;

import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.montoyo.wd.entity.ScreenBlockEntity;
import net.montoyo.wd.item.ItemLinker;
import net.montoyo.wd.registry.WDRegistries;
import net.montoyo.wd.utilities.data.BlockSide;
import net.montoyo.wd.utilities.math.Vector3i;

public class ScreenBlock extends Block implements EntityBlock {
    public ScreenBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ScreenBlockEntity(pos, state);
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, InteractionHand hand, BlockHitResult hitResult) {
        BlockEntity be = level.getBlockEntity(pos);
        if (be instanceof ScreenBlockEntity screen) {
            ItemStack stack = player.getItemInHand(hand);
            Item item = stack.getItem();

            if (item == WDRegistries.CONFIGURATOR) {
                BlockSide side = BlockSide.fromDirection(hitResult.getDirection());
                // Server-side: mark that screen was interacted with
                // Client GUI opening is handled in ClientInit via UseBlockCallback
                return InteractionResult.SUCCESS;
            }

            if (item == WDRegistries.LINKER) {
                BlockSide side = BlockSide.fromDirection(hitResult.getDirection());
                ItemLinker.onRightClickScreen(player, pos, side);
                return InteractionResult.SUCCESS;
            }

            // All other items (including bare hand) → PASS
            // Bare-hand screen creation is handled exclusively in ClientInit's UseBlockCallback
        }
        return InteractionResult.PASS;
    }

    /************************************************* DESTRUCTION HANDLING *************************************************/

    /**
     * Find the origin (top-left corner) of a potential multiblock screen.
     * Walks left then down along ScreenBlock rows/columns, stops when no ScreenBlock is found.
     * Equivalent to Forge's Multiblock.findOrigin().
     */
    private static Vector3i findOrigin(Level level, Vector3i pos, BlockSide side) {
        BlockPos.MutableBlockPos bp = new BlockPos.MutableBlockPos();
        // Walk left
        do {
            pos.x += (int) -side.right.x;
            pos.y += (int) -side.right.y;
            pos.z += (int) -side.right.z;
            bp.set(pos.x, pos.y, pos.z);
        } while (level.getBlockState(bp).getBlock() == WDRegistries.SCREEN_BLOCK);
        pos.x += (int) side.right.x;
        pos.y += (int) side.right.y;
        pos.z += (int) side.right.z;
        // Walk down (opposite of up)
        do {
            pos.x += (int) -side.up.x;
            pos.y += (int) -side.up.y;
            pos.z += (int) -side.up.z;
            bp.set(pos.x, pos.y, pos.z);
        } while (level.getBlockState(bp).getBlock() == WDRegistries.SCREEN_BLOCK);
        pos.x += (int) side.up.x;
        pos.y += (int) side.up.y;
        pos.z += (int) side.up.z;
        return pos;
    }

    /**
     * For a specific BlockSide, find the origin and destroy its ScreenBlockEntity.
     * Equivalent to Forge's ScreenBlock.destroySide() — ensures each multiblock origin
     * runs ScreenBlockEntity.onDestroy() exactly once (guarded by the 'destroyed' flag).
     */
    private void destroySide(Level level, Vector3i pos, BlockSide side, Player source) {
        findOrigin(level, pos, side);
        BlockPos bp = pos.toBlock();
        BlockEntity be = level.getBlockEntity(bp);
        if (be instanceof ScreenBlockEntity screenBE) {
            screenBE.onDestroy(source);
        }
    }

    /**
     * Fabric 1.20.1 entry point (Forge equivalent: onDestroyedByPlayer).
     * Called BEFORE the block state is changed / block entity is removed.
     * Iterates all 6 sides to ensure all possible multiblock origins run their
     * onDestroy() exactly once (idempotent via destroyed flag) + broadcast DESTROY.
     */
    @Override
    public void playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide) {
            Vector3i bp = new Vector3i(pos);
            for (BlockSide side : BlockSide.values()) {
                destroySide(level, new Vector3i(bp), side, player);
            }
        }
        super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        // Fallback: handle non-player destruction (explosions, pistons, /fill, etc.)
        // LevelChunk.setBlockState() may have already removed the block entity,
        // so getBlockEntity() can return null here — onDestroy() is idempotent
        // (guarded by 'destroyed' flag) and setRemoved() is the final safety net.
        if (!state.is(newState.getBlock())) {
            if (!level.isClientSide) {
                Vector3i bp = new Vector3i(pos);
                for (BlockSide side : BlockSide.values()) {
                    destroySide(level, new Vector3i(bp), side, null);
                }
            }
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof ScreenBlockEntity screen) {
                screen.onDestroy(null);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
