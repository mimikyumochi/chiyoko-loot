package lgbt.faith.chiyoko.commands

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import lgbt.faith.chiyoko.functions.EnchantPredictor
import lgbt.faith.chiyoko.functions.EnchantTarget
import lgbt.faith.chiyoko.functions.EligibleEnchantments
import lgbt.faith.chiyoko.functions.Enchantment
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.ChatFormatting
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import net.minecraft.world.item.Item
import java.util.concurrent.CompletableFuture

object PredictEnchant {

    fun register(dispatcher: CommandDispatcher<FabricClientCommandSource>) {

        dispatcher.register(
            ClientCommands.literal("predict")
                .then(
                    ClientCommands.argument(
                        "item",
                        StringArgumentType.word()
                    )
                        .suggests { _, builder ->
                            itemSuggestions(builder)
                        }
                        .then(enchantArgument(1))
                )
        )
    }

    // builds "enchantN levelN [enchantN+1 levelN+1 ...]" up to 3 enchants
    private fun enchantArgument(index: Int): RequiredArgumentBuilder<FabricClientCommandSource, String> {
        val levelArgument = ClientCommands.argument(
            "level$index",
            IntegerArgumentType.integer(1)
        )
            .suggests { ctx, builder ->
                levelSuggestions(ctx, builder, "enchant$index")
            }
            .executes { ctx ->
                execute(ctx.source, index, ctx)
            }

        if (index < 3) {
            levelArgument.then(enchantArgument(index + 1))
        }

        return ClientCommands.argument(
            "enchant$index",
            StringArgumentType.word()
        )
            .suggests { ctx, builder ->
                enchantSuggestions(ctx, builder)
            }
            .then(levelArgument)
    }

    private fun itemFromName(itemName: String): Item? {
        return BuiltInRegistries.ITEM
            .get(Identifier.withDefaultNamespace(itemName))
            .map { it.value() }
            .orElse(null)
    }


    private fun itemSuggestions(
        builder: SuggestionsBuilder
    ): CompletableFuture<Suggestions> {

        val items = BuiltInRegistries.ITEM.keySet()
            .filter { id ->

                val item = BuiltInRegistries.ITEM
                    .get(id)
                    .map { it.value() }
                    .orElse(null)

                item != null &&
                        EligibleEnchantments.getEligibleEnchantments(item)
                            .intersect(EligibleEnchantments.ENCHANT_TABLE)
                            .isNotEmpty()
            }
            .map { it.path }

        return SharedSuggestionProvider.suggest(
            items,
            builder
        )
    }


    private fun enchantSuggestions(
        ctx: CommandContext<FabricClientCommandSource>,
        builder: SuggestionsBuilder
    ): CompletableFuture<Suggestions> {

        val itemName = StringArgumentType.getString(ctx, "item")

        val item = itemFromName(itemName) ?: return builder.buildFuture()


        val used = buildSet {
            for (argument in listOf("enchant1", "enchant2")) {
                try {
                    add(StringArgumentType.getString(ctx, argument))
                } catch (_: Exception) {
                }
            }
        }


        val enchants = EligibleEnchantments.getEligibleEnchantments(item)
            .intersect(EligibleEnchantments.ENCHANT_TABLE)
            .filter { it !in used }


        return SharedSuggestionProvider.suggest(
            enchants,
            builder
        )
    }


    private fun levelSuggestions(
        ctx: CommandContext<FabricClientCommandSource>,
        builder: SuggestionsBuilder,
        enchantArgument: String
    ): CompletableFuture<Suggestions> {

        val enchantName = StringArgumentType.getString(ctx, enchantArgument)

        val enchant = Enchantment[enchantName]
            ?: return builder.buildFuture()

        return SharedSuggestionProvider.suggest(
            (1..enchant.maxLevel)
                .map { it.toString() },
            builder
        )
    }


    private fun execute(
        source: FabricClientCommandSource,
        count: Int,
        ctx: CommandContext<FabricClientCommandSource>
    ): Int {
        val itemName = StringArgumentType.getString(ctx, "item")
        val item = itemFromName(itemName)

        if (item == null) {
            source.sendError(
                Component.literal("Unknown item: $itemName")
                    .withStyle(ChatFormatting.RED)
            )
            return 0
        }

        val targets = (1..count).map { index ->
            EnchantTarget(
                Enchantment[StringArgumentType.getString(ctx, "enchant$index")]!!,
                IntegerArgumentType.getInteger(ctx, "level$index")
            )
        }

        if (targets.size != targets.toSet().size) {
            source.sendError(
                Component.literal("duplicate enchantments are not allowed")
                    .withStyle(ChatFormatting.RED)
            )
            return 0
        }

        val result = EnchantPredictor.predict(item, targets)

        if (result == null) {
            source.sendFeedback(
                Component.literal("no result found")
                    .withStyle(ChatFormatting.RED)
            )
        } else {
            val stacks = result.drops / 64
            val remainder = result.drops % 64
            val stacksText = "$stacks stack${if (stacks != 1) "s" else ""}"
            val dropsText = when {
                stacks == 0 -> "$remainder"
                remainder == 0 -> stacksText
                else -> "$stacksText and $remainder"
            }

            val message = Component.literal("1. ").withStyle(ChatFormatting.DARK_GREEN)
                .append(Component.literal("drop ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal(dropsText).withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" items\n").withStyle(ChatFormatting.GREEN))

                .append(Component.literal("2. ").withStyle(ChatFormatting.DARK_GREEN))
                .append(Component.literal("use ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal("${result.bookshelves}").withStyle(ChatFormatting.AQUA))
                .append(Component.literal(" bookshelves\n").withStyle(ChatFormatting.GREEN))

                .append(Component.literal("3. ").withStyle(ChatFormatting.DARK_GREEN))
                .append(Component.literal("enchant any item once\n").withStyle(ChatFormatting.GREEN))

                .append(Component.literal("4. ").withStyle(ChatFormatting.DARK_GREEN))
                .append(Component.literal("enchant on ").withStyle(ChatFormatting.GREEN))
                .append(Component.literal("slot ${result.slot + 1} ").withStyle(ChatFormatting.AQUA))
                .append(Component.literal("for your chosen enchants\n").withStyle(ChatFormatting.GREEN))

                .append(Component.literal("⚠ moving or taking damage will make this inaccurate").withStyle(ChatFormatting.YELLOW))


            source.sendFeedback(message)
        }

        return 1
    }
}