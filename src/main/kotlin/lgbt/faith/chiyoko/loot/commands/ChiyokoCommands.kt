package lgbt.faith.chiyoko.loot.commands

import com.mojang.brigadier.CommandDispatcher
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource

object ChiyokoCommands {

    fun register(
        dispatcher: CommandDispatcher<FabricClientCommandSource>
    ) {
        ValidateSeed.register(dispatcher)
    }
}