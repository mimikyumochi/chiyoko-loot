package lgbt.faith.chiyoko.loot.mixin

import lgbt.faith.chiyoko.loot.DropEventState
import net.minecraft.client.player.LocalPlayer
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable

// 26.3 moved the Q drop to MultiPlayerGameMode.dropItem, see MultiPlayerGameModeMixin
@Mixin(LocalPlayer::class)
class LocalPlayerMixin {
    //? if <26.3 {
    @Inject(method = ["drop"], at = [At("HEAD")])
    private fun onDrop(fullStack: Boolean, ci: CallbackInfoReturnable<Boolean>) {
        val player = this as LocalPlayer
        DropEventState.recordSelfDrop(player, player.mainHandItem)
    }
    //?}
}
