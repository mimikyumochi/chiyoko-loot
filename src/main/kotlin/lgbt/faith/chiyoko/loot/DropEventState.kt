package lgbt.faith.chiyoko.loot

import lgbt.faith.chiyoko.loot.sequences.Vault
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
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

class PendingGravelBreak(val pos: Vec3, val fortune: Int) : PendingDrop() {
    companion object { const val MAX_TICKS = 12; const val COLLECT_WINDOW = 5; const val RADIUS = 5.0 }
}

class PendingWitherDeath(val pos: Vec3, val looting: Int, val playerKilled: Boolean) : PendingDrop() {
    companion object { const val MAX_TICKS = 12; const val COLLECT_WINDOW = 10; const val RADIUS = 5.0 }
}

class PendingShulkerDeath(val pos: Vec3, val looting: Int) : PendingDrop() {
    companion object { const val MAX_TICKS = 12; const val COLLECT_WINDOW = 10; const val RADIUS = 5.0 }
}

class PendingFishingReel(val pos: Vec3, val luck: Int, val isOpenWater: Boolean, val isJungle: Boolean) : PendingDrop() {
    companion object { const val MAX_TICKS = 60; const val COLLECT_WINDOW = 5; const val RADIUS = 8.0 }
}

// an item the local player threw, so its entity isn't mistaken for a loot drop.
// the server spawns it at eye height - 0.3, which is what origin records.
class PendingSelfDrop(val origin: Vec3, val item: Item) {
    var ticksWaited = 0
    companion object { const val MAX_TICKS = 40; const val RADIUS = 2.0 }
}

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
    val newItemEntities = mutableListOf<Pair<Vec3, ItemStack>>()
    val knownItemEntityIds = mutableSetOf<Int>()

    // so LivingEntityMixin can return if not player killed
    val recentlyAttackedWithers = mutableSetOf<Int>()

    // so LevelMixin can return if not broken by local player
    val selfBrokenBlocks: MutableMap<BlockPos, Int> = mutableMapOf()

    fun recordSelfDrop(player: Player, stack: ItemStack) {
        if (stack.isEmpty) return
        pendingSelfDrops.add(PendingSelfDrop(Vec3(player.x, player.eyeY - 0.3, player.z), stack.item))
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