package lgbt.faith.chiyoko.loot.mixin

import lgbt.faith.chiyoko.loot.*
import lgbt.faith.chiyoko.loot.config.RollType
import lgbt.faith.chiyoko.loot.rand.Xoroshiro128PlusPlus
import lgbt.faith.chiyoko.loot.sequences.*
import net.minecraft.client.MinecraftClient
import net.minecraft.client.world.ClientWorld
import net.minecraft.text.Text
import net.minecraft.entity.mob.PiglinEntity
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import net.minecraft.block.VaultBlock
import net.minecraft.block.entity.VaultBlockEntity
import net.minecraft.block.enums.VaultState
import net.minecraft.util.math.Vec3d
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

private const val MAX_CATCH_HISTORY = 1

// a mob's drops spawn before its death status reaches us, so an item can show up a tick before the
// event it belongs to. unclaimed items get this many ticks to find one before they're dropped.
private const val UNCLAIMED_ITEM_TICKS = 3

@Mixin(MinecraftClient::class)
class MinecraftMixin {

    // tracks each piglins previous gold-holding state to detect the transition
    private val piglinGoldState = mutableMapOf<Int, Boolean>()


    private val recentCatches = ArrayDeque<ItemStack>(MAX_CATCH_HISTORY)

    private val unclaimedItems = mutableListOf<UnclaimedItem>()

    private inline fun <T> nearestEligible(
        list: List<T>,
        radius: Double,
        itemPos: net.minecraft.util.math.Vec3d,
        isEligible: (T) -> Boolean,
        posOf: (T) -> net.minecraft.util.math.Vec3d,
    ): T? {
        var best: T? = null
        var bestDist = radius
        for (candidate in list) {
            if (!isEligible(candidate)) continue
            val d = posOf(candidate).distanceTo(itemPos)
            // strictly closer, so an exact tie keeps the oldest candidate - two breaks at the
            // same block have identical distances and must be filled in the order they rolled.
            if (d < bestDist) {
                best = candidate
                bestDist = d
            }
        }
        return best
    }

    private inline fun <T : PendingDrop> processPending(
        list: MutableList<T>,
        collectWindow: Int,
        maxTicks: Int,
        onDone: (T) -> Unit,
    ) {
        val iter = list.iterator()
        while (iter.hasNext()) {
            val p = iter.next()
            if (p.collectingSince >= 0) p.collectingSince++
            p.ticksWaited++

            val ready = p.collectingSince >= collectWindow
            val expired = p.ticksWaited >= maxTicks
            if (!ready && !expired) continue

            onDone(p)
            iter.remove()
        }
    }

    @Inject(method = ["tick"], at = [At("HEAD")])
    private fun onTick(ci: CallbackInfo) {
        val mc = MinecraftClient.getInstance()
        val level = mc.world ?: return

//        val player = mc.player
//        if (player != null) {
//            EnchantFunctions.logRegistryOrderForHeldItem()
//        }

        processVaults(level)
        scanEntities(level)
        routeNewItemEntities(level)
        processGravels()
        processWithers()
        processShulkers()
        processFishing()
        processBarters()
        ageSelfBrokenBlocks()
    }

    // vaults
    private fun ageSelfBrokenBlocks() {
        val iter = DropEventState.selfBrokenBlocks.entries.iterator()
        while (iter.hasNext()) {
            val entry = iter.next()
            val newAge = entry.value + 1
            if (newAge > 2) iter.remove() else entry.setValue(newAge)
        }
    }

    private fun processVaults(level: ClientWorld) {
        if (VaultInteractionState.pendingVaults.isEmpty()) return
        val snapshot = VaultInteractionState.pendingVaults.toList()
        VaultInteractionState.pendingVaults.clear()

        for (pending in snapshot) {
            val waited = pending.ticksWaited + 1
            val blockState = level.getBlockState(pending.pos)
            val currentState = blockState.get(VaultBlock.VAULT_STATE)
            val isOminous = blockState.get(VaultBlock.OMINOUS)

            if (currentState == VaultState.EJECTING) {
                val blockEntity = level.getBlockEntity(pending.pos) as? VaultBlockEntity
                val displayItem = blockEntity?.sharedData?.displayItem ?: ItemStack.EMPTY

                if (!pending.vault.lootTable.any { it.first.item == displayItem.item }) return

                if (!displayItem.isEmpty &&
                    (pending.predictedItems.lastOrNull()?.item != displayItem.item ||
                            pending.predictedItems.lastOrNull()?.count != displayItem.count) &&
                    isMatchingSeed()
                ) {
                    handleVaultDesync(displayItem, isOminous)
                }
            } else if (waited < 200) {
                VaultInteractionState.pendingVaults.add(pending.copy(ticksWaited = waited))
            }
        }
    }

