package lgbt.faith.chiyoko.loot.sequences

import lgbt.faith.chiyoko.loot.config.RollType
import lgbt.faith.chiyoko.loot.rand.Xoroshiro128PlusPlus
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

class Shulker : Sequence {
    override lateinit var xoroshiro: Xoroshiro128PlusPlus

    override val key = "minecraft:entities/shulker"

    fun advance(n: Int, looting: Int) {
        repeat(n) {
            nextDrops(xoroshiro, looting)
        }
    }

    fun roll(type: RollType, looting: Int): List<ItemStack> {
        val rng = xoroshiro.copy()

        return when (type) {
            RollType.NextDrop -> {
                nextDrops(rng, looting)
            }
            RollType.KillsUntilItem -> {
                killsUntilShell(rng, looting)
            }
        }
    }

    private fun shellChance(looting: Int) = if (looting == 0) 0.5f else 0.5625f + (looting - 1) * 0.0625f

    fun killsUntilShell(rng: Xoroshiro128PlusPlus, looting: Int): List<ItemStack> {
        val chance = shellChance(looting)

        var kills = 0
        while (true) {
            kills++
            if (rng.nextFloat() < chance) break
        }

        return listOf(ItemStack(Items.SHULKER_SHELL, kills))

    }

    fun nextDrops(rng: Xoroshiro128PlusPlus, looting: Int): MutableList<ItemStack> {
        val drops = mutableListOf<ItemStack>()

        val chance = shellChance(looting)

        if (rng.nextFloat() < chance) {
            drops += ItemStack(Items.SHULKER_SHELL)
        }
        return drops
    }

    val lootTable = listOf(Items.SHULKER_SHELL)
}