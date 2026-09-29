package lgbt.faith.chiyoko.loot.mixin

import net.minecraft.entity.projectile.FishingBobberEntity
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Accessor

@Mixin(FishingBobberEntity::class)
interface FishingHookAccessor {
    @Accessor("inOpenWater")
    fun isOpenWater(): Boolean

    @Accessor("caughtFish")
    fun biting(): Boolean


}