    // piglin - detect when picks up gold ingot

    // piglin gold pickup + new item entity discovery, single pass

    private fun scanEntities(level: ClientWorld) {
        val livePiglinIds = mutableSetOf<Int>()
        val liveItemIds = mutableSetOf<Int>()
        val freshItems = mutableListOf<Pair<Vec3d, ItemStack>>()

        for (entity in level.entities) {
            when {
                entity is PiglinEntity -> {
                    livePiglinIds.add(entity.id)
                    val holdingGold = entity.offHandStack.isOf(Items.GOLD_INGOT)
                    val wasHolding = piglinGoldState[entity.id] ?: false

                    if (!wasHolding && holdingGold) {
                        DropEventState.pendingBarters.add(PendingPiglinBarter(entity.id))
                    }
                    piglinGoldState[entity.id] = holdingGold
                }
                // item ids are recorded even when nothing is pending, otherwise items already lying
                // on the ground look "new" on the next break and get routed to it before the real drop.
                entity is net.minecraft.entity.ItemEntity && !entity.stack.isEmpty -> {
                    liveItemIds.add(entity.id)
                    if (DropEventState.knownItemEntityIds.add(entity.id)) {
                        freshItems.add(entity.entityPos to entity.stack.copy())
                    }
                }
            }
        }
        piglinGoldState.keys.retainAll(livePiglinIds)
        DropEventState.knownItemEntityIds.retainAll(liveItemIds)

        claimSelfDrops(freshItems)
        DropEventState.newItemEntities.addAll(freshItems)
    }

    // each throw by the local player produces exactly one item entity. give every pending throw
    // the closest fresh item of the same type to where it was thrown from, so a gravel drop landing
    // in the same tick as a thrown gravel stack isn't the one that gets discarded.
    private fun claimSelfDrops(freshItems: MutableList<Pair<Vec3d, ItemStack>>) {
        val drops = DropEventState.pendingSelfDrops
        val iter = drops.iterator()
        while (iter.hasNext()) {
            val drop = iter.next()
            val claimed = freshItems
                .filter { (pos, stack) -> stack.item == drop.item && pos.distanceTo(drop.origin) <= PendingSelfDrop.RADIUS }
                .minByOrNull { (pos, _) -> pos.distanceTo(drop.origin) }

            if (claimed != null) {
                freshItems.remove(claimed)
                iter.remove()
            } else if (++drop.ticksWaited >= PendingSelfDrop.MAX_TICKS) {
                iter.remove()
            }
        }
    }

    // route newly arrived item entities to the nearest pending event

