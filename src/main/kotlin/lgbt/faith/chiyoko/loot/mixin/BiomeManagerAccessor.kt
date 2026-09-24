package lgbt.faith.chiyoko.loot.mixin

import net.minecraft.world.level.biome.BiomeManager
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor


@Mixin(BiomeManager::class)
interface BiomeManagerAccessor {
    @get:Accessor("biomeZoomSeed")
    val biomeZoomSeed: Long
}