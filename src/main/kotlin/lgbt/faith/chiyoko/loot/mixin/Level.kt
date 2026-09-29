package lgbt.faith.chiyoko.loot.mixin

import lgbt.faith.chiyoko.loot.DropEventState
import lgbt.faith.chiyoko.loot.PendingGravelBreak
import lgbt.faith.chiyoko.loot.enchantmentLevel
import net.minecraft.client.MinecraftClient
import net.minecraft.util.math.BlockPos
import net.minecraft.enchantment.Enchantments
import net.minecraft.world.World
import net.minecraft.block.Blocks
import net.minecraft.block.BlockState
import net.minecraft.util.math.Vec3d
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(World::class)
class LevelMixin {

    @Inject(method = ["setBlockState(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/BlockState;II)Z"], at = [At("HEAD")])
    private fun onSetBlock(pos: BlockPos, newState: BlockState, flags: Int, maxUpdateDepth: Int, ci: CallbackInfoReturnable<Boolean>) {
        val level = this as World
        if (!level.isClient) return

        val oldState = level.getBlockState(pos)
        if (oldState.block == Blocks.GRAVEL && newState.block != Blocks.GRAVEL) {
            if (DropEventState.selfBrokenBlocks.remove(pos) == null) return

            val mc = MinecraftClient.getInstance()
            val player = mc.player ?: return
            if (Vec3d.ofCenter(pos).distanceTo(player.entityPos) > 12.0) return

            val tool = player.mainHandStack

            val silkTouch = enchantmentLevel(level, Enchantments.SILK_TOUCH, tool) ?: return

            if (silkTouch > 0) return

            val fortune = enchantmentLevel(level, Enchantments.FORTUNE, tool) ?: return

            DropEventState.pendingGravels.add(PendingGravelBreak(Vec3d.ofCenter(pos), fortune))
        }
    }
}