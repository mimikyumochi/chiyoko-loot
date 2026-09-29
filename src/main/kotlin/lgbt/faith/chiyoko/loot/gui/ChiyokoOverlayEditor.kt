package lgbt.faith.chiyoko.loot.gui

import net.minecraft.util.Formatting
import net.minecraft.client.MinecraftClient
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.widget.ClickableWidget
import net.minecraft.client.gui.widget.ButtonWidget
import net.minecraft.client.gui.widget.ElementListWidget
import net.minecraft.client.gui.widget.TextFieldWidget
import net.minecraft.client.gui.tooltip.Tooltip
import net.minecraft.client.gui.Element
import net.minecraft.client.gui.Selectable
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import net.minecraft.text.MutableText
import net.minecraft.item.ItemStack
import net.minecraft.item.Items
import lgbt.faith.chiyoko.loot.Chiyoko
import lgbt.faith.chiyoko.loot.config.ChiyokoConfigManager
import lgbt.faith.chiyoko.loot.config.OverlayConfig
import lgbt.faith.chiyoko.loot.config.OverlayRotation
import lgbt.faith.chiyoko.loot.config.RollType
import lgbt.faith.chiyoko.loot.openScreen
import lgbt.faith.chiyoko.loot.sequenceName
import lgbt.faith.chiyoko.loot.sequences.*
import kotlin.math.min

class ChiyokoOverlayEditor(private val parent: Screen) : Screen(Text.translatable("chiyoko.overlay.title")) {

    private val configManager = Chiyoko.configManager

    private lateinit var list: OverlayList
    private val tabButtons = mutableListOf<Pair<String, ButtonWidget>>()
    var selectedKey: String? = null

    private val sequenceKeys: List<String>
        get() = Chiyoko.sequences.map.keys.filter { configManager.config.getOverlay(it).tracked == true }

    fun refreshUI() {
        val keys = sequenceKeys
        if (selectedKey !in keys) {
            selectedKey = keys.firstOrNull()
        }
        buildTabs(keys)
        buildList()
    }

    override fun init() {
        refreshUI()

        addDrawableChild(ButtonWidget.builder(Text.translatable("chiyoko.overlay.done")) {
            close()
        }.dimensions(width / 2 - 100, height - 27, 200, 20).build())
    }

    private fun buildTabs(keys: List<String>) {
        tabButtons.forEach { (_, button) -> remove(button) }
        tabButtons.clear()

        val untracked = Chiyoko.sequences.map.keys.filter { configManager.config.getOverlay(it).tracked != true }
        val showPlus = untracked.isNotEmpty()

        val tabSize = 20
        val spacing = 2
        val totalTabs = keys.size + if (showPlus) 1 else 0
        val totalWidth = totalTabs * tabSize + (totalTabs - 1).coerceAtLeast(0) * spacing
        var x = (width - totalWidth) / 2
        val y = 22

        keys.forEach { key ->
            val button = ButtonWidget.builder(Text.empty()) {
                selectedKey = key
                buildList()
            }.dimensions(x, y, tabSize, tabSize)
                .tooltip(Tooltip.of(sequenceName(key)))
                .build()

            tabButtons += key to button
            addDrawableChild(button)
            x += tabSize + spacing
        }

        if (showPlus) {
            val plusButton = ButtonWidget.builder(Text.literal("+")) {
                openScreen(ChiyokoAddTrackerScreen(this))
            }.dimensions(x, y, tabSize, tabSize)
                .tooltip(Tooltip.of(Text.translatable("chiyoko.overlay.add_tracker")))
                .build()

            tabButtons += "+" to plusButton
            addDrawableChild(plusButton)
        }
    }

    fun getItemForSequence(key: String): ItemStack {
        val sequenceType = Chiyoko.sequences.map[key]
        val item = when (sequenceType) {
            is Fishing -> Items.COD
            is WitherSkeleton -> Items.WITHER_SKELETON_SKULL
            is PiglinBartering -> Items.PIGLIN_HEAD
            is Shulker -> Items.SHULKER_SHELL
            is Gravel -> Items.FLINT
            is Vault -> {
                if (key.contains("ominous", ignoreCase = true)) {
                    Items.OMINOUS_TRIAL_KEY
                } else {
                    Items.TRIAL_KEY
                }
            }
            else -> Items.BARRIER
        }
        return ItemStack(item)
    }

