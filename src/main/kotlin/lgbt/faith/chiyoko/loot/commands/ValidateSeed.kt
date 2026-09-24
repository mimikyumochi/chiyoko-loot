package lgbt.faith.chiyoko.loot.commands

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.context.CommandContext
import lgbt.faith.chiyoko.loot.Chiyoko
import lgbt.faith.chiyoko.loot.isMatchingSeed
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentUtils

object ValidateSeed {

    fun register(dispatcher: CommandDispatcher<FabricClientCommandSource>) {
        dispatcher.register(
            ClientCommands.literal("validateseed")
                .executes { ctx ->
                    validate(ctx.source)
                    1
                }
        )
    }

    fun validate(source: FabricClientCommandSource): Int {
        val seedText = ComponentUtils.copyOnClickText(Chiyoko.seed.toString())

        if (isMatchingSeed()) {
            source.sendFeedback(
                Component.literal("✔ ").withStyle(ChatFormatting.GREEN)
                    .append(Component.translatable("chiyoko.command.validateseed.correct", seedText).withStyle(ChatFormatting.WHITE))
            )
            return 1
        } else {
            source.sendFeedback(
                Component.literal("✘ ").withStyle(ChatFormatting.RED)
                    .append(Component.translatable("chiyoko.command.validateseed.incorrect", seedText).withStyle(ChatFormatting.WHITE))
            )
            return 0
        }
    }
}