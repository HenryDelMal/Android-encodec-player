package com.henry.encodec.player

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.henry.encodec.decoder.ExecuTorchEncodecDecoder
import com.henry.encodec.ecdc.EcdcHeader
import com.henry.encodec.ecdc.EcdcReader
import com.henry.encodec.ecdc.EncodecVariant
import com.henry.encodec.playback.EcdcPlaybackSession
import com.henry.encodec.playback.LiveEcdcPlaybackSession
import com.henry.encodec.playback.LiveEcdcSegment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import java.io.File
import java.io.InputStream
import java.io.ByteArrayInputStream
import java.io.SequenceInputStream
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.ConcurrentHashMap

data class PlaylistItem(
    val uri: Uri,
    val title: String,
    val header: EcdcHeader,
)

data class LiveUiState(
    val manifestUrl: String,
    val title: String,
    val status: String = "Connecting…",
    val sequence: Long? = null,
    val codebooks: Int? = null,
    val bandwidthKbps: Double? = null,
    val bufferedSegments: Int = 0,
)

enum class RepeatMode {
    OFF,
    TRACK,
    LIST;

    fun next(): RepeatMode = entries[(ordinal + 1) % entries.size]
}

data class PlayerState(
    val playlist: List<PlaylistItem> = emptyList(),
    val currentIndex: Int = -1,
    val playing: Boolean = false,
    val paused: Boolean = false,
    val progress: Float = 0f,
    val shuffle: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val addingUrl: Boolean = false,
    val live: LiveUiState? = null,
    val error: String? = null,
) {
    val current: PlaylistItem? get() = playlist.getOrNull(currentIndex)
}

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    private val playlistStore = PlaylistStore(application)
    private val mutableState = MutableStateFlow(playlistStore.load())
    val state = mutableState.asStateFlow()
    private var playbackJob: Job? = null
    @Volatile private var session: EcdcPlaybackSession? = null
    @Volatile private var liveSession: LiveEcdcPlaybackSession? = null
    private var playbackGeneration = 0L
    private var requestedStartSample = 0L
    private val shufflePlayedUris = mutableSetOf<String>()
    private val decoderMutex = Mutex()
    private val remoteHeaderPrefixes = ConcurrentHashMap<String, ByteArray>()
    private val mediaSession = MediaSession(application, "EnCodec Player").apply {
        setCallback(object : MediaSession.Callback() {
            override fun onPlay() = play()
            override fun onPause() = pause()
            override fun onStop() = stop()
            override fun onSkipToNext() = next()
            override fun onSkipToPrevious() = previous()
            override fun onSeekTo(pos: Long) = seekToMillis(pos)
            override fun onCustomAction(action: String, extras: android.os.Bundle?) {
                when (action) {
                    MEDIA_ACTION_TOGGLE_SHUFFLE -> toggleShuffle()
                    MEDIA_ACTION_CYCLE_REPEAT -> cycleRepeatMode()
                    MEDIA_ACTION_JUMP_TO_LIVE -> jumpToLive()
                }
            }
        })
        isActive = true
    }

    init {
        removeObsoleteDecoderModels()
        activeInstance = WeakReference(this)
        viewModelScope.launch {
            state.collect(::publishMediaState)
        }
    }

    fun addToPlaylist(uris: List<Uri>) {
        val resolver = getApplication<Application>().contentResolver
        val existing = mutableState.value.playlist.map { it.uri }.toSet()
        val accepted = mutableListOf<PlaylistItem>()
        val errors = mutableListOf<String>()

        uris.filterNot(existing::contains).forEach { uri ->
            runCatching {
                val header = resolver.openInputStream(uri)!!.use(EcdcReader::inspect)
                validateHeader(header)
                runCatching {
                    resolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION,
                    )
                }
                PlaylistItem(uri, displayName(uri), header)
            }.onSuccess(accepted::add).onFailure {
                errors += "${displayName(uri)}: ${it.message}"
            }
        }

        val old = mutableState.value
        val combined = old.playlist + accepted
        val selectingFirstTrack = old.currentIndex < 0 && combined.isNotEmpty()
        if (selectingFirstTrack) requestedStartSample = 0
        mutableState.value = old.copy(
            playlist = combined,
            currentIndex = if (selectingFirstTrack) 0 else old.currentIndex,
            progress = if (selectingFirstTrack) 0f else old.progress,
            error = errors.takeIf { it.isNotEmpty() }?.joinToString("\n"),
        )
        persistPlaylist()
        if (selectingFirstTrack) startPlayback()
    }

    fun addUrl(rawUrl: String) {
        if (mutableState.value.addingUrl) return
        val text = rawUrl.trim()
        val uri = runCatching { Uri.parse(text) }.getOrNull()
        if (
            uri == null ||
            !uri.scheme.equals("https", ignoreCase = true) ||
            uri.host.isNullOrBlank()
        ) {
            mutableState.value = mutableState.value.copy(error = "Enter a valid HTTPS URL")
            return
        }
        if (mutableState.value.playlist.any { it.uri == uri }) {
            mutableState.value = mutableState.value.copy(error = "That URL is already in the playlist")
            return
        }

        mutableState.value = mutableState.value.copy(addingUrl = true, error = null)
        viewModelScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val prefix = HttpsStreams.readPrefix(
                        uri.toString(), STATIC_HEADER_PREFIX_BYTES,
                    ).also { remoteHeaderPrefixes[uri.toString()] = it }
                    ByteArrayInputStream(prefix).use { input ->
                        val header = EcdcReader.inspect(input)
                        validateHeader(header)
                        PlaylistItem(uri, remoteDisplayName(uri), header)
                    }
                }
            }
            val old = mutableState.value
            result.onSuccess { item ->
                if (old.playlist.any { it.uri == item.uri }) {
                    mutableState.value = old.copy(addingUrl = false)
                } else {
                    val combined = old.playlist + item
                    val selectingFirstTrack = old.currentIndex < 0
                    if (selectingFirstTrack) requestedStartSample = 0
                    mutableState.value = old.copy(
                        playlist = combined,
                        currentIndex = if (selectingFirstTrack) 0 else old.currentIndex,
                        progress = if (selectingFirstTrack) 0f else old.progress,
                        addingUrl = false,
                        error = null,
                    )
                    persistPlaylist()
                    if (selectingFirstTrack) startPlayback()
                }
            }.onFailure { error ->
                mutableState.value = old.copy(
                    addingUrl = false,
                    error = "Could not add URL: ${error.message ?: "network error"}",
                )
            }
        }
    }

    fun lastLiveUrl(): String = playlistStore.loadLastLiveUrl()

    fun openLive(rawUrl: String) {
        val text = rawUrl.trim()
        val uri = runCatching { Uri.parse(text) }.getOrNull()
        if (uri == null ||
            !(uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) ||
            uri.host.isNullOrBlank()
        ) {
            mutableState.value = mutableState.value.copy(
                error = "Enter a valid HTTP or HTTPS live manifest URL",
            )
            return
        }
        requestedStartSample = 0
        stopInternal(resetProgress = true)
        playlistStore.saveLastLiveUrl(uri.toString())
        mutableState.value = mutableState.value.copy(
            live = LiveUiState(uri.toString(), Uri.decode(uri.host ?: "EnCodec live")),
            playing = false,
            paused = false,
            progress = 0f,
            error = null,
        )
        startLivePlayback()
    }

    fun disconnectLive() {
        if (mutableState.value.live == null) return
        stopInternal(resetProgress = true)
        mutableState.value = mutableState.value.copy(live = null, error = null)
        stopPlaybackService()
    }

    fun reconnectLive() = restartLive("Reconnecting…")

    fun jumpToLive() = restartLive("Jumping to live edge…")

    private fun restartLive(status: String) {
        if (mutableState.value.live == null) return
        stopInternal(resetProgress = false)
        mutableState.value = mutableState.value.copy(
            live = mutableState.value.live?.copy(status = status, sequence = null),
            error = null,
        )
        startLivePlayback()
    }

    fun selectTrack(index: Int) {
        if (index !in mutableState.value.playlist.indices) return
        val wasActive = mutableState.value.playing
        requestedStartSample = 0
        stopInternal(resetProgress = true)
        mutableState.value = mutableState.value.copy(currentIndex = index, live = null)
        persistPlaylist()
        if (wasActive) startPlayback()
    }

    fun playPause() {
        val snapshot = mutableState.value
        when {
            !snapshot.playing && snapshot.live != null -> startLivePlayback()
            !snapshot.playing -> startPlayback()
            snapshot.paused -> play()
            else -> pause()
        }
    }

    fun play() {
        val snapshot = mutableState.value
        when {
            !snapshot.playing && snapshot.live != null -> startLivePlayback()
            !snapshot.playing -> startPlayback()
            snapshot.paused -> {
                session?.resume()
                liveSession?.resume()
                mutableState.value = snapshot.copy(paused = false)
            }
        }
    }

    fun pause() {
        val snapshot = mutableState.value
        if (snapshot.playing && !snapshot.paused) {
            session?.pause()
            liveSession?.pause()
            mutableState.value = snapshot.copy(paused = true)
        }
    }

    fun stop() {
        val wasLive = mutableState.value.live != null
        requestedStartSample = 0
        stopInternal(resetProgress = true)
        if (wasLive) {
            mutableState.value = mutableState.value.copy(
                live = mutableState.value.live?.copy(status = "Stopped"),
            )
        }
        stopPlaybackService()
    }

    fun next() {
        val snapshot = mutableState.value
        if (snapshot.live != null) return
        if (snapshot.playlist.isEmpty()) return
        val nextIndex = when {
            snapshot.shuffle -> nextShuffleIndex(snapshot, restartCycle = true)
            snapshot.currentIndex < snapshot.playlist.lastIndex -> snapshot.currentIndex + 1
            snapshot.repeatMode == RepeatMode.LIST -> 0
            else -> null
        } ?: return
        requestedStartSample = 0
        moveAndPlay(nextIndex)
    }

    fun previous() {
        val snapshot = mutableState.value
        if (snapshot.live != null) return
        if (snapshot.playlist.isEmpty()) return
        val positionSeconds = snapshot.current?.let {
            it.header.audioLengthSamples * snapshot.progress / it.header.variant.sampleRate
        } ?: 0f
        if (positionSeconds >= 3f) {
            seekToFraction(0f)
            return
        }
        requestedStartSample = 0
        val previousIndex = when {
            snapshot.currentIndex > 0 -> snapshot.currentIndex - 1
            snapshot.repeatMode == RepeatMode.LIST -> snapshot.playlist.lastIndex
            else -> 0
        }
        moveAndPlay(previousIndex)
    }

    fun toggleShuffle() {
        val snapshot = mutableState.value
        if (snapshot.live != null) return
        val enabled = !snapshot.shuffle
        shufflePlayedUris.clear()
        if (enabled) snapshot.current?.let { shufflePlayedUris += it.uri.toString() }
        mutableState.value = snapshot.copy(shuffle = enabled)
        persistPlaylist()
    }

    fun cycleRepeatMode() {
        val snapshot = mutableState.value
        if (snapshot.live != null) return
        mutableState.value = snapshot.copy(repeatMode = snapshot.repeatMode.next())
        persistPlaylist()
    }

    fun seekToFraction(fraction: Float) {
        val snapshot = mutableState.value
        if (snapshot.live != null) return
        val current = snapshot.current ?: return
        val safeFraction = fraction.coerceIn(0f, 0.999999f)
        requestedStartSample = (current.header.audioLengthSamples * safeFraction).toLong()
        mutableState.value = snapshot.copy(progress = safeFraction)
        if (snapshot.playing) startPlayback(startPaused = snapshot.paused)
    }

    fun seekToMillis(positionMillis: Long) {
        val current = mutableState.value.current ?: return
        val durationMillis = current.header.audioLengthSamples * 1_000L /
            current.header.variant.sampleRate
        if (durationMillis > 0) {
            seekToFraction(positionMillis.toFloat() / durationMillis)
        }
    }

    fun clearPlaylist() {
        val snapshot = mutableState.value
        snapshot.playlist.forEach(::releaseLocalPermission)
        shufflePlayedUris.clear()
        requestedStartSample = 0
        stopInternal(resetProgress = true)
        stopPlaybackService()
        mutableState.value = PlayerState(
            shuffle = snapshot.shuffle,
            repeatMode = snapshot.repeatMode,
        )
        persistPlaylist()
    }

    fun removeTrack(index: Int) {
        val snapshot = mutableState.value
        val removed = snapshot.playlist.getOrNull(index) ?: return
        shufflePlayedUris -= removed.uri.toString()
        val wasPlaying = snapshot.playing
        val wasPaused = snapshot.paused
        val currentUri = snapshot.current?.uri
        val removedCurrent = index == snapshot.currentIndex

        if (wasPlaying) stopInternal(resetProgress = removedCurrent)
        val remaining = snapshot.playlist.toMutableList().apply { removeAt(index) }
        val newIndex = when {
            remaining.isEmpty() -> -1
            removedCurrent -> index.coerceAtMost(remaining.lastIndex)
            else -> remaining.indexOfFirst { it.uri == currentUri }.takeIf { it >= 0 } ?: 0
        }
        val newProgress = if (removedCurrent || remaining.isEmpty()) 0f else snapshot.progress
        requestedStartSample = remaining.getOrNull(newIndex)?.let {
            (it.header.audioLengthSamples * newProgress).toLong()
        } ?: 0L
        mutableState.value = mutableState.value.copy(
            playlist = remaining,
            currentIndex = newIndex,
            playing = false,
            paused = false,
            progress = newProgress,
        )
        releaseLocalPermission(removed)
        persistPlaylist()
        if (wasPlaying && newIndex >= 0) {
            startPlayback(startPaused = wasPaused)
        } else if (newIndex < 0) {
            stopPlaybackService()
        }
    }

    private fun moveAndPlay(index: Int) {
        stopInternal(resetProgress = true)
        mutableState.value = mutableState.value.copy(currentIndex = index, live = null)
        persistPlaylist()
        startPlayback()
    }

    private fun startLivePlayback() {
        val live = mutableState.value.live ?: return
        stopInternal(resetProgress = false)
        ensurePlaybackService()
        val generation = playbackGeneration
        playbackJob = viewModelScope.launch {
            mutableState.value = mutableState.value.copy(
                playing = true,
                paused = false,
                error = null,
                live = mutableState.value.live?.copy(status = "Loading decoder…"),
            )
            try {
                decoderMutex.withLock {
                    coroutineScope {
                            val source = LiveStreamSource(live.manifestUrl)
                            val queue = Channel<DownloadedLiveSegment>(LIVE_PREFETCH_CAPACITY)
                            val buffered = AtomicInteger(0)
                            fun publishBuffer(status: String? = null) {
                                if (playbackGeneration != generation) return
                                val depth = buffered.get()
                                mutableState.value = mutableState.value.copy(
                                    live = mutableState.value.live?.copy(
                                        status = status ?: if (depth > 0) "LIVE" else "Rebuffering…",
                                        bufferedSegments = depth,
                                    ),
                                )
                            }
                            val producer = launch(Dispatchers.IO) {
                                try {
                                    while (isActive) {
                                        val downloaded = source.nextSegment { status ->
                                            if (buffered.get() == 0) publishBuffer(status)
                                        }
                                        buffered.incrementAndGet()
                                        try {
                                            queue.send(downloaded)
                                        } catch (error: Throwable) {
                                            buffered.decrementAndGet()
                                            throw error
                                        }
                                        publishBuffer()
                                    }
                                } finally {
                                    queue.close()
                                }
                            }
                            try {
                                publishBuffer("Buffering live audio…")
                                val config = decoderConfig(EncodecVariant.STEREO_48_KHZ)
                                ExecuTorchEncodecDecoder(
                                    copyAssetOnce(config.assetName),
                                    EncodecVariant.STEREO_48_KHZ,
                                    maxCodebooks = config.maxCodebooks,
                                    modelTimeSteps = config.timeSteps,
                                ).use { decoder ->
                                    while (buffered.get() < LIVE_STARTUP_SEGMENTS) {
                                        // The producer has been filling the queue
                                        // concurrently with decoder initialization.
                                        kotlinx.coroutines.delay(25)
                                        if (producer.isCompleted) break
                                    }
                                    val newSession = LiveEcdcPlaybackSession(decoder)
                                    liveSession = newSession
                                    newSession.play(
                                        nextSegment = {
                                            if (buffered.get() == 0) publishBuffer("Rebuffering…")
                                            val downloaded = queue.receive()
                                            buffered.decrementAndGet()
                                            publishBuffer("Decoding segment ${downloaded.sequence}…")
                                            mutableState.value = mutableState.value.copy(
                                                live = mutableState.value.live?.copy(
                                                    codebooks = downloaded.codebooks,
                                                    bandwidthKbps = downloaded.bandwidthKbps,
                                                ),
                                            )
                                            LiveEcdcSegment(
                                                ByteArrayInputStream(downloaded.bytes),
                                                downloaded.sequence,
                                                downloaded.discontinuity,
                                            )
                                        },
                                        onSegmentPlaying = { sequence ->
                                            if (playbackGeneration == generation) {
                                                mutableState.value = mutableState.value.copy(
                                                    live = mutableState.value.live?.copy(
                                                        status = "LIVE",
                                                        sequence = sequence,
                                                        bufferedSegments = buffered.get(),
                                                    ),
                                                )
                                            }
                                        },
                                    )
                                }
                            } finally {
                                producer.cancelAndJoin()
                                queue.cancel()
                            }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (playbackGeneration == generation) {
                    mutableState.value = mutableState.value.copy(
                        error = "Live playback failed: ${error.message ?: "unknown error"}",
                        live = mutableState.value.live?.copy(status = "Disconnected"),
                    )
                }
            } finally {
                if (playbackGeneration == generation) {
                    liveSession = null
                    mutableState.value = mutableState.value.copy(playing = false, paused = false)
                    stopPlaybackService()
                }
            }
        }
    }

    private fun startPlayback(startPaused: Boolean = false) {
        val selected = mutableState.value.current ?: return
        if (mutableState.value.live != null) {
            mutableState.value = mutableState.value.copy(live = null)
        }
        if (mutableState.value.shuffle) shufflePlayedUris += selected.uri.toString()
        if (requestedStartSample >= selected.header.audioLengthSamples - 1) {
            requestedStartSample = 0
        }
        stopInternal(resetProgress = false)
        ensurePlaybackService()
        val generation = playbackGeneration
        val initialStartSample = requestedStartSample
        playbackJob = viewModelScope.launch {
            val playbackScope = this
            val initial = mutableState.value
            val initialDuration = initial.current?.header?.audioLengthSamples ?: 1L
            mutableState.value = mutableState.value.copy(
                playing = true,
                paused = startPaused,
                progress = (initialStartSample.toDouble() / initialDuration)
                    .toFloat().coerceIn(0f, 1f),
                error = null,
            )
            try {
                decoderMutex.withLock {
                    var firstIteration = true
                    while (isActive) {
                        val snapshot = mutableState.value
                        val item = snapshot.current ?: break
                        val trackStartSample = if (firstIteration) {
                            initialStartSample
                        } else {
                            0L
                        }
                        firstIteration = false
                        val config = decoderConfig(item.header.variant)
                        val preparedRemote = if (item.uri.scheme.equals("https", true)) {
                            playbackScope.async(Dispatchers.IO) {
                                prepareRemoteInput(
                                    playbackScope, item.uri, item.header, trackStartSample,
                                )
                            }
                        } else {
                            null
                        }
                        ExecuTorchEncodecDecoder(
                            copyAssetOnce(config.assetName),
                            item.header.variant,
                            maxCodebooks = config.maxCodebooks,
                            modelTimeSteps = config.timeSteps,
                        ).use { decoder ->
                            val newSession = EcdcPlaybackSession(decoder)
                            session = newSession
                            if (mutableState.value.paused) newSession.pause()
                            withPlaybackInput(item.uri, preparedRemote) {
                                    input, initialFrameIndex ->
                                newSession.play(
                                    input = input,
                                    startSample = trackStartSample,
                                    initialFrameIndex = initialFrameIndex,
                                    onProgress = { progress ->
                                        if (playbackGeneration == generation) {
                                            requestedStartSample =
                                                (item.header.audioLengthSamples * progress).toLong()
                                            mutableState.value = mutableState.value.copy(progress = progress)
                                        }
                                    },
                                )
                            }
                        }
                        if (playbackGeneration != generation) break
                        val nextIndex = nextIndexAfterCompletion(mutableState.value) ?: break
                        requestedStartSample = 0
                        mutableState.value = mutableState.value.copy(
                            currentIndex = nextIndex,
                            progress = 0f,
                        )
                        persistPlaylist()
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                if (playbackGeneration == generation) {
                    mutableState.value = mutableState.value.copy(
                        error = error.message ?: "Playback failed",
                    )
                }
            } finally {
                if (playbackGeneration == generation) {
                    session = null
                    mutableState.value = mutableState.value.copy(
                        playing = false,
                        paused = false,
                    )
                    stopPlaybackService()
                }
            }
        }
    }

    private fun stopInternal(resetProgress: Boolean) {
        playbackGeneration++
        session?.stop()
        liveSession?.stop()
        playbackJob?.cancel()
        playbackJob = null
        session = null
        liveSession = null
        val snapshot = mutableState.value
        mutableState.value = snapshot.copy(
            playing = false,
            paused = false,
            progress = if (resetProgress) 0f else snapshot.progress,
        )
    }

    private fun publishMediaState(state: PlayerState) {
        val current = state.current
        val live = state.live
        val durationMillis = if (live != null) 0L else current?.let {
            it.header.audioLengthSamples * 1_000L / it.header.variant.sampleRate
        } ?: 0L
        val positionMillis = if (live != null) 0L else (durationMillis * state.progress).toLong()
        val playbackState = when {
            state.playing && !state.paused -> PlaybackState.STATE_PLAYING
            state.playing -> PlaybackState.STATE_PAUSED
            else -> PlaybackState.STATE_STOPPED
        }
        val standardActions = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or
            PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP
        val finiteActions = PlaybackState.ACTION_SKIP_TO_NEXT or
            PlaybackState.ACTION_SKIP_TO_PREVIOUS or PlaybackState.ACTION_SEEK_TO
        val stateBuilder = PlaybackState.Builder()
            .setActions(standardActions or if (live == null) finiteActions else 0L)
        if (live == null) {
            stateBuilder
                .addCustomAction(
                    PlaybackState.CustomAction.Builder(
                        MEDIA_ACTION_TOGGLE_SHUFFLE,
                        if (state.shuffle) "Shuffle on" else "Shuffle off",
                        android.R.drawable.ic_menu_sort_by_size,
                    ).build(),
                )
                .addCustomAction(
                    PlaybackState.CustomAction.Builder(
                        MEDIA_ACTION_CYCLE_REPEAT,
                        when (state.repeatMode) {
                            RepeatMode.OFF -> "Loop off"
                            RepeatMode.TRACK -> "Loop track"
                            RepeatMode.LIST -> "Loop list"
                        },
                        android.R.drawable.ic_menu_revert,
                    ).build(),
                )
        } else {
            stateBuilder.addCustomAction(
                PlaybackState.CustomAction.Builder(
                    MEDIA_ACTION_JUMP_TO_LIVE,
                    "Jump to live",
                    android.R.drawable.ic_media_next,
                ).build(),
            )
        }
        mediaSession.setPlaybackState(
            stateBuilder
                .setState(
                    playbackState,
                    positionMillis,
                    if (playbackState == PlaybackState.STATE_PLAYING) 1f else 0f,
                )
                .build(),
        )
        mediaSession.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, live?.title ?: current?.title ?: "EnCodec Player")
                .putString(
                    MediaMetadata.METADATA_KEY_ARTIST,
                    if (live != null) "LIVE EnCodec" else "EnCodec audio",
                )
                .putLong(MediaMetadata.METADATA_KEY_DURATION, durationMillis)
                .build(),
        )
        mediaSession.isActive = current != null || live != null
        publishMediaNotification(state)
    }

    private fun publishMediaNotification(state: PlayerState) {
        val manager = getApplication<Application>()
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val current = state.current
        val live = state.live
        if (!state.playing || (current == null && live == null)) {
            manager.cancel(MEDIA_NOTIFICATION_ID)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    MEDIA_CHANNEL_ID,
                    "Playback",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = "EnCodec playback controls" },
            )
        }
        val playPauseTitle = if (state.paused) "Play" else "Pause"
        val playPauseIcon = if (state.paused) {
            android.R.drawable.ic_media_play
        } else {
            android.R.drawable.ic_media_pause
        }
        val notificationBuilder = Notification.Builder(getApplication(), MEDIA_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(live?.title ?: current?.title ?: "EnCodec Player")
            .setContentText(live?.let { "LIVE • ${it.status}" } ?: "EnCodec audio")
            .setContentIntent(mediaIntent(MainActivity.ACTION_OPEN, 0))
            .setOnlyAlertOnce(true)
            .setOngoing(!state.paused)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        if (live != null) {
            notificationBuilder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Stop",
                mediaIntent(MainActivity.ACTION_STOP, 1),
            )
        } else {
            notificationBuilder.addAction(
                android.R.drawable.ic_media_previous,
                "Previous",
                mediaIntent(MainActivity.ACTION_PREVIOUS, 1),
            )
        }
        notificationBuilder
            .addAction(
                playPauseIcon,
                playPauseTitle,
                mediaIntent(MainActivity.ACTION_PLAY_PAUSE, 2),
            )
            .addAction(
                android.R.drawable.ic_media_next,
                if (live != null) "Jump to live" else "Next",
                mediaIntent(
                    if (live != null) MainActivity.ACTION_JUMP_LIVE else MainActivity.ACTION_NEXT,
                    3,
                ),
            )
            .setStyle(
                Notification.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2),
            )
        manager.notify(MEDIA_NOTIFICATION_ID, notificationBuilder.build())
    }

    private fun mediaIntent(action: String, requestCode: Int): PendingIntent {
        if (action == MainActivity.ACTION_OPEN) {
            val openIntent = Intent(getApplication(), MainActivity::class.java).apply {
                this.action = action
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            return PendingIntent.getActivity(
                getApplication(),
                requestCode,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
        val commandIntent = Intent(getApplication(), MediaControlReceiver::class.java).apply {
            this.action = action
        }
        return PendingIntent.getBroadcast(
            getApplication(),
            requestCode,
            commandIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    override fun onCleared() {
        stopInternal(resetProgress = false)
        stopPlaybackService()
        val manager = getApplication<Application>()
            .getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.cancel(MEDIA_NOTIFICATION_ID)
        if (activeInstance?.get() === this) activeInstance = null
        mediaSession.release()
        super.onCleared()
    }

    companion object {
        private var activeInstance: WeakReference<PlayerViewModel>? = null
        const val MEDIA_CHANNEL_ID = "encodec_playback"
        const val MEDIA_NOTIFICATION_ID = 48
        private const val MEDIA_ACTION_TOGGLE_SHUFFLE =
            "com.henry.encodec.player.TOGGLE_SHUFFLE"
        private const val MEDIA_ACTION_CYCLE_REPEAT =
            "com.henry.encodec.player.CYCLE_REPEAT"
        private const val MEDIA_ACTION_JUMP_TO_LIVE =
            "com.henry.encodec.player.JUMP_TO_LIVE"
        private const val LIVE_STARTUP_SEGMENTS = 2
        private const val LIVE_PREFETCH_CAPACITY = 3
        private const val STATIC_HEADER_PREFIX_BYTES = 1024
        private const val STATIC_STARTUP_SECONDS = 3
        private const val STATIC_MIN_STARTUP_BYTES = 2 * 1024
        private const val STATIC_MAX_STARTUP_BYTES = 16 * 1024

        internal fun dispatchMediaAction(action: String?) {
            val player = activeInstance?.get() ?: return
            when (action) {
                MainActivity.ACTION_PLAY_PAUSE -> player.playPause()
                MainActivity.ACTION_PREVIOUS -> player.previous()
                MainActivity.ACTION_NEXT -> player.next()
                MainActivity.ACTION_STOP -> player.stop()
                MainActivity.ACTION_JUMP_LIVE -> player.jumpToLive()
            }
        }

        internal fun refreshMediaState() {
            activeInstance?.get()?.let { it.publishMediaState(it.mutableState.value) }
        }
    }

    private fun nextIndexAfterCompletion(state: PlayerState): Int? {
        if (state.playlist.isEmpty()) return null
        if (state.repeatMode == RepeatMode.TRACK) return state.currentIndex
        if (!state.shuffle) {
            if (state.currentIndex < state.playlist.lastIndex) return state.currentIndex + 1
            return if (state.repeatMode == RepeatMode.LIST) 0 else null
        }
        return nextShuffleIndex(
            state,
            restartCycle = state.repeatMode == RepeatMode.LIST,
        )
    }

    private fun nextShuffleIndex(state: PlayerState, restartCycle: Boolean): Int? {
        state.current?.let { shufflePlayedUris += it.uri.toString() }
        val unplayed = state.playlist.indices.filter { index ->
            state.playlist[index].uri.toString() !in shufflePlayedUris
        }
        if (unplayed.isNotEmpty()) return unplayed.random()
        if (!restartCycle) return null

        val currentUri = state.current?.uri?.toString()
        shufflePlayedUris.clear()
        if (currentUri != null) shufflePlayedUris += currentUri
        return state.playlist.indices
            .filter { state.playlist[it].uri.toString() !in shufflePlayedUris }
            .randomOrNull()
            ?: state.currentIndex.takeIf { it in state.playlist.indices }
    }

    private fun ensurePlaybackService() {
        val application = getApplication<Application>()
        val intent = Intent(application, PlaybackService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            application.startForegroundService(intent)
        } else {
            application.startService(intent)
        }
    }

    private fun stopPlaybackService() {
        getApplication<Application>().stopService(
            Intent(getApplication(), PlaybackService::class.java),
        )
    }

    private fun persistPlaylist() = playlistStore.save(mutableState.value)

    private fun releaseLocalPermission(item: PlaylistItem) {
        if (!item.uri.scheme.equals("content", ignoreCase = true)) return
        runCatching {
            getApplication<Application>().contentResolver.releasePersistableUriPermission(
                item.uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
    }

    private fun displayName(uri: Uri): String {
        val resolver = getApplication<Application>().contentResolver
        return resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "EnCodec track"
    }

    private fun remoteDisplayName(uri: Uri): String {
        val pathName = uri.lastPathSegment
            ?.substringAfterLast('/')
            ?.takeIf { it.isNotBlank() }
        return Uri.decode(pathName ?: uri.host ?: "HTTPS stream")
    }

    private fun openInput(uri: Uri): InputStream =
        if (uri.scheme.equals("https", ignoreCase = true)) {
            HttpsStreams.open(uri.toString())
        } else {
            getApplication<Application>().contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("Could not open the selected file")
        }

    private suspend fun <T> withPlaybackInput(
        uri: Uri,
        preparedRemote: Deferred<PreparedRemoteInput>?,
        block: suspend (InputStream, Int) -> T,
    ): T = withContext(Dispatchers.IO) {
        if (uri.scheme.equals("https", ignoreCase = true)) {
            val prepared = requireNotNull(preparedRemote).await()
            prepared.input.use { block(it, prepared.initialFrameIndex) }
        } else {
            openInput(uri).use { block(it, 0) }
        }
    }

    private suspend fun prepareRemoteInput(
        scope: CoroutineScope,
        uri: Uri,
        header: EcdcHeader,
        startSample: Long,
    ): PreparedRemoteInput {
        val prefix = remoteHeaderPrefixes[uri.toString()] ?: HttpsStreams.readPrefix(
            uri.toString(), STATIC_HEADER_PREFIX_BYTES,
        ).also { remoteHeaderPrefixes[uri.toString()] = it }
        require(prefix.size >= 9) { "Remote ECDC header is incomplete" }
        val metadataSize = ((prefix[5].toInt() and 0xff) shl 24) or
            ((prefix[6].toInt() and 0xff) shl 16) or
            ((prefix[7].toInt() and 0xff) shl 8) or (prefix[8].toInt() and 0xff)
        val headerSize = 9 + metadataSize
        require(headerSize in 9..prefix.size) { "Remote ECDC metadata exceeds 1 KiB" }
        val exactHeader = prefix.copyOf(headerSize)
        val stride = requireNotNull(header.variant.segmentStrideSamples)
        val frameIndex = (startSample / stride).toInt()
        val fullFrameBits = header.numCodebooks * header.variant.frameRate * 10
        val frameBytes = 4 + (fullFrameBits + 7) / 8
        val rangeStart = headerSize.toLong() + frameIndex.toLong() * frameBytes
        val startupBytes = (header.nominalBitrateBps * STATIC_STARTUP_SECONDS / 8)
            .coerceIn(STATIC_MIN_STARTUP_BYTES, STATIC_MAX_STARTUP_BYTES) + headerSize
        val input = DownloadAheadInputStream.open(
            scope = scope,
            startupBytes = startupBytes,
            sourceProvider = {
                SequenceInputStream(
                    ByteArrayInputStream(exactHeader),
                    HttpsStreams.openRange(uri.toString(), rangeStart),
                )
            },
        )
        return PreparedRemoteInput(input, frameIndex)
    }

    private data class PreparedRemoteInput(
        val input: InputStream,
        val initialFrameIndex: Int,
    )

    private fun validateHeader(header: EcdcHeader) {
        require(!header.usesLanguageModel) {
            "LM-coded files are not supported yet"
        }
        require(header.variant == EncodecVariant.STEREO_48_KHZ) {
            "24 kHz non-HQ files are not supported"
        }
    }

    private fun decoderConfig(variant: EncodecVariant): DecoderConfig = when (variant) {
        EncodecVariant.STEREO_48_KHZ -> DecoderConfig(
            assetName = "encodec_48khz_decoder.pte",
            maxCodebooks = 16,
            timeSteps = 150,
        )
        EncodecVariant.MONO_24_KHZ -> error("24 kHz non-HQ files are not supported")
    }

    private data class DecoderConfig(
        val assetName: String,
        val maxCodebooks: Int,
        val timeSteps: Int,
    )

    private fun copyAssetOnce(name: String): File {
        val filesDir = getApplication<Application>().filesDir
        val destination = File(filesDir, name)
        if (!destination.exists()) {
            getApplication<Application>().assets.open(name).use { source ->
                destination.outputStream().use(source::copyTo)
            }
        }
        return destination
    }

    private fun removeObsoleteDecoderModels() {
        val filesDir = getApplication<Application>().filesDir
        File(filesDir, "encodec_24khz_decoder.pte").delete()
        File(filesDir, "encodec_24khz_decoder_308.pte").delete()
    }
}
