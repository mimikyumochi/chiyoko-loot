package lgbt.faith.chiyoko.loot.mixin

import lgbt.faith.chiyoko.loot.DropEventState
import net.minecraft.client.network.ClientPlayerEntity
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

// 26.3 moved the Q drop to MultiPlayerGameMode.dropItem, see MultiPlayerGameModeMixin
@Mixin(ClientPlayerEntity::class)
class LocalPlayerMixin {
    @Inject(method = ["dropSelectedItem"], at = [At("HEAD")])
    private fun onDrop(fullStack: Boolean, ci: CallbackInfoReturnable<Boolean>) {
        val player = this as ClientPlayerEntity
        DropEventState.recordSelfDrop(player, player.mainHandStack)
    }
}
