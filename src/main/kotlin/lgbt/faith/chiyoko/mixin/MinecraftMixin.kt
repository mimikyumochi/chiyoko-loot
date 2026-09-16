package lgbt.faith.chiyoko.mixin

import lgbt.faith.chiyoko.*
import lgbt.faith.chiyoko.config.RollType
import lgbt.faith.chiyoko.rand.Xoroshiro128PlusPlus
import lgbt.faith.chiyoko.sequences.*
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.world.entity.monster.piglin.Piglin
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.block.VaultBlock
import net.minecraft.world.level.block.entity.vault.VaultBlockEntity
import net.minecraft.world.level.block.entity.vault.VaultState
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

private const val MAX_CATCH_HISTORY = 1

@Mixin(Minecraft::class)
class MinecraftMixin {

    // tracks each piglins previous gold-holding state to detect the transition
    private val piglinGoldState = mutableMapOf<Int, Boolean>()


    private val recentCatches = ArrayDeque<ItemStack>(MAX_CATCH_HISTORY)

    private inline fun <T> nearestEligible(
        list: List<T>,
        radius: Double,
        itemPos: net.minecraft.world.phys.Vec3,
        isEligible: (T) -> Boolean,
        posOf: (T) -> net.minecraft.world.phys.Vec3,
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
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return

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

    private fun processVaults(level: ClientLevel) {
        if (VaultInteractionState.pendingVaults.isEmpty()) return
        val snapshot = VaultInteractionState.pendingVaults.toList()
        VaultInteractionState.pendingVaults.clear()

        for (pending in snapshot) {
            val waited = pending.ticksWaited + 1
            val blockState = level.getBlockState(pending.pos)
            val currentState = blockState.getValue(VaultBlock.STATE)
            val isOminous = blockState.getValue(VaultBlock.OMINOUS)

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

    private fun scanEntities(level: ClientLevel) {
        val livePiglinIds = mutableSetOf<Int>()
        val trackItems = DropEventState.pendingGravels.isNotEmpty() ||
                DropEventState.pendingFishing.isNotEmpty() ||
                DropEventState.pendingWithers.isNotEmpty() ||
                DropEventState.pendingBarters.isNotEmpty() ||
                DropEventState.pendingShulkers.isNotEmpty()

        for (entity in level.entitiesForRendering()) {
            when {
                entity is Piglin -> {
                    livePiglinIds.add(entity.id)
                    val holdingGold = entity.offhandItem.`is`(Items.GOLD_INGOT)
                    val wasHolding = piglinGoldState[entity.id] ?: false

                    if (!wasHolding && holdingGold) {
                        DropEventState.pendingBarters.add(PendingPiglinBarter(entity.id))
                    }
                    piglinGoldState[entity.id] = holdingGold
                }
                trackItems && entity is net.minecraft.world.entity.item.ItemEntity && !entity.item.isEmpty -> {
                    if (DropEventState.knownItemEntityIds.add(entity.id)) {
                        DropEventState.newItemEntities.add(entity.position() to entity.item.copy())
                    }
                }
            }
        }
        piglinGoldState.keys.retainAll(livePiglinIds)

        if (!trackItems) {
            DropEventState.knownItemEntityIds.clear()
            DropEventState.newItemEntities.clear()
        }
    }

    // route newly arrived item entities to the nearest pending event

    private fun routeNewItemEntities(level: ClientLevel) {
        if (DropEventState.newItemEntities.isEmpty()) return

        DropEventState.knownItemEntityIds.retainAll { level.getEntity(it) != null }

        val newItems = DropEventState.newItemEntities.toList()
        DropEventState.newItemEntities.clear()

        for ((itemPos, itemStack) in newItems) {
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
                isEligible = { it.collectedItems.size < 3 },
                posOf = { it.pos },
            ) ?: nearestEligible(
                DropEventState.pendingShulkers, PendingShulkerDeath.RADIUS, itemPos,
                isEligible = { it.collectedItems.isEmpty() },
                posOf = { it.pos },
            ) ?: nearestEligible(
                DropEventState.pendingBarters, PendingPiglinBarter.RADIUS, itemPos,
                isEligible = { it.collectedItems.isEmpty() && it.ticksWaited >= 115 },
                posOf = { pending ->
                    level.getEntity(pending.piglinId)?.position() ?: net.minecraft.world.phys.Vec3.ZERO
                },
            )

            target?.collect(itemStack)
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

        sendOverlay("advanced $advances times to account for desync")
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

        sendOverlay("advanced $advances times to account for desync (matched ${catchList.size} items)")
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
        sendOverlay("advanced $advances times to account for desync")

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
        sendOverlay("advanced $advancements times to account for desync")
    }

    private fun matchesPrediction(actual: List<ItemStack>, predicted: List<ItemStack>): Boolean {
        fun List<ItemStack>.toDropMap() =
            groupBy { it.item }.mapValues { (_, stacks) -> stacks.sumOf { it.count } }
        return actual.toDropMap() == predicted.toDropMap()
    }
}