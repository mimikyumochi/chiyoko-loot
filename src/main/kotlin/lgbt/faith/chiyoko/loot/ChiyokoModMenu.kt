package lgbt.faith.chiyoko.loot

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import lgbt.faith.chiyoko.loot.gui.ChiyokoConfigScreen

// only loaded through the "modmenu" entrypoint, so mod menu is guaranteed to be present here.
// nothing else may reference this class - use modMenuLoaded instead
class ChiyokoModMenu : ModMenuApi {
    override fun getModConfigScreenFactory() = ConfigScreenFactory { parent -> ChiyokoConfigScreen(parent) }
}