    private fun buildList() {
        if (::list.isInitialized) {
            remove(list)
        }

        list = OverlayList(client!!, width, height - 80, 50, 25)
        val key = selectedKey

        if (key != null) {
            val overlay = configManager.config.getOverlay(key)
            val sequenceType = Chiyoko.sequences.map[key]

            list.addEntry(OverlayList.UntrackEntry(key, configManager, this))
            list.addEntry(OverlayList.VisibleEntry(key, overlay, configManager))
            list.addEntry(OverlayList.RotationEntry(key, overlay, configManager))
            list.addEntry(OverlayList.ReversedEntry(key, overlay, configManager))

            if (sequenceType is WitherSkeleton || sequenceType is Shulker) {
                list.addEntry(OverlayList.RollTypeEntry(key, overlay, configManager))
            }
            if (sequenceType is Fishing || sequenceType is PiglinBartering || sequenceType is Gravel || sequenceType is Vault) {
                list.addEntry(OverlayList.AdvancesEntry(key, overlay, configManager, textRenderer))
            }
            if (sequenceType is Vault) {
                list.addEntry(OverlayList.SplitEntry(key, overlay, configManager))
            }
        } else {
            list.addEntry(OverlayList.InfoEntry(Text.translatable("chiyoko.overlay.no_trackers")))
        }

        addDrawableChild(list)
    }

    override fun close() {
        configManager.save()
        openScreen(parent)
    }

    override fun render(graphics: DrawContext, mouseX: Int, mouseY: Int, a: Float) {
        list.render(graphics, mouseX, mouseY, a)
        super.render(graphics, mouseX, mouseY, a)

        tabButtons.forEach { (key, button) ->
            if (key != "+") {
                val stack = getItemForSequence(key)
                graphics.drawItem(stack, button.x + 2, button.y + 2)
            }

            if (key == selectedKey && key != "+") {
                val indicator = Text.literal("●").formatted(Formatting.YELLOW)
                val textX = button.x + (button.width - textRenderer.getWidth(indicator)) / 2
                graphics.drawTextWithShadow(textRenderer, indicator, textX + 1, button.y + button.height - 1, 0xFFFFFFFF.toInt())
            }
        }
    }
}

class ChiyokoAddTrackerScreen(private val parent: ChiyokoOverlayEditor) : Screen(Text.translatable("chiyoko.add_tracker.title")) {

    private val configManager = Chiyoko.configManager
    private val gridButtons = mutableListOf<Pair<String, ButtonWidget>>()

    override fun init() {
        val untracked = Chiyoko.sequences.map.keys.filter { configManager.config.getOverlay(it).tracked != true }

        val buttonSize = 20
        val spacing = 4
        val maxColumns = 10

        val columns = min(untracked.size, maxColumns)
        val gridWidth = columns * buttonSize + (columns - 1).coerceAtLeast(0) * spacing
        val startX = (width - gridWidth) / 2
        val startY = height / 4

        untracked.forEachIndexed { index, key ->
            val col = index % maxColumns
            val row = index / maxColumns

            val x = startX + col * (buttonSize + spacing)
            val y = startY + row * (buttonSize + spacing)
            val btn = ButtonWidget.builder(Text.empty()) {
                configManager.config.updateOverlay(key) { tracked = true }
                parent.selectedKey = key
                openScreen(parent)
                parent.refreshUI()
            }.dimensions(x, y, buttonSize, buttonSize)
                .tooltip(Tooltip.of(Text.translatable("chiyoko.add_tracker.track", sequenceName(key))))
                .build()

            gridButtons += key to btn
            addDrawableChild(btn)
        }

        addDrawableChild(ButtonWidget.builder(Text.translatable("chiyoko.add_tracker.cancel")) {
            close()
        }.dimensions(width / 2 - 50, height - 35, 100, 20).build())
    }

