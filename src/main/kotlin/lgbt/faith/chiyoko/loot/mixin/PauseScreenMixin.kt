package lgbt.faith.chiyoko.loot.mixin

import lgbt.faith.chiyoko.loot.Chiyoko
import lgbt.faith.chiyoko.loot.gui.ChiyokoConfigScreen
import lgbt.faith.chiyoko.loot.gui.ChiyokoRenderer
import lgbt.faith.chiyoko.loot.modMenuLoaded
import lgbt.faith.chiyoko.loot.openScreen
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.widget.ButtonWidget.builder
import net.minecraft.client.gui.Drawable
import net.minecraft.client.gui.Element
import net.minecraft.client.gui.Selectable
import net.minecraft.client.gui.screen.GameMenuScreen
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Invoker
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(Screen::class)
interface ScreenAccessor {
    @Invoker("addDrawableChild")
    fun <T> callAddRenderableWidget(widget: T): T
            where T : Element, T : Drawable, T : Selectable
}

@Mixin(GameMenuScreen::class)
abstract class PauseScreenMixin {

    @Inject(
        method = ["render"],
        at = [At("HEAD")]
    )
    private fun dropseed(
        graphics: DrawContext,
        mouseX: Int,
        mouseY: Int,
        a: Float,
        ci: CallbackInfo
    ) {
        ChiyokoRenderer().render(graphics)
    }

    // added once per init rather than every frame, which piled up a new button each render
    @Inject(method = ["init"], at = [At("TAIL")])
    private fun addConfigButton(ci: CallbackInfo) {
        val screen = this as GameMenuScreen
        if (!screen.shouldShowMenu()) return
        // with mod menu the config is reachable from the mods list, so the button can be hidden
        if (modMenuLoaded && !Chiyoko.configManager.config.showPauseButton) return

        val width = 60
        val height = 20
        val x = screen.width - width - 5
        val y = screen.height - height - 5

        val button = builder(Text.translatable("chiyoko.pause_button")) {
            openScreen(ChiyokoConfigScreen(screen))
        }.dimensions(x, y, width, height).build()

        (this as ScreenAccessor).callAddRenderableWidget(button)
    }
}
