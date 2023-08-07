package su.plo.voice.discs

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager
import com.sedmelluq.discord.lavaplayer.player.DefaultAudioPlayerManager
import com.sedmelluq.discord.lavaplayer.source.http.HttpAudioSourceManager
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException
import com.sedmelluq.discord.lavaplayer.track.*
import kotlinx.coroutines.*
import su.plo.voice.api.encryption.Encryption
import su.plo.voice.api.server.audio.source.ServerStaticSource
import su.plo.voice.proto.packets.tcp.clientbound.SourceAudioEndPacket
import su.plo.voice.proto.packets.udp.clientbound.SourceAudioPacket
import java.net.URI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class PlasmoAudioPlayerManager(
    private val plugin: DiscsPlugin
) {
    private val lavaPlayerManager: AudioPlayerManager = DefaultAudioPlayerManager()
    private val scope = CoroutineScope(Dispatchers.Default)
    private val encrypt: Encryption
        get() = plugin.voiceServer.defaultEncryption

    init {
        registerSources()
    }

    fun startTrackJob(track: AudioTrack, source: ServerStaticSource, distance: Short) = scope.launch {

        val player = lavaPlayerManager.createPlayer()

        player.playTrack(track)

        var i = 0L
        var start = 0L

        try { while(isActive) {

            if (track.state == AudioTrackState.FINISHED) break

            val frame = player.provide(5L, TimeUnit.MILLISECONDS) ?: continue

            val packet = SourceAudioPacket(
                i++,
                source.state.toByte(),
                encrypt.encrypt(frame.data),
                source.id,
                distance
            )

            source.sendAudioPacket(packet, distance)

            if (start == 0L) start = System.currentTimeMillis()

            val wait = (start + frame.timecode) - System.currentTimeMillis()

            if (wait <= 0) continue else delay(wait)

        }} finally { withContext(NonCancellable) {

            player.destroy()

            source.sendPacket(SourceAudioEndPacket(
                source.id,
                i++
            ), distance)

            plugin.sourceLine.removeSource(source)
        }}
    }

    val noMatchesException = FriendlyException(
        "No matches",
        FriendlyException.Severity.COMMON,
        Exception("No matches")
    )

    fun getTrack(identifier: String): CompletableFuture<AudioTrack> {

        val future = CompletableFuture<AudioTrack>()
        lavaPlayerManager.loadItem(identifier, object: AudioLoadResultHandler {
            override fun trackLoaded(track: AudioTrack) {
                future.complete(track)
            }
            override fun playlistLoaded(playlist: AudioPlaylist) {
                if(playlist.tracks.isNotEmpty())
                    future.complete(playlist.tracks[0])
                else
                    future.completeExceptionally(noMatchesException)
            }
            override fun loadFailed(exception: FriendlyException) {
                future.completeExceptionally(exception)
            }
            override fun noMatches() {
                future.completeExceptionally(noMatchesException)
            }
        })
        return future.orTimeout(10,TimeUnit.SECONDS)
    }

    fun getPlaylist(identifier: String): CompletableFuture<AudioPlaylist> {

        val future = CompletableFuture<AudioPlaylist>()

        lavaPlayerManager.loadItem(identifier, object : AudioLoadResultHandler {
            override fun trackLoaded(track: AudioTrack) {
                plugin.logger.warning("Track loaded no matches")
                future.completeExceptionally(noMatchesException)
            }
            override fun playlistLoaded(playlist: AudioPlaylist) {
                future.complete(playlist)
            }
            override fun loadFailed(exception: FriendlyException) {
                plugin.logger.warning("load failed")
                future.completeExceptionally(exception)
            }
            override fun noMatches() {
                plugin.logger.warning("no matches")
                future.completeExceptionally(noMatchesException)
            }
        })

        return future
    }

    private fun registerSources() {
//        lavaPlayerManager.registerSourceManager(YoutubeAudioSourceManager(
//            true,
//            plugin.addonConfig.youtube.email.ifBlank { null },
//            plugin.addonConfig.youtube.password.ifBlank { null }
//        ))
        try {
            lavaPlayerManager.registerSourceManager(CustomHttpAudioSourceManager(plugin))
        }catch (e: Throwable) {
            plugin.logger.warning("CustomHttpAudioSourceManager not registered")
        }
    }

    class CustomHttpAudioSourceManager(private val plugin: DiscsPlugin) : HttpAudioSourceManager() {
        override fun loadItem(manager: AudioPlayerManager?, reference: AudioReference?): AudioItem? {
            if (reference != null && plugin.addonConfig.httpSource.whitelistEnabled) {
                val identifier = reference.identifier
                val host = runCatching { URI(identifier) }.getOrNull()?.host ?: return null
                if (!plugin.addonConfig.httpSource.whitelist.any { host.endsWith(it) }) return null
            }
            return super.loadItem(manager, reference)
        }
    }
}
