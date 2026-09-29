package lgbt.faith.chiyoko.loot.gui

import lgbt.faith.chiyoko.loot.Chiyoko
import lgbt.faith.chiyoko.loot.gui.OverlayList.LabeledEntry.Companion.toggleLabel
import lgbt.faith.chiyoko.loot.modMenuLoaded
import lgbt.faith.chiyoko.loot.openScreen
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text

// parent is the pause screen, or mod menu's mod list when opened from there
class ChiyokoConfigScreen(private val parent: Screen?) : Screen(Text.translatable("chiyoko.config.title")) {

    override fun init() {
        val btnWidth = 40
        val btnHeight = 20
        val inputWidth = 130
        val gap = 10
        val centerX = this.width / 2
        val yPos = (this.height / 2) - 30


        // row 1: [seedInput (130px)] [save (50px)] - total 150px + (gap*2)
        val row1TotalWidth = inputWidth + (gap*2) + btnWidth
        val row1StartX = centerX - row1TotalWidth / 2

        val seedInput = TextFieldWidget(
            this.textRenderer,
            centerX - (inputWidth/2) - (gap*2),
            yPos,
            inputWidth,
            btnHeight,
            Text.translatable("chiyoko.config.seed")
        )
        seedInput.setChangedListener { text ->
            if (text.isNotEmpty() && !text.matches(Regex("-?\\d*"))) {
                seedInput.text = text.replace(Regex("[^0-9-]"), "")
            }
        }
        seedInput.text = Chiyoko.seed.toString()
        seedInput.setMaxLength(20)
        this.addDrawableChild(seedInput)

        this.addDrawableChild(
            ButtonWidget.builder(Text.translatable("chiyoko.config.save")) {

                val s = seedInput.text
                Chiyoko.seed = s.toLong()
                Chiyoko.changeWorldSeed()

                openScreen(null)
            }
            .dimensions(row1StartX + inputWidth+gap, yPos, btnWidth, btnHeight)
            .build()
        )

        // row 1: [edit layout (75px)] [edit layout (75px)] - total 150px + gap
        val editBtnWidth = btnWidth + 35
        val row2TotalWidth = editBtnWidth + gap + editBtnWidth
        val row2StartX = centerX - row2TotalWidth / 2
        val row2Y = yPos + btnHeight + gap

        this.addDrawableChild(
            ButtonWidget.builder(Text.translatable("chiyoko.config.edit_layout")) {
                openScreen(ChiyokoLayoutEditor(this))
            }
            .dimensions(row2StartX, row2Y, editBtnWidth, btnHeight)
            .build()
        )
        this.addDrawableChild(
            ButtonWidget.builder(Text.translatable("chiyoko.config.edit_overlays")) {
                openScreen(ChiyokoOverlayEditor(this))
            }
            .dimensions(row2StartX + editBtnWidth + gap, row2Y, editBtnWidth, btnHeight)
            .build()
        )

        var nextRowY = row2Y + btnHeight + gap

        // optional row: [pause button toggle (160px)] - only with mod menu, since without it
        // the pause button is the only way back into this screen
        if (modMenuLoaded) {
            val config = Chiyoko.configManager.config
            this.addDrawableChild(
                ButtonWidget.builder(pauseButtonLabel()) {
                    config.showPauseButton = !config.showPauseButton
                    Chiyoko.configManager.save()
                    it.message = pauseButtonLabel()
                }
                    .dimensions(centerX - row2TotalWidth / 2, nextRowY, row2TotalWidth, btnHeight)
                    .build()
            )
            nextRowY += btnHeight + gap
        }

        // last row [close (75px)] - total 75px, centred
        val closeStartX = centerX - (editBtnWidth/2)

        this.addDrawableChild(
            ButtonWidget.builder(Text.translatable("chiyoko.config.close")) { close() }
                .dimensions(closeStartX, nextRowY, editBtnWidth, btnHeight)
                .build()
        )
    }

    private fun pauseButtonLabel(): Text = Text.translatable(
        "chiyoko.config.pause_button",
        toggleLabel(Chiyoko.configManager.config.showPauseButton, "chiyoko.toggle.shown", "chiyoko.toggle.hidden"),
    )

    override fun close() {
        openScreen(parent)
    }
}
