package lgbt.faith.chiyoko.loot.mixin

import lgbt.faith.chiyoko.loot.*
import net.minecraft.client.MinecraftClient
import net.minecraft.client.network.ClientPlayerInteractionManager
import net.minecraft.client.network.ClientPlayerEntity
import net.minecraft.util.math.BlockPos
import net.minecraft.registry.tag.BiomeTags
import net.minecraft.util.Hand
import net.minecraft.util.ActionResult
import net.minecraft.entity.Entity
import net.minecraft.entity.attribute.EntityAttributes
import net.minecraft.entity.mob.WitherSkeletonEntity
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.screen.ScreenHandler
import net.minecraft.screen.slot.SlotActionType
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.enchantment.Enchantments
import net.minecraft.block.Blocks
import net.minecraft.block.VaultBlock
import net.minecraft.block.enums.VaultState
import net.minecraft.util.hit.BlockHitResult
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

@Mixin(ClientPlayerInteractionManager::class)
class MultiPlayerGameModeMixin {


    // vault - detect opening vaults
    @Inject(method = ["breakBlock"], at = [At("HEAD")])
    private fun onDestroyBlock(pos: BlockPos, ci: CallbackInfoReturnable<Boolean>) {
        DropEventState.selfBrokenBlocks[pos.toImmutable()] = 0
    }

    @Inject(method = ["interactBlock"], at = [At("HEAD")])
    private fun onUseItemOn(
        player: ClientPlayerEntity,
        hand: Hand,
        hitResult: BlockHitResult,
        ci: CallbackInfoReturnable<ActionResult>,
    ) {

        val level = player.entityWorld
        val pos = hitResult.blockPos
        val blockState = level.getBlockState(pos)

        if (!blockState.isOf(Blocks.VAULT)) return
        if (player.isInSneakingPose) return

        val isOminous = blockState.get(VaultBlock.OMINOUS)
        val expectedKey = if (isOminous) Items.OMINOUS_TRIAL_KEY else Items.TRIAL_KEY
        if (!player.getStackInHand(hand).isOf(expectedKey)) return
        if (blockState.get(VaultBlock.VAULT_STATE) != VaultState.ACTIVE) return

        val vault = vaultSequence(isOminous) ?: return

        val predictedItems = vault.peek(1, false)
        vault.advance(1)
        Chiyoko.configManager.updateSequence(vault)
        VaultInteractionState.pendingVaults.add(PendingVault(pos.toImmutable(), predictedItems, vault))
    }

    // fishing - detect reel in client side
    @Inject(method = ["interactItem"], at = [At("HEAD")])
    private fun onUseItem(
        player: PlayerEntity,
        hand: Hand,
        ci: CallbackInfoReturnable<ActionResult>,
    ) {
        val level = player.entityWorld
        if (!level.isClient) return

        val rod = player.getStackInHand(hand)
        if (!rod.isOf(Items.FISHING_ROD)) return
        val hook = player.fishHook ?: return

        // biting is synced to the client via SynchedEntityData
        if (!(hook as FishingHookAccessor).biting()) return
        val pos = hook.blockPos

        val luckOfTheSea = enchantmentLevel(level, Enchantments.LUCK_OF_THE_SEA, rod) ?: return

        val luck = player.getAttributeValue(EntityAttributes.LUCK).toInt() + luckOfTheSea
        val isOpenWater = (hook as FishingHookAccessor).isOpenWater()
        val isJungle = level.getBiome(pos).isIn(BiomeTags.IS_JUNGLE)

        DropEventState.pendingFishing.add(
            PendingFishingReel(hook.entityPos, luck, isOpenWater, isJungle)
        )
    }

    // self drops - thrown items would otherwise be routed to a nearby pending break or kill
    @Inject(method = ["clickSlot"], at = [At("HEAD")])
    private fun onHandleContainerInput(
        containerId: Int,
        slotId: Int,
        buttonNum: Int,
        input: SlotActionType,
        player: PlayerEntity,
        ci: CallbackInfo,
    ) {
        val menu = player.currentScreenHandler
        if (menu.syncId != containerId) return

        when {
            // Q over a slot throws from it, but only with nothing on the cursor
            input == SlotActionType.THROW && slotId in menu.slots.indices && menu.cursorStack.isEmpty ->
                DropEventState.recordSelfDrop(player, menu.slots[slotId].stack)
            // clicking outside the window throws whatever is on the cursor
            input == SlotActionType.PICKUP && slotId == ScreenHandler.EMPTY_SPACE_SLOT_INDEX ->
                DropEventState.recordSelfDrop(player, menu.cursorStack)
        }
    }

    @Inject(method = ["dropCreativeStack"], at = [At("HEAD")])
    private fun onHandleCreativeModeItemDrop(stack: ItemStack, ci: CallbackInfo) {
        val player = MinecraftClient.getInstance().player ?: return
        DropEventState.recordSelfDrop(player, stack)
    }

    // track which wither skeletons the player has hit

    @Inject(method = ["attackEntity"], at = [At("HEAD")])
    private fun onAttack(player: PlayerEntity, target: Entity, ci: CallbackInfo) {
        if (target is WitherSkeletonEntity) {
            DropEventState.recentlyAttackedWithers.add(target.id)
        }
    }
}