package lgbt.faith.chiyoko.loot

import lgbt.faith.chiyoko.loot.sequences.Vault
import net.minecraft.util.math.BlockPos
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.Item
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.util.math.Vec3d
import java.util.concurrent.ConcurrentLinkedQueue


abstract class PendingDrop {
    var ticksWaited = 0
    val collectedItems = mutableListOf<ItemStack>()
    var collectingSince = -1

    fun collect(itemStack: ItemStack) {
        collectedItems.add(itemStack)
        if (collectingSince == -1) collectingSince = 0
    }
}

class PendingGravelBreak(val pos: Vec3d, val fortune: Int) : PendingDrop() {
    companion object { const val MAX_TICKS = 12; const val COLLECT_WINDOW = 5; const val RADIUS = 5.0 }
}

class PendingWitherDeath(val pos: Vec3d, val looting: Int, val playerKilled: Boolean) : PendingDrop() {
    companion object { const val MAX_TICKS = 12; const val COLLECT_WINDOW = 10; const val RADIUS = 5.0 }
}

class PendingShulkerDeath(val pos: Vec3d, val looting: Int) : PendingDrop() {
    companion object { const val MAX_TICKS = 12; const val COLLECT_WINDOW = 10; const val RADIUS = 5.0 }
}

class PendingFishingReel(val pos: Vec3d, val luck: Int, val isOpenWater: Boolean, val isJungle: Boolean) : PendingDrop() {
    companion object { const val MAX_TICKS = 60; const val COLLECT_WINDOW = 5; const val RADIUS = 8.0 }
}

// an item the local player threw, so its entity isn't mistaken for a loot drop.
// the server spawns it at eye height - 0.3, which is what origin records.
class PendingSelfDrop(val origin: Vec3d, val item: Item) {
    var ticksWaited = 0
    companion object { const val MAX_TICKS = 40; const val RADIUS = 2.0 }
}

// a fresh item entity that hasn't been routed to a pending event yet
class UnclaimedItem(val pos: Vec3d, val stack: ItemStack, var age: Int = 0)

// only these come from the wither skeleton loot table, anything else near the death isn't a roll
val WITHER_LOOT = setOf(Items.COAL, Items.BONE, Items.WITHER_SKELETON_SKULL)

class PendingPiglinBarter(val piglinId: Int) : PendingDrop() {
    companion object { const val MAX_TICKS = 500; const val COLLECT_WINDOW = 10; const val RADIUS = 8.0 }
}

object DropEventState {
    val pendingGravels   = mutableListOf<PendingGravelBreak>()
    val pendingWithers   = mutableListOf<PendingWitherDeath>()
    val pendingShulkers  = mutableListOf<PendingShulkerDeath>()
    val pendingFishing   = mutableListOf<PendingFishingReel>()
    val pendingBarters   = mutableListOf<PendingPiglinBarter>()
    val pendingSelfDrops = mutableListOf<PendingSelfDrop>()

    // filled and drained by MinecraftMixin every tick
    val newItemEntities = mutableListOf<Pair<Vec3d, ItemStack>>()
    val knownItemEntityIds = mutableSetOf<Int>()

    // so LivingEntityMixin can return if not player killed
    val recentlyAttackedWithers = mutableSetOf<Int>()

    // so LevelMixin can return if not broken by local player
    val selfBrokenBlocks: MutableMap<BlockPos, Int> = mutableMapOf()

    fun recordSelfDrop(player: PlayerEntity, stack: ItemStack) {
        if (stack.isEmpty) return
        pendingSelfDrops.add(PendingSelfDrop(Vec3d(player.x, player.eyeY - 0.3, player.z), stack.item))
    }
}

// unchanged vault stuff - if it isnt broke, dont fix it
data class PendingVault(
    val pos: BlockPos,
    val predictedItems: List<ItemStack>,
    val vault: Vault,
    val ticksWaited: Int = 0,
)
object VaultInteractionState {
    val pendingVaults: ConcurrentLinkedQueue<PendingVault> = ConcurrentLinkedQueue()
}