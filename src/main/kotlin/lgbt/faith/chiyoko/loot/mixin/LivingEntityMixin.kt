package lgbt.faith.chiyoko.loot.mixin

import lgbt.faith.chiyoko.loot.DropEventState
import lgbt.faith.chiyoko.loot.PendingShulkerDeath
import lgbt.faith.chiyoko.loot.PendingWitherDeath
import lgbt.faith.chiyoko.loot.enchantmentLevel
import net.minecraft.client.MinecraftClient
import net.minecraft.entity.LivingEntity
import net.minecraft.entity.damage.DamageSource
import net.minecraft.entity.mob.ShulkerEntity
import net.minecraft.entity.mob.WitherSkeletonEntity
import net.minecraft.enchantment.Enchantments
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(LivingEntity::class)
class LivingEntityMixin {

    // the server syncs every hit with its real attacker, so sweeps and projectiles count as player kills here too -
    // attackEntity only sees the entity that was clicked. this always arrives before the death status.
    @Inject(method = ["onDamaged"], at = [At("HEAD")])
    private fun onDamaged(source: DamageSource, ci: CallbackInfo) {
        val entity = this as LivingEntity
        if (!entity.entityWorld.isClient) return
        if (entity !is WitherSkeletonEntity) return

        val player = MinecraftClient.getInstance().player ?: return
        if (source.attacker == player) {
            DropEventState.recentlyAttackedWithers.add(entity.id)
        }
    }

    @Inject(method = ["setHealth"], at = [At("HEAD")])
    private fun onSetHealth(health: Float, ci: CallbackInfo) {
        val entity = this as LivingEntity
        if (!entity.entityWorld.isClient) return
        if (entity.health <= 0f || health > 0f) return

        val mc = MinecraftClient.getInstance()
        val player = mc.player ?: return

        val lootingLevel = enchantmentLevel(entity.entityWorld, Enchantments.LOOTING, player.mainHandStack) ?: return

        if (entity is WitherSkeletonEntity) {
            val playerKilled = DropEventState.recentlyAttackedWithers.remove(entity.id)
            if (!playerKilled) return
            DropEventState.pendingWithers.add(PendingWitherDeath(entity.entityPos, lootingLevel, playerKilled))

        }
        if (entity is ShulkerEntity) {
            DropEventState.pendingShulkers.add(PendingShulkerDeath(entity.entityPos, lootingLevel))

        }
    }
}