    private fun routeNewItemEntities(level: ClientWorld) {
        if (DropEventState.newItemEntities.isEmpty() && unclaimedItems.isEmpty()) return

        val newItems = unclaimedItems + DropEventState.newItemEntities.map { (pos, stack) -> UnclaimedItem(pos, stack) }
        DropEventState.newItemEntities.clear()
        unclaimedItems.clear()

        for (item in newItems) {
            val itemPos = item.pos
            val itemStack = item.stack
            // gravel and fishing each drop exactly 1 item, so fill those first.
            val target: PendingDrop? = nearestEligible(
                DropEventState.pendingGravels, PendingGravelBreak.RADIUS, itemPos,
                isEligible = { it.collectedItems.isEmpty() },
                posOf = { it.pos },
            ) ?: nearestEligible(
                DropEventState.pendingFishing, PendingFishingReel.RADIUS, itemPos,
                isEligible = { it.collectedItems.isEmpty() },
                posOf = { it.pos },
            ) ?: nearestEligible(
                DropEventState.pendingWithers, PendingWitherDeath.RADIUS, itemPos,
                // the held stone sword drops from the entity's own random, not the loot table - routing it here
                // made resolveWither bail out without advancing
                isEligible = { it.collectedItems.size < 3 && itemStack.item in WITHER_LOOT },
                posOf = { it.pos },
            ) ?: nearestEligible(
                DropEventState.pendingShulkers, PendingShulkerDeath.RADIUS, itemPos,
                isEligible = { it.collectedItems.isEmpty() },
                posOf = { it.pos },
            ) ?: nearestEligible(
                DropEventState.pendingBarters, PendingPiglinBarter.RADIUS, itemPos,
                isEligible = { it.collectedItems.isEmpty() && it.ticksWaited >= 115 },
                posOf = { pending ->
                    level.getEntityById(pending.piglinId)?.entityPos ?: net.minecraft.util.math.Vec3d.ZERO
                },
            )

            if (target != null) target.collect(itemStack)
            else if (++item.age < UNCLAIMED_ITEM_TICKS) unclaimedItems.add(item)
        }
    }

    // gravel

    // gravel rolls are consumed in break order, so only the head of the queue may resolve.
    // letting a later break resolve first compares its drop against an earlier break's roll.
    private fun processGravels() {
        val pending = DropEventState.pendingGravels

        for (p in pending) {
            if (p.collectingSince >= 0) p.collectingSince++
            p.ticksWaited++
        }

        while (pending.isNotEmpty()) {
            val head = pending.first()
            val ready = head.collectingSince >= PendingGravelBreak.COLLECT_WINDOW
            val expired = head.ticksWaited >= PendingGravelBreak.MAX_TICKS
            if (!ready && !expired) break

            pending.removeAt(0)
            if (head.collectedItems.isNotEmpty()) resolveGravel(head) else advanceMissedGravel()
        }
    }

    // the server rolled for this break even though its item never reached us, so the sequence
    // still has to move on - dropping it silently leaves every later break one roll behind.
    private fun advanceMissedGravel() {
        val gravel = trackedSequence<Gravel>("minecraft:blocks/gravel") ?: return
        gravel.advance(1)
        Chiyoko.configManager.updateSequence(gravel)
    }

    private fun resolveGravel(p: PendingGravelBreak) {
        val gravel = trackedSequence<Gravel>("minecraft:blocks/gravel") ?: return

        val actual = p.collectedItems.first()
        // avoid potential misroutes which will cause the game to hang as it infinitely writes to the config file for desyncs.
        if (actual.item != Items.GRAVEL && actual.item != Items.FLINT) {
            return
        }

        var predicted = gravel.roll(1, p.fortune)
        gravel.advance(1)
        var desynced = actual.item != predicted.firstOrNull()?.item
        if (!desynced || !isMatchingSeed()) {
            Chiyoko.configManager.updateSequence(gravel)
            return
        }

        var advances = 0L
        val maxAdvances = 1000
        while (desynced && advances < maxAdvances) {
            advances++
            predicted = gravel.roll(1, p.fortune)
            gravel.advance(1)
            desynced = actual.item != predicted.firstOrNull()?.item
        }
        Chiyoko.configManager.updateSequence(gravel, advances)

        sendOverlay(Text.translatable("chiyoko.desync.advanced", advances))
    }

