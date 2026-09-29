package lgbt.faith.chiyoko.loot

import com.mojang.brigadier.context.CommandContext
import com.mojang.serialization.Codec
import lgbt.faith.chiyoko.loot.mixin.BiomeManagerAccessor
import lgbt.faith.chiyoko.loot.sequences.Sequence
import lgbt.faith.chiyoko.loot.sequences.Vault
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.util.Formatting
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.Screen
import net.minecraft.component.ComponentType
import net.minecraft.registry.RegistryKeys
import net.minecraft.text.Text
import net.minecraft.text.Texts
import net.minecraft.text.MutableText
import net.minecraft.registry.RegistryKey
import net.minecraft.item.ItemStack
import net.minecraft.enchantment.Enchantment
import net.minecraft.enchantment.EnchantmentHelper
import net.minecraft.world.World
import net.minecraft.world.biome.source.BiomeAccess

object ChiyokoComponents {
    val VARIANT: ComponentType<Int> = ComponentType.builder<Int>()
        .codec(Codec.INT)
        .build()
}

fun isMatchingSeed(): Boolean {
    val mc = MinecraftClient.getInstance()
    val level = mc.world ?: return false

    val worldSeed = mc.server?.saveProperties?.generatorOptions?.seed
    val worldHash = (level.biomeAccess as BiomeManagerAccessor).biomeZoomSeed

    return worldSeed == Chiyoko.seed || worldHash == BiomeAccess.hashSeed(Chiyoko.seed)
}

fun sendOverlay(text: MutableText, color: Formatting = Formatting.WHITE) {
    val mc = MinecraftClient.getInstance()
    mc.execute { mc.player?.sendMessage(text.formatted(color), true) }
}

val modMenuLoaded: Boolean by lazy { FabricLoader.getInstance().isModLoaded("modmenu") }

fun openScreen(screen: Screen?) {
    MinecraftClient.getInstance().setScreen(screen)
}

// "minecraft:blocks/gravel" -> chiyoko.sequence.blocks.gravel, falling back to the raw id
fun sequenceName(key: String): MutableText {
    val id = key.removePrefix("minecraft:")
    return Text.translatableWithFallback("chiyoko.sequence." + id.replace('/', '.'), id)
}

fun handleVaultDesync(actual: ItemStack, isOminous: Boolean) {

    val vault = vaultSequence(isOminous) ?: return

    var advances = 0L
    val maxAdvances = 1000
    do {
        val predicted = vault.peek(1, false)
        vault.advance(1)
        advances++
    } while((predicted.lastOrNull()?.item != actual.item ||
            predicted.lastOrNull()?.count != actual.count) && advances < maxAdvances)

    if (advances > 0) {
        Chiyoko.configManager.updateSequence(vault, advances)
        sendOverlay(Text.translatable("chiyoko.desync.advanced", advances))
    }
}

fun vaultSequence(isOminous: Boolean): Vault? {
    val sequences = Chiyoko.sequences.map
    return if (isOminous) sequences["minecraft:chests/trial_chambers/reward_ominous"] as? Vault
    else           sequences["minecraft:chests/trial_chambers/reward"] as? Vault
}

// returns the sequence only if its overlay is tracked
inline fun <reified T : Sequence> trackedSequence(key: String): T? {
    if (Chiyoko.configManager.config.getOverlay(key).tracked != true) return null
    return Chiyoko.sequences.map[key] as? T
}

// null if the enchantment registry isn't available, 0 if the item doesn't have the enchant
fun enchantmentLevel(level: World, enchantment: RegistryKey<Enchantment>, stack: ItemStack): Int? {
    val enchantRegistry = level.registryManager.getOptional(RegistryKeys.ENCHANTMENT).orElse(null) ?: return null
    return enchantRegistry.getOptional(enchantment)
        .map { EnchantmentHelper.getLevel(it, stack) }
        .orElse(0)
}
