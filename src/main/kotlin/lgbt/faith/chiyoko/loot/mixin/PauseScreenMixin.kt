package lgbt.faith.chiyoko.loot.mixin

import lgbt.faith.chiyoko.loot.Chiyoko
import lgbt.faith.chiyoko.loot.gui.ChiyokoConfigScreen
import lgbt.faith.chiyoko.loot.gui.ChiyokoRenderer
import lgbt.faith.chiyoko.loot.modMenuLoaded
import lgbt.faith.chiyoko.loot.openScreen
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button.builder
import net.minecraft.client.gui.components.Renderable
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.narration.NarratableEntry
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.gen.Invoker
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Inject
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

@Mixin(Screen::class)
interface ScreenAccessor {
    @Invoker("addRenderableWidget")
    fun <T> callAddRenderableWidget(widget: T): T
            where T : GuiEventListener, T : Renderable, T : NarratableEntry
}

@Mixin(PauseScreen::class)
abstract class PauseScreenMixin {

    @Inject(
        method = ["extractRenderState"],
        at = [At("HEAD")]
    )
    private fun dropseed(
        graphics: GuiGraphicsExtractor,
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
        val screen = this as PauseScreen
        if (!screen.showsPauseMenu()) return
        // with mod menu the config is reachable from the mods list, so the button can be hidden
        if (modMenuLoaded && !Chiyoko.configManager.config.showPauseButton) return

        val width = 60
        val height = 20
        val x = screen.width - width - 5
        val y = screen.height - height - 5

        val button = builder(Component.translatable("chiyoko.pause_button")) {
            openScreen(ChiyokoConfigScreen(screen))
        }.bounds(x, y, width, height).build()

        (this as ScreenAccessor).callAddRenderableWidget(button)
    }
}
