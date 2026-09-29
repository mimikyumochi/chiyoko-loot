package lgbt.faith.chiyoko.loot.gui

import lgbt.faith.chiyoko.loot.Chiyoko
import lgbt.faith.chiyoko.loot.config.OverlayRotation
import lgbt.faith.chiyoko.loot.config.RollType
import lgbt.faith.chiyoko.loot.keys
import lgbt.faith.chiyoko.loot.sequences.*
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gl.RenderPipelines
import net.minecraft.registry.entry.RegistryEntry
import net.minecraft.registry.RegistryKeys
import net.minecraft.util.Identifier
import net.minecraft.registry.tag.BiomeTags
import net.minecraft.util.Hand
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.item.ItemStack
import net.minecraft.enchantment.Enchantment
import net.minecraft.enchantment.EnchantmentHelper
import net.minecraft.enchantment.Enchantments
import net.minecraft.world.World

class ChiyokoRenderer {
    data class SubList(val xOffset: Int, val yOffset: Int, val items: List<ItemStack>)

    private data class RollCacheKey(
        val advances: Int,
        val rngLo: Long,
        val rngHi: Long,
        val luck: Int,
        val isOpenWater: Boolean,
        val isJungle: Boolean,
        val fortuneLevel: Int,
        val lootingLevel: Int,
        val split: Boolean,
        val rollType: Any?,
    )

    private val rollCache = HashMap<String, Pair<RollCacheKey, List<SubList>>>()

    private var cachedRegistryLevel: World? = null
    private var cachedLootingHolder: RegistryEntry<Enchantment>? = null
    private var cachedFortuneHolder: RegistryEntry<Enchantment>? = null

    val mc = MinecraftClient.getInstance()

    val SLOT_SPRITE = Identifier.of("minecraft:container/slot")
    val font = mc.textRenderer

    private fun gridToPixel(cell: Int) = (cell * gridSize) + border

    private val gridSize = 20
    private val border = 1

    private fun enchantHolders(level: World): Pair<RegistryEntry<Enchantment>, RegistryEntry<Enchantment>> {
        if (cachedRegistryLevel === level && cachedLootingHolder != null && cachedFortuneHolder != null) {
            return cachedLootingHolder!! to cachedFortuneHolder!!
        }
        val enchantLookup = level.registryManager.getOrThrow(RegistryKeys.ENCHANTMENT)
        val looting = enchantLookup.getOrThrow(Enchantments.LOOTING)
        val fortune = enchantLookup.getOrThrow(Enchantments.FORTUNE)
        cachedRegistryLevel = level
        cachedLootingHolder = looting
        cachedFortuneHolder = fortune
        return looting to fortune
    }

    private fun enchantLevel(holder: RegistryEntry<Enchantment>, player: PlayerEntity): Int {
        val mainhand = player.getStackInHand(Hand.MAIN_HAND)
        val offhand = player.getStackInHand(Hand.OFF_HAND)
        return maxOf(
            EnchantmentHelper.getLevel(holder, mainhand),
            EnchantmentHelper.getLevel(holder, offhand),
        )
    }

