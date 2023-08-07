package su.plo.voice.discs.event

import kotlinx.coroutines.*
import kotlinx.coroutines.future.await
import net.kyori.adventure.text.TextComponent
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.block.Block
import org.bukkit.event.EventHandler
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.block.BlockBreakEvent
import org.bukkit.event.block.BlockRedstoneEvent
import org.bukkit.event.entity.EntityExplodeEvent
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import su.plo.lib.api.chat.MinecraftTextComponent
import su.plo.lib.api.chat.MinecraftTextStyle
import su.plo.lib.api.server.world.ServerPos3d
import su.plo.voice.api.server.player.VoicePlayer
import su.plo.voice.discs.DiscsPlugin
import su.plo.voice.discs.utils.extend.*
import su.plo.voice.discs.utils.suspendSync

class JukeboxEventListener(
    private val plugin: DiscsPlugin
): Listener {

    private val jobByBlock: MutableMap<Block, Job> = HashMap()

    private val scope = CoroutineScope(Dispatchers.Default)
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onJukeBoxCharge(event: BlockRedstoneEvent){
        if(!event.block.isJukebox()) return
        val jukebox = event.block.asJukebox()
        val record = jukebox?.record
        record?.itemMeta
            ?.persistentDataContainer
            ?.let {
                it.get(plugin.identifierKey, PersistentDataType.STRING) ?:
                it.get(plugin.oldIdentifierKey, PersistentDataType.STRING)
            }
            ?: return
        event.newCurrent = event.oldCurrent
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDiscInsert(event: PlayerInteractEvent) {

        if (event.action != Action.RIGHT_CLICK_BLOCK) return

        val block = event.clickedBlock ?: return

        val jukebox = block.asJukebox() ?: return

        if (jukebox.isPlaying) return

        val item = event.item?.takeIf { it.isCustomDisc(plugin) } ?: return

        val voicePlayer = event.player.asVoicePlayer(plugin.voiceServer) ?: return

        if (!voicePlayer.instance.hasPermission("pv.addon.discs.play")) return

        val identifier = item.itemMeta
            ?.persistentDataContainer
            ?.let {
                it.get(plugin.identifierKey, PersistentDataType.STRING) ?:
                it.get(plugin.oldIdentifierKey, PersistentDataType.STRING)
            }
            ?: return

        voicePlayer.instance.sendActionBar(
            MinecraftTextComponent.translatable("pv.addon.discs.actionbar.loading")
                .withStyle(MinecraftTextStyle.YELLOW)
        )

        jobByBlock[block]?.cancel()
        jobByBlock[block] = playTrack(identifier, voicePlayer, block, item)
    }

    private fun playTrack(
        identifier: String,
        voicePlayer: VoicePlayer,
        block: Block,
        item: ItemStack
    ): Job = scope.launch {

        val track = try {
            plugin.audioPlayerManager.getTrack(identifier).await()
        } catch (e: Exception) {
            voicePlayer.instance.sendActionBar(
                MinecraftTextComponent.translatable("pv.addon.discs.actionbar.track_not_found", e.message)
                    .withStyle(MinecraftTextStyle.RED)
            )
            suspendSync(plugin) { block.asJukebox()?.eject() }
            return@launch
        }

        val trackName = item.itemMeta
            ?.lore()
            ?.getOrNull(0)
            ?.let { it as? TextComponent }
            ?.content()
            ?: track.info.title

        val world = plugin.voiceServer.minecraftServer.getWorld(block.world)

        val pos = ServerPos3d(
            world,
            block.x.toDouble() + 0.5,
            block.y.toDouble() + 1.5,
            block.z.toDouble() + 0.5
        )

        val source = plugin.sourceLine.createStaticSource(pos, true)

        source.setName(trackName)

        val distance = when (plugin.addonConfig.distance.enableBeaconLikeDistance) {
            true -> plugin.addonConfig.distance.beaconLikeDistanceList[getBeaconLevel(block)]
            false -> plugin.addonConfig.distance.jukeboxDistance
        }

        val actionbarMessage = MinecraftTextComponent.translatable(
            "pv.addon.discs.actionbar.playing", trackName
        )

        voicePlayer.visualizeDistance(
            pos.toPosition(),
            distance.toInt(),
            0xf1c40f
        )

        suspendSync(plugin) { block.world.getNearbyPlayers(block.location, distance.toDouble()) }
            .map { it.asVoicePlayer(plugin.voiceServer) }
            .forEach { it?.sendAnimatedActionBar(actionbarMessage) }

        val job = plugin.audioPlayerManager.startTrackJob(track, source, distance)
        try {
            job.join()
        } finally {
            job.cancelAndJoin()
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    fun onDiskEject(event: PlayerInteractEvent) {
        if (event.action != Action.RIGHT_CLICK_BLOCK) return

        if (
            (event.player.inventory.itemInMainHand.type != Material.AIR ||
            event.player.inventory.itemInOffHand.type != Material.AIR) &&
            event.player.isSneaking
        ) return

        val block = event.clickedBlock ?: return

        block.asJukebox()?.takeIf { it.isPlaying } ?: return

        jobByBlock[block]?.cancel()
    }

    @EventHandler
    fun onJukeboxBreak(event: BlockBreakEvent) {
        event.block
            .takeIf { it.isJukebox() }
            ?.also {
                it.asJukebox()?.stopPlaying();
            }
            ?.let { jobByBlock[it] }
            ?.cancel()
    }

    @EventHandler
    fun onJukeboxExplode(event: EntityExplodeEvent) {
        event.blockList()
            .filter { it.isJukebox() }
            .forEach { jobByBlock[it]?.cancel() }
    }

    private fun getBeaconLevel(block: Block) = (1 until plugin.addonConfig.distance.beaconLikeDistanceList.size).takeWhile { level ->
        (-level..level).all { xOffset ->
            (-level..level).all { zOffset ->
                Location(
                    block.world,
                    (block.x + xOffset).toDouble(),
                    (block.y - level).toDouble(),
                    (block.z + zOffset).toDouble()
                ).block.isBeaconBaseBlock()
            }
        }
    }.count()
}
