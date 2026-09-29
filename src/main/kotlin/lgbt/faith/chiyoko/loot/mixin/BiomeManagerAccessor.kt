package lgbt.faith.chiyoko.loot.mixin

import net.minecraft.world.biome.source.BiomeAccess
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor


@Mixin(BiomeAccess::class)
interface BiomeManagerAccessor {
    @get:Accessor("seed")
    val biomeZoomSeed: Long
}