    // shulker
    private fun processShulkers() {
        processPending(DropEventState.pendingShulkers, PendingShulkerDeath.COLLECT_WINDOW, PendingShulkerDeath.MAX_TICKS) { p ->
            val genuinelyEmpty = p.collectingSince == -1
            if (p.collectedItems.isNotEmpty() || genuinelyEmpty) resolveShulkers(p)
        }
    }
    private fun resolveShulkers(p: PendingShulkerDeath) {
        val shulkerSeq = trackedSequence<Shulker>("minecraft:entities/shulker") ?: return

        val actualDrops = p.collectedItems.filter { it.item != Items.AIR }
        if (actualDrops.any { drop -> drop.item !in shulkerSeq.lootTable }) return

        val predictedDrops = shulkerSeq.roll(RollType.NextDrop, p.looting)
        shulkerSeq.advance(1, p.looting)
        Chiyoko.configManager.updateSequence(shulkerSeq)


        if (matchesPrediction(actualDrops, predictedDrops) || !isMatchingSeed()) return

        val result = findDesyncFix(
            startXoro = shulkerSeq.getRngCopy(),
            maxDepth = 12, // down from 50
            actualDrops = actualDrops,
            branchOptions = listOf(false, true), // hasLooting
            rollBranch = { xoro, hasLooting -> shulkerSeq.nextDrops(xoro, if (hasLooting) p.looting else 0) },
        )

        applyDesyncFix(result, shulkerSeq)
    }

    // wither skeleton

    private fun processWithers() {
        processPending(DropEventState.pendingWithers, PendingWitherDeath.COLLECT_WINDOW, PendingWitherDeath.MAX_TICKS) { p ->
            val genuinelyEmpty = p.collectingSince == -1
            if (p.collectedItems.isNotEmpty() || genuinelyEmpty) resolveWither(p)
        }
    }

    private fun resolveWither(p: PendingWitherDeath) {
        val witherSeq = trackedSequence<WitherSkeleton>("minecraft:entities/wither_skeleton") ?: return

        val actualDrops = p.collectedItems.filter { it.item != Items.AIR }

        if (actualDrops.any { drop -> drop.item !in witherSeq.lootTable }) return

        val predictedDrops = witherSeq.roll(
            RollType.NextDrop,
            p.playerKilled, p.looting
        )
        witherSeq.advance(1, p.playerKilled, p.looting)
        Chiyoko.configManager.updateSequence(witherSeq)

        if (matchesPrediction(actualDrops, predictedDrops) || !isMatchingSeed()) return

        val result = findDesyncFix(
            startXoro = witherSeq.getRngCopy(),
            maxDepth = 12,
            actualDrops = actualDrops,
            branchOptions = listOf(false to false, true to false, true to true), // (playerKilled, hasLooting)
            rollBranch = { xoro, (playerKilled, hasLooting) ->
                witherSeq.nextDrops(xoro, playerKilled, if (hasLooting) p.looting else 0)
            },
        )

        applyDesyncFix(result, witherSeq)
    }

    // fishing
    private fun processFishing() {
        processPending(DropEventState.pendingFishing, PendingFishingReel.COLLECT_WINDOW, PendingFishingReel.MAX_TICKS) { p ->
            if (p.collectedItems.isNotEmpty()) {
                for (item in p.collectedItems) {
                    if (recentCatches.size >= MAX_CATCH_HISTORY) recentCatches.removeFirst()
                    recentCatches.addLast(item)
                }
                resolveFishing(p)
            }
        }
    }
    private fun resolveFishing(p: PendingFishingReel) {
        val fishing = trackedSequence<Fishing>("minecraft:gameplay/fishing") ?: return

        val actual = p.collectedItems.first()

        // avoid potential misroutes which will cause the game to hang as it infinitely writes to the config file for desyncs.
        val isFishDrop = (Fishing.fishTable() + Fishing.junkTable(true) + Fishing.treasureTable())
            .any { it.item.item == actual.item }

        if (!isFishDrop) return

        val predicted = fishing.peek(1, p.luck, p.isOpenWater, p.isJungle)
        fishing.advance(1, p.luck, p.isOpenWater, p.isJungle)
        Chiyoko.configManager.updateSequence(fishing)

        var desynced = actual.item != predicted.first().item


        if (!desynced || !isMatchingSeed()) return

        val catchList = recentCatches.toList() // snapshot the history once

        var advances = 0L
        var rngAdvances = 0L
        val maxAdvances = 1000
        while (desynced && advances < maxAdvances) {
            advances++

            val matched = tryMatchCatchSequence(fishing, catchList, p)
            if (matched != null) {
                rngAdvances += matched
                desynced = false
            } else {
                fishing.advance(1, p.luck, p.isOpenWater, p.isJungle)
                rngAdvances++
            }
        }

        if (rngAdvances > 0) {
            Chiyoko.configManager.updateSequence(fishing, rngAdvances)
        }

        sendOverlay(Text.translatable("chiyoko.desync.advanced_matched", advances, catchList.size))
    }

