package lgbt.faith.chiyoko.mixin

import lgbt.faith.chiyoko.DropEventState
import lgbt.faith.chiyoko.PendingGravelBreak
import lgbt.faith.chiyoko.enchantmentLevel
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(Level::class)
class LevelMixin {

    @Inject(method = ["setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z"], at = [At("HEAD")])
    private fun onSetBlock(pos: BlockPos, newState: BlockState, flags: Int, maxUpdateDepth: Int, ci: CallbackInfoReturnable<Boolean>) {
        val level = this as Level
        if (!level.isClientSide) return

        val oldState = level.getBlockState(pos)
        if (oldState.block == Blocks.GRAVEL && newState.block != Blocks.GRAVEL) {
            if (DropEventState.selfBrokenBlocks.remove(pos) == null) return

            val mc = Minecraft.getInstance()
            val player = mc.player ?: return
            if (Vec3.atCenterOf(pos).distanceTo(player.position()) > 12.0) return

            val tool = player.mainHandItem

            val silkTouch = enchantmentLevel(level, Enchantments.SILK_TOUCH, tool) ?: return

            if (silkTouch > 0) return

            val fortune = enchantmentLevel(level, Enchantments.FORTUNE, tool) ?: return

            DropEventState.pendingGravels.add(PendingGravelBreak(Vec3.atCenterOf(pos), fortune))
        }
    }
}