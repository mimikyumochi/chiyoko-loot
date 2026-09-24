package lgbt.faith.chiyoko.loot

import com.mojang.brigadier.context.CommandContext
import com.mojang.serialization.Codec
import lgbt.faith.chiyoko.loot.mixin.BiomeManagerAccessor
import lgbt.faith.chiyoko.loot.sequences.Sequence
import lgbt.faith.chiyoko.loot.sequences.Vault
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.component.DataComponentType
import net.minecraft.core.registries.Registries
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.ComponentUtils
import net.minecraft.network.chat.MutableComponent
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.enchantment.Enchantment
import net.minecraft.world.item.enchantment.EnchantmentHelper
import net.minecraft.world.level.Level
import net.minecraft.world.level.biome.BiomeManager

object ChiyokoComponents {
    val VARIANT: DataComponentType<Int> = DataComponentType.builder<Int>()
        .persistent(Codec.INT)
        .build()
}

fun isMatchingSeed(): Boolean {
    val mc = Minecraft.getInstance()
    val level = mc.level ?: return false

    val worldSeed = mc.singleplayerServer?.worldGenSettings?.options()?.seed()
    val worldHash = (level.biomeManager as BiomeManagerAccessor).biomeZoomSeed

    return worldSeed == Chiyoko.seed || worldHash == BiomeManager.obfuscateSeed(Chiyoko.seed)
}

fun sendOverlay(text: MutableComponent, color: ChatFormatting = ChatFormatting.WHITE) {
    val mc = Minecraft.getInstance()
    mc.execute { mc.player?.sendOverlayMessage(text.withStyle(color)) }
}

val modMenuLoaded: Boolean by lazy { FabricLoader.getInstance().isModLoaded("modmenu") }

fun openScreen(screen: Screen?) {
    /*? if >=26.2 {*/
    /*Minecraft.getInstance().gui.setScreen(screen)
    *//*?} else {*/
    Minecraft.getInstance().setScreen(screen)
    /*?}*/
}

// "minecraft:blocks/gravel" -> chiyoko.sequence.blocks.gravel, falling back to the raw id
fun sequenceName(key: String): MutableComponent {
    val id = key.removePrefix("minecraft:")
    return Component.translatableWithFallback("chiyoko.sequence." + id.replace('/', '.'), id)
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
        sendOverlay(Component.translatable("chiyoko.desync.advanced", advances))
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
fun enchantmentLevel(level: Level, enchantment: ResourceKey<Enchantment>, stack: ItemStack): Int? {
    val enchantRegistry = level.registryAccess().lookup(Registries.ENCHANTMENT).orElse(null) ?: return null
    return enchantRegistry.get(enchantment)
        .map { EnchantmentHelper.getItemEnchantmentLevel(it, stack) }
        .orElse(0)
}