    override fun close() {
        openScreen(parent)
    }

    override fun render(graphics: DrawContext, mouseX: Int, mouseY: Int, a: Float) {
        super.render(graphics, mouseX, mouseY, a)

        gridButtons.forEach { (key, button) ->
            val stack = parent.getItemForSequence(key)
            graphics.drawItem(stack, button.x + 2, button.y + 2)
        }
    }
}

class OverlayList(mc: MinecraftClient, width: Int, height: Int, y0: Int, itemHeight: Int) : ElementListWidget<OverlayList.Entry>(mc, width, height, y0, itemHeight) {
    abstract class Entry : ElementListWidget.Entry<Entry>()

    public override fun addEntry(entry: Entry): Int {
        return super.addEntry(entry)
    }

    // label on the left, widget on the right
    abstract class LabeledEntry(labelKey: String) : Entry() {
        private val label: Text = Text.translatable(labelKey)
        private var _focused = false
        protected abstract val widget: ClickableWidget

        override fun render(graphics: DrawContext, mouseX: Int, mouseY: Int, hovered: Boolean, a: Float) {
            val mc = MinecraftClient.getInstance()
            graphics.drawTextWithShadow(mc.textRenderer, label, contentX, contentMiddleY - mc.textRenderer.fontHeight / 2, 0xFFFFFFFF.toInt())
            widget.setPosition(contentRightEnd - 150, contentY)
            widget.render(graphics, mouseX, mouseY, a)
        }

        override fun children(): List<Element> = listOf(widget)
        override fun selectableChildren(): List<Selectable> = listOf(widget)

        override fun setFocused(focused: Boolean) {_focused = focused}
        override fun isFocused(): Boolean {return _focused}

        companion object {
            fun toggleLabel(value: Boolean, trueKey: String = "chiyoko.toggle.true", falseKey: String = "chiyoko.toggle.false"): Text {
                return if (value) {
                    Text.translatable(trueKey).formatted(Formatting.GREEN)
                } else {
                    Text.translatable(falseKey).formatted(Formatting.RED)
                }
            }
        }
    }

    class UntrackEntry(val key: String, val configManager: ChiyokoConfigManager, val editor: ChiyokoOverlayEditor) : LabeledEntry("chiyoko.overlay.tracking") {
        private val button = ButtonWidget.builder(Text.translatable("chiyoko.overlay.untrack").formatted(Formatting.RED)) {
            configManager.config.updateOverlay(key) { tracked = false }
            editor.selectedKey = null
            editor.refreshUI()
        }.dimensions(0, 0, 150, 20).build()

        override val widget get() = button
    }

    class InfoEntry(val text: Text) : Entry() {
        override fun render(graphics: DrawContext, mouseX: Int, mouseY: Int, hovered: Boolean, a: Float) {
            val mc = MinecraftClient.getInstance()
            val textComponent = text.copy().formatted(Formatting.GRAY)
            graphics.drawTextWithShadow(mc.textRenderer, textComponent, contentX + (contentWidth - mc.textRenderer.getWidth(textComponent)) / 2, contentMiddleY - mc.textRenderer.fontHeight / 2, 0xFFFFFFFF.toInt())
        }
        override fun children(): List<Element> = emptyList()
        override fun selectableChildren(): List<Selectable> = emptyList()
        override fun setFocused(focused: Boolean) {}
        override fun isFocused(): Boolean = false
    }

    class VisibleEntry(val key: String, val overlay: OverlayConfig, val configManager: ChiyokoConfigManager) : LabeledEntry("chiyoko.overlay.visibility") {
        private val button = ButtonWidget.builder(visibleLabel()) {
            overlay.visible = !overlay.visible
            it.message = visibleLabel()
            configManager.config.updateOverlay(key) { visible = overlay.visible }
        }.dimensions(0, 0, 150, 20).build()

        override val widget get() = button