    fun render(graphics: DrawContext) {
        if (!Chiyoko.loaded) return


        if (
            mc.options.hudHidden
            ) return
        var hoveredItem: ItemStack? = null

        val player = mc.player ?: return
        val level = mc.world ?: return

        val (lootingHolder, fortuneHolder) = enchantHolders(level)

        val mouseX = mc.mouse.x * mc.window.scaledWidth / mc.window.width
        val mouseY = mc.mouse.y * mc.window.scaledHeight / mc.window.height

        val mx = mouseX.toInt()
        val my = mouseY.toInt()

        val rod = mc.player?.fishHook
        val rodPos = rod?.blockPos
        val playerPos = mc.player?.blockPos
        val luck = (mc.player?.luck ?: 0.0f).toInt()

        val isOpenWater = rod?.isInOpenWater ?: true
        val isJungle =
            if (rodPos != null) level.getBiome(rodPos).isIn(BiomeTags.IS_JUNGLE)
            else if (playerPos != null) level.getBiome(playerPos).isIn(BiomeTags.IS_JUNGLE)
            else false

        val lootingLevel = enchantLevel(lootingHolder, player)
        val fortuneLevel = enchantLevel(fortuneHolder, player)

        val configManager = Chiyoko.configManager

        rollCache.keys.retainAll(keys.toSet())

        keys.forEachIndexed { index, key ->
            val sequence = Chiyoko.sequences.map[key] ?: return@forEachIndexed
            val overlay = configManager.config.getOverlay(key)

            if (overlay.tracked != true) return@forEachIndexed
            if (!overlay.visible) return@forEachIndexed

            val pos = configManager.config.getSlotPosition(key, index)

            val x = gridToPixel(pos.gridX)
            val y = gridToPixel(pos.gridY)

            val vector = when {
                overlay.rotation == OverlayRotation.HORIZONTAL && overlay.reversed  -> intArrayOf(-1, 0)
                overlay.rotation == OverlayRotation.HORIZONTAL                      -> intArrayOf(1, 0)
                overlay.reversed                                                    -> intArrayOf(0, -1)
                else                                                                -> intArrayOf(0, 1)
            }
            val perpendicular = when {
                overlay.rotation == OverlayRotation.HORIZONTAL -> intArrayOf(0, 1)
                else -> intArrayOf(1, 0)
            }

            val rng = sequence.getRngCopy()
            val cacheKey = RollCacheKey(
                advances = overlay.advances,
                rngLo = rng.seedLo,
                rngHi = rng.seedHi,
                luck = luck,
                isOpenWater = isOpenWater,
                isJungle = isJungle,
                fortuneLevel = fortuneLevel,
                lootingLevel = lootingLevel,
                split = overlay.split,
                rollType = if (sequence is WitherSkeleton || sequence is Shulker) overlay.rollType else null,
            )

            val cached = rollCache[key]
            val subLists: List<SubList> = if (cached != null && cached.first == cacheKey) {
                cached.second
            } else {
                val rolled: List<SubList> = when (sequence) {
                    is Vault -> if (overlay.split) {
                        sequence.peekEach(overlay.advances).mapIndexed { i, items ->
                            SubList((gridSize - 2) * i * perpendicular[0], (gridSize - 2) * i * perpendicular[1], items)
                        }
                    } else {
                        listOf(SubList(0, 0, sequence.peek(overlay.advances)))
                    }
                    is PiglinBartering -> listOf(SubList(0, 0, sequence.roll(overlay.advances)))
                    is WitherSkeleton -> {
                        val drops = sequence.roll(overlay.rollType ?: RollType.KillsUntilItem, true, lootingLevel)
                        listOf(SubList(0, 0, drops.ifEmpty { listOf(ItemStack.EMPTY) }))
                    }
                    is Shulker -> {
                        val drops = sequence.roll(overlay.rollType ?: RollType.KillsUntilItem, lootingLevel)
                        listOf(SubList(0, 0, drops.ifEmpty { listOf(ItemStack.EMPTY) }))
                    }
                    is Fishing -> listOf(SubList(0, 0, sequence.peek(overlay.advances, luck, isOpenWater, isJungle)))
                    is Gravel  -> listOf(SubList(0, 0, sequence.roll(overlay.advances, fortuneLevel)))

                    else -> emptyList()
                }
                rollCache[key] = cacheKey to rolled
                rolled
            }

            for (subList in subLists) {
                for ((itemIndex, item) in subList.items.withIndex()) {
                    val step = (gridSize - 2) * itemIndex
                    val itemX = x + subList.xOffset + step * vector[0]
                    val itemY = y + subList.yOffset + step * vector[1]

                    graphics.drawGuiTexture(RenderPipelines.GUI_TEXTURED, SLOT_SPRITE, itemX, itemY, gridSize - 2, gridSize - 2)
                    graphics.drawItem(item, itemX + 1, itemY + 1)
                    graphics.drawStackOverlay(font, item, itemX + 1, itemY + 1)
                    val hovered = mx in itemX until (itemX + gridSize) && my in itemY until (itemY + gridSize)
                    if (hovered) {
                        hoveredItem = item
                    }
                }
            }
        }
        if (hoveredItem != null) {
            val tickDelta = mc.renderTickCounter.dynamicDeltaTicks
            graphics.drawItemTooltip(mc.textRenderer, hoveredItem, mx, my)
            graphics.drawDeferredElements()
        }
    }
}