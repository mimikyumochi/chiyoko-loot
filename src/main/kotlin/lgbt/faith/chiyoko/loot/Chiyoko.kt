package lgbt.faith.chiyoko.loot

import lgbt.faith.chiyoko.loot.commands.ChiyokoCommands
import lgbt.faith.chiyoko.loot.config.ChiyokoConfigManager
import lgbt.faith.chiyoko.loot.rand.RandomSupport
import lgbt.faith.chiyoko.loot.sequences.*
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.toast.SystemToast
import net.minecraft.text.Text
import net.minecraft.util.WorldSavePath
import kotlin.io.path.name


val keys = listOf(
    "minecraft:chests/trial_chambers/reward_ominous",
    "minecraft:chests/trial_chambers/reward",
    "minecraft:gameplay/piglin_bartering",
    "minecraft:gameplay/fishing",
    "minecraft:entities/wither_skeleton",
    "minecraft:blocks/gravel",
    "minecraft:entities/shulker"
)
data class Sequences(
    val map: MutableMap<String, Sequence> = mutableMapOf()
)
private fun createSequence(key: String): Sequence? {
    return when (key) {
        "minecraft:chests/trial_chambers/reward_ominous" -> Vault(true)
        "minecraft:chests/trial_chambers/reward" -> Vault(false)
        "minecraft:gameplay/piglin_bartering" -> PiglinBartering()
        "minecraft:gameplay/fishing" -> Fishing()
        "minecraft:entities/wither_skeleton" -> WitherSkeleton()
        "minecraft:blocks/gravel" -> Gravel()
        "minecraft:entities/shulker" -> Shulker()
        else -> null
    }
}

class Chiyoko : ClientModInitializer {
    companion object {
        val mc = MinecraftClient.getInstance()

        var loaded = false
        var seed: Long = 0
        var worldName: String = ""
        lateinit var configManager: ChiyokoConfigManager

        val sequences = Sequences()

        fun changeWorldSeed() {
            val worldData = configManager.config.worlds[worldName] ?: return

            for ((key, sequence) in sequences.map) {

                val seqData = worldData.sequences[key] ?: continue
                val advances = seqData.advances.toInt()

                val rng = RandomSupport.createSequence(seed, key)
                rng.advance(advances)

                sequence.loadState(rng.seedLo, rng.seedHi)

                configManager.updateSequence(worldName, seed, rng, key, 0)
            }
        }
    }


    override fun onInitializeClient() {
        ChiyokoComponents // force component to register

        configManager = ChiyokoConfigManager()
        configManager.load()

        ClientCommandRegistrationCallback.EVENT.register { dispatcher, _ ->
            ChiyokoCommands.register(dispatcher)
        }

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            loaded = false
        }


        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            loaded = true
            var s: Long
            var w: String

            if (mc.currentServerEntry == null) {
                s = mc.server!!.saveProperties.generatorOptions.seed
                w = mc.server!!.getSavePath(WorldSavePath.ROOT).parent.name
            } else {
                w = mc.currentServerEntry!!.address
                s = configManager.config.worlds[w]?.worldSeed ?: 0
            }

            seed = s
            worldName = w
            val worldData = configManager.config.worlds[worldName]

            for (key in keys) {

                val sequence = createSequence(key) ?: continue
                sequences.map[key] = sequence

                val saved = worldData?.sequences?.get(key)

                if (saved != null) {
                    sequence.loadState(saved.seedLo, saved.seedHi)
                } else {
                    sequence.init(seed)
                    configManager.addSequence(worldName, seed, sequence.getRngCopy(), key)
                }
            }
        }

        ClientTickEvents.END_CLIENT_TICK.register { client ->

            if (configManager.wasReset) {
                SystemToast.add(
                    client.toastManager,
                    SystemToast.Type.PERIODIC_NOTIFICATION,
                    Text.translatable("chiyoko.toast.config_reset.title"),
                    Text.translatable("chiyoko.toast.config_reset.description")
                )
                configManager.wasReset = false
            }
        }
    }
}