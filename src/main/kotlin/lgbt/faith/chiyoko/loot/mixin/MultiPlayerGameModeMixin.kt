package lgbt.faith.chiyoko.loot.mixin

import lgbt.faith.chiyoko.loot.*
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.MultiPlayerGameMode
import net.minecraft.client.player.LocalPlayer
import net.minecraft.core.BlockPos
import net.minecraft.tags.BiomeTags
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.ai.attributes.Attributes
import net.minecraft.world.entity.monster.skeleton.WitherSkeleton
import net.minecraft.world.entity.player.Player
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.enchantment.Enchantments
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.VaultBlock
import net.minecraft.world.level.block.entity.vault.VaultState
import net.minecraft.world.phys.BlockHitResult
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(MultiPlayerGameMode::class)
class MultiPlayerGameModeMixin {


    // vault - detect opening vaults
    @Inject(method = ["destroyBlock"], at = [At("HEAD")])
    private fun onDestroyBlock(pos: BlockPos, ci: CallbackInfoReturnable<Boolean>) {
        DropEventState.selfBrokenBlocks[pos.immutable()] = 0
    }

    @Inject(method = ["useItemOn"], at = [At("HEAD")])
    private fun onUseItemOn(
        player: LocalPlayer,
        hand: InteractionHand,
        hitResult: BlockHitResult,
        ci: CallbackInfoReturnable<InteractionResult>,
    ) {

        val level = player.level()
        val pos = hitResult.blockPos
        val blockState = level.getBlockState(pos)

        if (!blockState.`is`(Blocks.VAULT)) return
        if (player.isCrouching) return

        val isOminous = blockState.getValue(VaultBlock.OMINOUS)
        val expectedKey = if (isOminous) Items.OMINOUS_TRIAL_KEY else Items.TRIAL_KEY
        if (!player.getItemInHand(hand).`is`(expectedKey)) return
        if (blockState.getValue(VaultBlock.STATE) != VaultState.ACTIVE) return

        val vault = vaultSequence(isOminous) ?: return

        val predictedItems = vault.peek(1, false)
        vault.advance(1)
        Chiyoko.configManager.updateSequence(vault)
        VaultInteractionState.pendingVaults.add(PendingVault(pos.immutable(), predictedItems, vault))
    }

    // fishing - detect reel in client side
    @Inject(method = ["useItem"], at = [At("HEAD")])
    private fun onUseItem(
        player: Player,
        hand: InteractionHand,
        ci: CallbackInfoReturnable<InteractionResult>,
    ) {
        val level = player.level()
        if (!level.isClientSide) return

        val rod = player.getItemInHand(hand)
        if (!rod.`is`(Items.FISHING_ROD)) return
        val hook = player.fishing ?: return

        // biting is synced to the client via SynchedEntityData
        if (!(hook as FishingHookAccessor).biting()) return
        val pos = hook.blockPosition()

        val luckOfTheSea = enchantmentLevel(level, Enchantments.LUCK_OF_THE_SEA, rod) ?: return

        val luck = player.getAttributeValue(Attributes.LUCK).toInt() + luckOfTheSea
        val isOpenWater = (hook as FishingHookAccessor).isOpenWater()
        val isJungle = level.getBiome(pos).`is`(BiomeTags.IS_JUNGLE)

        DropEventState.pendingFishing.add(
            PendingFishingReel(hook.position(), luck, isOpenWater, isJungle)
        )
    }

    // self drops - thrown items would otherwise be routed to a nearby pending break or kill
    @Inject(method = ["handleContainerInput"], at = [At("HEAD")])
    private fun onHandleContainerInput(
        containerId: Int,
        slotId: Int,
        buttonNum: Int,
        input: ContainerInput,
        player: Player,
        ci: CallbackInfo,
    ) {
        val menu = player.containerMenu
        if (menu.containerId != containerId) return

        when {
            // Q over a slot throws from it, but only with nothing on the cursor
            input == ContainerInput.THROW && slotId in menu.slots.indices && menu.carried.isEmpty ->
                DropEventState.recordSelfDrop(player, menu.slots[slotId].item)
            // clicking outside the window throws whatever is on the cursor
            input == ContainerInput.PICKUP && slotId == AbstractContainerMenu.SLOT_CLICKED_OUTSIDE ->
                DropEventState.recordSelfDrop(player, menu.carried)
        }
    }

    @Inject(method = ["handleCreativeModeItemDrop"], at = [At("HEAD")])
    private fun onHandleCreativeModeItemDrop(stack: ItemStack, ci: CallbackInfo) {
        val player = Minecraft.getInstance().player ?: return
        DropEventState.recordSelfDrop(player, stack)
    }

    //? if >=26.3 {
    /*@Inject(method = ["dropItem"], at = [At("HEAD")])
    private fun onDropItem(player: LocalPlayer, fullStack: Boolean, ci: CallbackInfo) {
        DropEventState.recordSelfDrop(player, player.mainHandItem)
    }
    *///?}

    // track which wither skeletons the player has hit

    @Inject(method = ["attack"], at = [At("HEAD")])
    private fun onAttack(player: Player, target: Entity, ci: CallbackInfo) {
        if (target is WitherSkeleton) {
            DropEventState.recentlyAttackedWithers.add(target.id)
        }
    }
}