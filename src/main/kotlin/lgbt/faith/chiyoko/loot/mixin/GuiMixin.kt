package lgbt.faith.chiyoko.loot.mixin

import lgbt.faith.chiyoko.loot.gui.ChiyokoRenderer
import net.minecraft.client.render.RenderTickCounter
import net.minecraft.client.gui.hud.InGameHud
import net.minecraft.client.gui.DrawContext
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(InGameHud::class)
class GuiMixin {
    @Inject(
        method = ["render"],
        at = [At("TAIL")]
    )
    private fun dropseed(
        graphics: DrawContext,
        deltaTracker: RenderTickCounter,
        ci: CallbackInfo
    ) {
        ChiyokoRenderer().render(graphics)
    }
}