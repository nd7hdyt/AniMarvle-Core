package com.animarvle.domain

import com.animarvle.sourceapi.Audio
import com.animarvle.sourceapi.Choice
import com.animarvle.sourceapi.EpisodeOptions
import com.animarvle.sourceapi.MediaSource
import com.animarvle.sourceapi.ResolvedMedia
import com.animarvle.sourceapi.ServerOption
import com.animarvle.sourceapi.SourceEpisode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

/** What the user asked for; persisted per metaId (ARCH §7.3). */
data class PlaybackSelection(
    val audio: Audio,
    val serverKey: String? = null,
    val qualityId: String? = null,
)

sealed interface ResolveEvent {
    /** A server produced playable streams. [resumedAtMs] is the position playback should start at. */
    data class Resolved(
        val serverKey: String,
        val media: ResolvedMedia,
        val resumedAtMs: Long,
    ) : ResolveEvent

    /** The current server failed and the resolver moved on. [cause] is for the log/toast. */
    data class Fallback(val from: String, val to: String?, val cause: String) : ResolveEvent

    data class AllServersFailed(val tried: List<String>, val cause: String?) : ResolveEvent

    /** The requested audio is not offered by this source. Never auto-switch silently (ARCH §6). */
    data class AudioUnavailable(val requested: Audio, val available: Set<Audio>, val message: String) :
        ResolveEvent
}

/**
 * Audio + server resolution (ARCH §7.3).
 *
 * Order: per-title last-used -> user priority -> plugin order. A manually picked server is
 * tried first but never blocks the fallback chain.
 */
class StreamResolver(
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    suspend fun options(source: MediaSource, episode: SourceEpisode): EpisodeOptions =
        withTimeout(timeoutMs) { source.options(episode) }

    /**
     * Resolves [episode] to playable streams.
     *
     * @param lastUsedServer server that worked last time for this metaId (tried first).
     * @param userPriority user-defined server order from Settings.
     * @param startAfterServer for mid-playback failures (ARCH §7.3): resume the loop after
     *   the server that just died.
     * @param resumedAtMs position playback should seek to once a server succeeds.
     */
    fun resolve(
        source: MediaSource,
        episode: SourceEpisode,
        requested: PlaybackSelection,
        lastUsedServer: String? = null,
        userPriority: List<String> = emptyList(),
        startAfterServer: String? = null,
        failedServers: Set<String> = emptySet(),
        resumedAtMs: Long = 0L,
    ): Flow<ResolveEvent> = flow {
        val options = withTimeout(timeoutMs) { source.options(episode) }
        val servers = options.byAudio[requested.audio]
            ?: (if (requested.audio == Audio.SUB) options.byAudio[Audio.RAW] else null)
        if (servers.isNullOrEmpty()) {
            emit(
                ResolveEvent.AudioUnavailable(
                    requested = requested.audio,
                    available = options.byAudio.keys,
                    message = "${requested.audio} is not available from this source",
                ),
            )
            return@flow
        }

        val order = orderServers(servers, lastUsedServer, userPriority, startAfterServer, requested.serverKey, failedServers)
        if (order.isEmpty()) {
            emit(ResolveEvent.AllServersFailed(servers.map { it.key }, "All servers failed"))
            return@flow
        }
        var previous: String? = null
        var lastCause: String? = null

        for (server in order) {
            val prev = previous
            if (prev != null) {
                emit(ResolveEvent.Fallback(prev, server.key, lastCause ?: "server failed"))
            }
            try {
                val media = withTimeout(timeoutMs) {
                    source.resolve(episode, Choice(requested.audio, server.key))
                }
                if (media.streams.isEmpty()) {
                    throw EmptyStreamException(server.key)
                }
                emit(ResolveEvent.Resolved(server.key, media, resumedAtMs))
                return@flow
            } catch (t: TimeoutCancellationException) {
                lastCause = "timed out after ${timeoutMs}ms"
            } catch (c: CancellationException) {
                throw c
            } catch (e: Exception) {
                lastCause = e.message ?: e::class.java.simpleName
            }
            previous = server.key
        }

        emit(ResolveEvent.AllServersFailed(order.map { it.key }, lastCause))
    }

    /** Per-title last-used -> user priority -> plugin order; manual pick forced to the front. */
    internal fun orderServers(
        servers: List<ServerOption>,
        lastUsedServer: String?,
        userPriority: List<String>,
        startAfterServer: String?,
        manualPick: String?,
        failedServers: Set<String> = emptySet(),
    ): List<ServerOption> {
        val byKey = servers.associateBy { it.key }
        val candidateOrder = mutableListOf<ServerOption>()

        if (startAfterServer != null) {
            // Mid-playback fallback: continue after the server that just failed.
            val index = servers.indexOfFirst { it.key == startAfterServer }
            if (index >= 0) {
                candidateOrder.addAll(servers.drop(index + 1))
                candidateOrder.addAll(servers.take(index))
            } else {
                candidateOrder.addAll(servers)
            }
        } else {
            manualPick?.let { byKey[it] }?.let { candidateOrder.add(it) }
            lastUsedServer?.let { byKey[it] }?.let { candidateOrder.add(it) }
            userPriority.forEach { key -> byKey[key]?.let { candidateOrder.add(it) } }
            candidateOrder.addAll(servers)
        }

        val result = LinkedHashMap<String, ServerOption>()
        candidateOrder
            .filterNot { it.key in failedServers || it.key == startAfterServer }
            .forEach { result.putIfAbsent(it.key, it) }

        return result.values.toList()
    }

    private class EmptyStreamException(serverKey: String) :
        Exception("server $serverKey returned no streams")

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
    }
}