package lgbt.faith.chiyoko.sequences

import lgbt.faith.chiyoko.rand.RandomSupport
import lgbt.faith.chiyoko.rand.Xoroshiro128PlusPlus
import net.minecraft.world.item.ItemStack

interface Sequence {
    data class Entry(val item: ItemStack, val start: Int, val end: Int)

    var xoroshiro: Xoroshiro128PlusPlus
    val key: String

    fun init(worldSeed: Long) {
        xoroshiro = RandomSupport.createSequence(worldSeed, key)
    }

    fun loadState(seedLo: Long, seedHi: Long) {
        xoroshiro = Xoroshiro128PlusPlus(seedLo, seedHi)
    }

    fun getRngCopy() = xoroshiro.copy()

    companion object {
        fun rollEntry(rng: Xoroshiro128PlusPlus, table: List<Entry>): ItemStack {
            val roll = rng.nextInt(table.last().end)
            return table.first { roll in it.start until it.end }.item
        }
    }
}