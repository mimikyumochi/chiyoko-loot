package lgbt.faith.chiyoko.loot.commands

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import lgbt.faith.chiyoko.loot.Chiyoko
import lgbt.faith.chiyoko.loot.isMatchingSeed
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.util.Formatting
import net.minecraft.text.Text
import net.minecraft.text.Texts

object ValidateSeed {

    fun register(dispatcher: CommandDispatcher<FabricClientCommandSource>) {
        dispatcher.register(
            ClientCommandManager.literal("validateseed")
                .executes { ctx ->
                    validate(ctx.source)
                    1
                }
        )
    }

    fun validate(source: FabricClientCommandSource): Int {
        val seedText = Texts.bracketedCopyable(Chiyoko.seed.toString())

        if (isMatchingSeed()) {
            source.sendFeedback(
                Text.literal("✔ ").formatted(Formatting.GREEN)
                    .append(Text.translatable("chiyoko.command.validateseed.correct", seedText).formatted(Formatting.WHITE))
            )
            return 1
        } else {
            source.sendFeedback(
                Text.literal("✘ ").formatted(Formatting.RED)
                    .append(Text.translatable("chiyoko.command.validateseed.incorrect", seedText).formatted(Formatting.WHITE))
            )
            return 0
        }
    }
}