        private fun visibleLabel(): Text = toggleLabel(overlay.visible, "chiyoko.toggle.shown", "chiyoko.toggle.hidden")
    }

    class RotationEntry(val key: String, val overlay: OverlayConfig, val configManager: ChiyokoConfigManager) : LabeledEntry("chiyoko.overlay.rotation") {
        private val button = ButtonWidget.builder(rotationLabel()) {
            overlay.rotation = when (overlay.rotation) {
                OverlayRotation.HORIZONTAL -> OverlayRotation.VERTICAL
                OverlayRotation.VERTICAL -> OverlayRotation.HORIZONTAL
            }
            it.message = rotationLabel()
            configManager.config.updateOverlay(key) { rotation = overlay.rotation }
        }.dimensions(0, 0, 150, 20).build()

        override val widget get() = button

        private fun rotationLabel(): MutableText {
            return when (overlay.rotation) {
                OverlayRotation.VERTICAL -> Text.translatable("chiyoko.overlay.rotation.vertical")
                OverlayRotation.HORIZONTAL -> Text.translatable("chiyoko.overlay.rotation.horizontal")
            }
        }
    }

    class ReversedEntry(val key: String, val overlay: OverlayConfig, val configManager: ChiyokoConfigManager) : LabeledEntry("chiyoko.overlay.reversed") {
        private val button = ButtonWidget.builder(reversedLabel()) {
            overlay.reversed = !overlay.reversed
            it.message = reversedLabel()
            configManager.config.updateOverlay(key) { reversed = overlay.reversed }
        }.dimensions(0, 0, 150, 20).build()

        override val widget get() = button

        private fun reversedLabel(): Text = toggleLabel(overlay.reversed)
    }

    class RollTypeEntry(val key: String, val overlay: OverlayConfig, val configManager: ChiyokoConfigManager) : LabeledEntry("chiyoko.overlay.roll_type") {
        private val button = ButtonWidget.builder(rollTypeLabel()) {
            overlay.rollType = when (overlay.rollType ?: RollType.KillsUntilItem) {
                RollType.NextDrop -> RollType.KillsUntilItem
                RollType.KillsUntilItem -> RollType.NextDrop
            }
            it.message = rollTypeLabel()
            configManager.config.updateOverlay(key) { rollType = overlay.rollType }
        }.dimensions(0, 0, 150, 20).build()

        override val widget get() = button

        private fun rollTypeLabel(): Text = when (overlay.rollType ?: RollType.KillsUntilItem) {
            RollType.NextDrop -> Text.translatable("chiyoko.overlay.roll_type.next_drop")
            RollType.KillsUntilItem -> Text.translatable("chiyoko.overlay.roll_type.kills_until_item")
        }
    }

    class AdvancesEntry(
        private val key: String,
        private val overlay: OverlayConfig,
        private val configManager: ChiyokoConfigManager,
        font: TextRenderer
    ) : LabeledEntry("chiyoko.overlay.advances") {
        private val editBox = TextFieldWidget(font, 0, 0, 150, 20, Text.translatable("chiyoko.overlay.advances")).also {
            it.text = overlay.advances.toString()
            it.setChangedListener { s ->
                if (s.isNotEmpty() && !s.matches(Regex("-?\\d*"))) {
                    it.text = s.replace(Regex("[^0-9-]"), "").toInt().coerceAtLeast(1).toString()
                }

                val v = s.toIntOrNull() ?: return@setChangedListener
                overlay.advances = v
                configManager.config.updateOverlay(key) { advances = v }
            }
        }

        override val widget get() = editBox
    }

    class SplitEntry(val key: String, val overlay: OverlayConfig, val configManager: ChiyokoConfigManager) : LabeledEntry("chiyoko.overlay.split") {
        private val button = ButtonWidget.builder(splitLabel()) {
            overlay.split = !overlay.split
            it.message = splitLabel()
            configManager.config.updateOverlay(key) { split = overlay.split }
        }.dimensions(0, 0, 150, 20).build()

        override val widget get() = button

        private fun splitLabel(): Text = toggleLabel(overlay.split)
    }
}