    private fun tryMatchCatchSequence(fishing: Fishing, catchList: List<ItemStack>, p: PendingFishingReel): Int? {
        val snapshot = fishing.getRngCopy()
        for (expected in catchList) {
            val pred = fishing.peek(1, p.luck, p.isOpenWater, p.isJungle)

            if (expected.item != pred.first().item) {
                fishing.loadState(snapshot.seedLo, snapshot.seedHi)
                return null
            }

            fishing.advance(1, p.luck, p.isOpenWater, p.isJungle)
        }
        return catchList.size
    }

    // piglin bartering

    private fun processBarters() {
        processPending(DropEventState.pendingBarters, PendingPiglinBarter.COLLECT_WINDOW, PendingPiglinBarter.MAX_TICKS) { p ->
            if (p.collectedItems.isNotEmpty()) resolveBarter(p)
        }
    }

    private fun resolveBarter(p: PendingPiglinBarter) {
        val barter = trackedSequence<PiglinBartering>("minecraft:gameplay/piglin_bartering") ?: return

        val actual = p.collectedItems.firstOrNull() ?: return

        if (!barter.lootTable.any { it.item.item == actual.item }) return

        var predicted = barter.roll(1)
        barter.advance(1)
        Chiyoko.configManager.updateSequence(barter)

        var desynced = actual.item != predicted.firstOrNull()?.item
        if (!desynced || !isMatchingSeed()) return

        var advances = 0L
        val maxAdvances = 1000
        while (desynced && advances < maxAdvances) {
            advances++
            predicted = barter.roll(1)
            barter.advance(1)
            desynced = actual.item != predicted.firstOrNull()?.item
        }
        Chiyoko.configManager.updateSequence(barter, advances)
        sendOverlay(Text.translatable("chiyoko.desync.advanced", advances))

    }


    // bfs for shulkers and wither skeletons
    private fun <T> findDesyncFix(
        startXoro: Xoroshiro128PlusPlus,
        maxDepth: Int,
        actualDrops: List<ItemStack>,
        branchOptions: List<T>,
        rollBranch: (Xoroshiro128PlusPlus, T) -> List<ItemStack>,
    ): Pair<Xoroshiro128PlusPlus, Int>? {
        for (depthLimit in 1..maxDepth) {
            val queue = ArrayDeque<Triple<Xoroshiro128PlusPlus, Int, Int>>()
            val visited = HashSet<Xoroshiro128PlusPlus.State>()
            queue.add(Triple(startXoro.copy(), 0, 0))
            visited.add(startXoro.toState())

            while (queue.isNotEmpty()) {
                val (current, depth, advancements) = queue.removeFirst()
                if (depth >= depthLimit) continue
                for (option in branchOptions) {
                    val next = current.copy()
                    val predicted = rollBranch(next, option)
                    val state = next.toState()
                    if (!visited.add(state)) continue
                    if (matchesPrediction(actualDrops, predicted)) return next to (advancements + 1)
                    queue.add(Triple(next, depth + 1, advancements + 1))
                }
            }
        }
        return null
    }

    private fun applyDesyncFix(result: Pair<Xoroshiro128PlusPlus, Int>?, sequence: Sequence) {
        if (result == null) return
        val (found, advancements) = result
        // the bfs searches on copies, so the live sequence has to be moved onto the state it
        // found - otherwise only the config is corrected and the in-memory rng stays behind.
        sequence.loadState(found.seedLo, found.seedHi)
        Chiyoko.configManager.updateSequence(sequence, advancements.toLong())
        sendOverlay(Text.translatable("chiyoko.desync.advanced", advancements))
    }

    private fun matchesPrediction(actual: List<ItemStack>, predicted: List<ItemStack>): Boolean {
        fun List<ItemStack>.toDropMap() =
            groupBy { it.item }.mapValues { (_, stacks) -> stacks.sumOf { it.count } }
        return actual.toDropMap() == predicted.toDropMap()
    }
}