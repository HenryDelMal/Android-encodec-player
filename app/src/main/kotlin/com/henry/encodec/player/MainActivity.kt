package com.henry.encodec.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode as ComposeRepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.animation.core.animateFloatAsState
import com.henry.encodec.ecdc.EcdcHeader
import com.henry.encodec.ecdc.EncodecVariant
import java.util.Locale
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    private val playerModel: PlayerViewModel by lazy {
        (application as PlayerApplication).playerModel
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var themeMode by rememberSaveable { mutableStateOf("System") }
            val useDark = themeMode == "Dark" ||
                (themeMode == "System" && isSystemInDarkTheme())
            val colors = if (useDark) {
                darkColorScheme(
                    primary = Color(0xFFB5A3FF),
                    background = Color(0xFF11101A),
                    surface = Color(0xFF191824),
                    surfaceVariant = Color(0xFF242231),
                )
            } else {
                lightColorScheme(
                    primary = Color(0xFF6548F5),
                    secondary = Color(0xFF8B6DFF),
                    background = Color(0xFFF6F4FC),
                    surface = Color(0xFFFEFDFF),
                    surfaceVariant = Color(0xFFF0EDFA),
                    primaryContainer = Color(0xFFE9E2FF),
                    onPrimaryContainer = Color(0xFF24105C),
                )
            }
            MaterialTheme(colorScheme = colors) {
                Surface(color = colors.background) {
                    PlayerScreen(playerModel, themeMode, onThemeModeChange = { themeMode = it })
                }
            }
        }
        handleMediaAction(intent)
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleMediaAction(intent)
    }

    private fun handleMediaAction(intent: Intent?) {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> playerModel.playPause()
            ACTION_PREVIOUS -> playerModel.previous()
            ACTION_NEXT -> playerModel.next()
            ACTION_STOP -> playerModel.stop()
            ACTION_JUMP_LIVE -> playerModel.jumpToLive()
        }
    }

    companion object {
        const val ACTION_OPEN = "com.henry.encodec.player.OPEN"
        const val ACTION_PLAY_PAUSE = "com.henry.encodec.player.PLAY_PAUSE"
        const val ACTION_PREVIOUS = "com.henry.encodec.player.PREVIOUS"
        const val ACTION_NEXT = "com.henry.encodec.player.NEXT"
        const val ACTION_STOP = "com.henry.encodec.player.STOP"
        const val ACTION_JUMP_LIVE = "com.henry.encodec.player.JUMP_TO_LIVE"
    }
}

@Composable
private fun PlayerScreen(
    model: PlayerViewModel,
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
) {
    val state by model.state.collectAsState()
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var draggingSlider by remember { mutableStateOf(false) }
    var urlText by remember { mutableStateOf(model.lastLiveUrl()) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf("Home") }
    var browseLives by rememberSaveable { mutableStateOf(false) }
    var settingsPage by rememberSaveable { mutableStateOf<String?>(null) }
    var fullPlayer by rememberSaveable { mutableStateOf(false) }
    var search by rememberSaveable { mutableStateOf("") }
    var moreMenu by remember { mutableStateOf(false) }
    var homeTab by rememberSaveable { mutableStateOf("Queue") }
    val importTracksPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::importTracks)
    }
    val importStreamsPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::importStreams)
    }
    val exportTracksPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(model::exportTracks)
    }
    val exportStreamsPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        uri?.let(model::exportStreams)
    }
    BackHandler(enabled = showUrlDialog || fullPlayer || settingsPage != null || page != "Home") {
        when {
            showUrlDialog -> showUrlDialog = false
            fullPlayer -> fullPlayer = false
            settingsPage != null -> settingsPage = null
            page != "Home" -> page = "Home"
        }
    }
    LaunchedEffect(state.progress) { if (!draggingSlider) sliderPosition = state.progress }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) model.addToPlaylist(uris)
    }
    val openPicker = { picker.launch(arrayOf("*/*")) }

    Column(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .statusBarsPadding().navigationBarsPadding(),
    ) {
        if (!fullPlayer) {
            Row(
                Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.encodec_logo), contentDescription = null, modifier = Modifier.size(27.dp))
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text("EnCodec Player", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("Neural audio. Anywhere.", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = openPicker) {
                    Icon(painterResource(android.R.drawable.ic_menu_add), contentDescription = "Add audio files")
                }
                IconButton(enabled = !state.addingUrl, onClick = { showUrlDialog = true }) {
                    if (state.addingUrl) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Text("🌐", modifier = Modifier.semantics { contentDescription = "Open URL" }, fontSize = 19.sp)
                }
                Box {
                    IconButton(onClick = { moreMenu = true }) {
                        Icon(painterResource(android.R.drawable.ic_menu_more), contentDescription = "More options")
                    }
                    DropdownMenu(expanded = moreMenu, onDismissRequest = { moreMenu = false }) {
                        DropdownMenuItem(text = { Text("Settings") }, onClick = {
                            moreMenu = false
                            page = "Settings"
                        })
                        DropdownMenuItem(text = { Text("Add files") }, onClick = {
                            moreMenu = false
                            openPicker()
                        })
                    }
                }
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (fullPlayer) {
                FullPlayerPage(state, model, sliderPosition, onSlider = { sliderPosition = it; draggingSlider = true },
                    onSeek = { model.seekToFraction(sliderPosition); draggingSlider = false },
                    onClose = { fullPlayer = false })
            } else when (page) {
                "Browse" -> BrowsePage(
                    state = state,
                    model = model,
                    showLives = browseLives,
                    onShowLives = { browseLives = it },
                    search = search,
                    onSearch = { search = it },
                    onTrackSelected = { page = "Home" },
                    onStreamSelected = { page = "Home" },
                )
                "Settings" -> SettingsPage(
                    state = state,
                    model = model,
                    themeMode = themeMode,
                    onThemeModeChange = onThemeModeChange,
                    detail = settingsPage,
                    onDetail = { settingsPage = it },
                    onImportTracks = { importTracksPicker.launch(arrayOf("application/json", "text/*")) },
                    onExportTracks = { exportTracksPicker.launch("EnCodec-Tracks.json") },
                    onImportStreams = { importStreamsPicker.launch(arrayOf("application/json", "text/*")) },
                    onExportStreams = { exportStreamsPicker.launch("EnCodec-Streams.json") },
                )
                else -> HomePage(
                    state = state,
                    model = model,
                    sliderPosition = sliderPosition,
                    onSlider = { sliderPosition = it; draggingSlider = true },
                    onSeek = { model.seekToFraction(sliderPosition); draggingSlider = false },
                    onFullPlayer = { fullPlayer = true },
                    onBrowseLives = { browseLives = true; page = "Browse" },
                    onBrowseTracks = { browseLives = false; page = "Browse" },
                    selectedTab = homeTab,
                    onSelectedTab = { homeTab = it },
                )
            }
        }

        if (!fullPlayer) {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf("Home", "Tracks", "Streams", "Settings").forEach { destination ->
                    val selected = page == destination ||
                        (page == "Browse" && destination == if (browseLives) "Streams" else "Tracks")
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            when (destination) {
                                "Home" -> { page = "Home"; settingsPage = null }
                                "Tracks", "Streams" -> {
                                    browseLives = destination == "Streams"
                                    page = "Browse"
                                    settingsPage = null
                                }
                                else -> { page = destination; settingsPage = null }
                            }
                        },
                        icon = {
                            val tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                            if (destination == "Streams") GlobeGlyph(Modifier.size(21.dp), tint)
                            else Text(
                                when (destination) { "Home" -> "⌂"; "Tracks" -> "♫"; else -> "⚙" },
                                color = tint, fontSize = 20.sp,
                            )
                        },
                        label = { Text(destination, style = MaterialTheme.typography.labelSmall) },
                        alwaysShowLabel = true,
                    )
                }
            }
        }
    }

    if (showUrlDialog) {
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("Open URL") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter an .ecdc file URL or an EnCodec Live .json manifest URL.",
                        style = MaterialTheme.typography.bodySmall)
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        label = { Text("URL") },
                        placeholder = { Text("https://example.com/stream/stream.json") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(
                    enabled = urlText.trim().let { it.startsWith("http://", true) || it.startsWith("https://", true) },
                    onClick = { model.openUrl(urlText); showUrlDialog = false },
                ) { Text(if (state.addingUrl) "Checking…" else "Open") }
            },
            dismissButton = { TextButton(onClick = { showUrlDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun GlobeGlyph(modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier) {
        val stroke = size.minDimension * 0.075f
        drawCircle(tint, style = Stroke(stroke))
        drawOval(tint, topLeft = androidx.compose.ui.geometry.Offset(size.width * 0.31f, 0f),
            size = androidx.compose.ui.geometry.Size(size.width * 0.38f, size.height), style = Stroke(stroke))
        drawLine(tint, androidx.compose.ui.geometry.Offset(size.width * 0.06f, size.height * 0.36f),
            androidx.compose.ui.geometry.Offset(size.width * 0.94f, size.height * 0.36f), stroke)
        drawLine(tint, androidx.compose.ui.geometry.Offset(size.width * 0.06f, size.height * 0.64f),
            androidx.compose.ui.geometry.Offset(size.width * 0.94f, size.height * 0.64f), stroke)
    }
}

@Composable
private fun HomePage(
    state: PlayerState,
    model: PlayerViewModel,
    sliderPosition: Float,
    onSlider: (Float) -> Unit,
    onSeek: () -> Unit,
    onFullPlayer: () -> Unit,
    onBrowseLives: () -> Unit,
    onBrowseTracks: () -> Unit,
    selectedTab: String,
    onSelectedTab: (String) -> Unit,
) {
    val queueRows = remember(state.playlist, state.currentIndex) {
        if (state.playlist.isEmpty()) emptyList()
        else {
            val start = state.currentIndex.coerceIn(0, state.playlist.size)
            state.playlist.drop(start).mapIndexed { offset, item ->
                (start + offset) to item
            }
        }
    }
    val recentRows = remember(state.recentItems, state.libraryTracks, state.livestreams) {
        state.recentItems.take(8).mapNotNull { key ->
            when {
                key.startsWith("track:") -> state.libraryTracks.firstOrNull { it.uri.toString() == key.removePrefix("track:") }
                    ?.let { item -> key to (item.title to "Track · ${formatBitrate(item.header)} · ${formatAudioFormat(item.header)}") }
                key.startsWith("stream:") -> state.livestreams.firstOrNull { it.manifestUrl == key.removePrefix("stream:") }
                    ?.let { item -> key to (item.title to "Stream · ${item.manifestUrl}") }
                else -> null
            }
        }
    }
    val listState = rememberLazyListState()
    // The queue is projected from the current track onward. If playback changes
    // while Home is scrolled down, reset the list so the new current item isn't
    // left outside the visible portion of that projection.
    LaunchedEffect(state.currentIndex, state.playlist.size, selectedTab) {
        if (selectedTab == "Queue") listState.animateScrollToItem(0)
    }
    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        state = listState,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "home-player") { PlayerHeroCard(state, model, sliderPosition, onSlider, onSeek, onFullPlayer) }
        item(key = "home-tabs") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = selectedTab == "Queue", onClick = { onSelectedTab("Queue") },
                    label = { Text("Queue · ${queueRows.size}") })
                FilterChip(selected = selectedTab == "Recents", onClick = { onSelectedTab("Recents") },
                    label = { Text("Recents") })
                Spacer(Modifier.weight(1f))
                if (selectedTab == "Queue") {
                    IconButton(enabled = state.playlist.isNotEmpty(), onClick = model::clearPlaylist) {
                        Icon(painterResource(android.R.drawable.ic_menu_delete), contentDescription = "Clear queue")
                    }
                    IconButton(onClick = onBrowseTracks) {
                        Icon(painterResource(android.R.drawable.ic_menu_add), contentDescription = "Add from tracks")
                    }
                } else IconButton(onClick = onBrowseLives) { GlobeGlyph(Modifier.size(21.dp)) }
            }
        }
        if (selectedTab == "Queue") {
            item(key = "queue-hint") {
                Text("Hold and drag a queued track to change playback order", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (queueRows.isEmpty()) {
                item(key = "queue-empty") {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(22.dp)) {
                        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Your playback queue is empty", style = MaterialTheme.typography.titleSmall)
                            Text("Add tracks from your library. Streams stay in their own list.", style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            FilledTonalButton(onClick = onBrowseTracks) { Text("Browse tracks") }
                        }
                    }
                }
            } else {
                itemsIndexed(queueRows, key = { _, row -> "queue:${row.second.uri}" }) { index, row ->
                    val (actualIndex, item) = row
                    TrackRow(
                        index = index,
                        item = item,
                        selected = state.live == null && index == 0,
                        onClick = { model.selectTrack(actualIndex) },
                        onRemove = { model.removeTrack(actualIndex) },
                        onMove = if (index == 0) null else model::moveTrack,
                        maxIndex = queueRows.lastIndex,
                        minIndex = 1,
                        placementModifier = Modifier.animateItem(),
                    )
                }
            }
        } else {
            item(key = "recents-hint") {
                Text("Tracks and stations · most recent first", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (recentRows.isEmpty()) {
                item(key = "recents-empty") {
                    Text("Your recent tracks and stations will show here.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else items(recentRows, key = { it.first }) { (key, details) ->
                RecentRow(
                    title = details.first,
                    subtitle = details.second,
                    isStream = key.startsWith("stream:"),
                    onClick = {
                        if (key.startsWith("stream:")) model.openLive(key.removePrefix("stream:"))
                        else state.libraryTracks.firstOrNull { it.uri.toString() == key.removePrefix("track:") }
                            ?.let(model::playLibraryTrack)
                    },
                    onRemove = { model.removeRecent(key) },
                )
            }
        }
    }
}

@Composable
private fun PlayerHeroCard(
    state: PlayerState,
    model: PlayerViewModel,
    sliderPosition: Float,
    onSlider: (Float) -> Unit,
    onSeek: () -> Unit,
    onOpen: () -> Unit,
) {
    val live = state.live
    val item = state.current
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(26.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StationArtwork(Modifier.size(74.dp), label = if (live != null) live.title else item?.title ?: "EnCodec")
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    if (live != null) BadgeText("◉ LIVE")
                    Text(live?.title ?: item?.title ?: "Nothing playing", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        when {
                            live != null -> "${live.variant?.let(::formatAudioFormat) ?: "Live stream"} · ${live.bandwidthKbps?.let { "${formatNumber(it)} kbps" } ?: "Connecting"}"
                            item != null -> "${formatBitrate(item.header)} · ${formatAudioFormat(item.header)}"
                            else -> "Neural audio. Anywhere."
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onOpen) { Text("♡", color = MaterialTheme.colorScheme.primary, fontSize = 22.sp) }
            }
            Waveform(
                Modifier.fillMaxWidth().height(34.dp),
                liveActive = state.playing && !state.paused,
                isLive = live != null,
                progress = state.progress,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                MetaTile("▮", live?.bandwidthKbps?.let { "${formatNumber(it)} kbps" } ?: item?.let { formatBitrate(it.header) } ?: "—", "Bitrate", Modifier.weight(1f))
                MetaTile("◇", live?.codebooks?.toString() ?: item?.header?.numCodebooks?.toString() ?: "—", "Codebooks", Modifier.weight(1f))
                MetaTile("♫", live?.variant?.sampleRate?.let { "${it / 1000} kHz" } ?: item?.let { "${it.header.variant.sampleRate / 1000} kHz" } ?: "—", "Audio", Modifier.weight(1f))
                MetaTile("◉", live?.let { "${it.bufferedSegments}/${it.targetBufferedSegments}" } ?: "Ready", live?.let { if (it.buffering) "Buffering" else "Queued" } ?: "Status", Modifier.weight(1f))
            }
            if (live != null) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(live.sequence?.let { "Sequence $it" } ?: live.status, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall)
                    Text(livePlaybackLabel(live.status), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
                Box(Modifier.fillMaxWidth().height(4.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))) {
                    Box(Modifier.fillMaxWidth((live.bufferedSegments.toFloat() / live.targetBufferedSegments.coerceAtLeast(1)).coerceIn(0.03f, 1f))
                        .height(4.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(50)))
                }
            } else {
                PlaybackTimeline(state, sliderPosition, onSlider, onSeek)
            }
            TransportControls(state, model, compact = true)
        }
    }
}

@Composable
private fun BrowsePage(
    state: PlayerState,
    model: PlayerViewModel,
    showLives: Boolean,
    onShowLives: (Boolean) -> Unit,
    search: String,
    onSearch: (String) -> Unit,
    onTrackSelected: () -> Unit,
    onStreamSelected: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Browse", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (showLives) IconButton(onClick = model::clearLiveStreams) {
                Icon(painterResource(android.R.drawable.ic_menu_delete), contentDescription = "Clear saved streams")
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !showLives, onClick = { onShowLives(false) }, label = { Text("Tracks (${state.libraryTracks.size})") })
            FilterChip(selected = showLives, onClick = { onShowLives(true) }, label = { Text("Live (${state.livestreams.size})") })
        }
        OutlinedTextField(
            value = search,
            onValueChange = onSearch,
            modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
            singleLine = true,
            placeholder = { Text(if (showLives) "Search stations…" else "Search tracks…") },
            leadingIcon = { Text("⌕", fontSize = 20.sp) },
            shape = RoundedCornerShape(16.dp),
        )
        if (showLives) {
            Text("Hold and drag a station to change order", Modifier.padding(bottom = 4.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val streams = state.livestreams.withIndex().filter { it.value.title.contains(search, true) || it.value.manifestUrl.contains(search, true) }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                itemsIndexed(streams, key = { _, entry -> entry.value.manifestUrl }) { _, entry ->
                    StreamRow(entry.value, state.live?.manifestUrl == entry.value.manifestUrl,
                        onClick = { model.openLive(entry.value.manifestUrl); onStreamSelected() },
                        onRemove = { model.removeLiveStream(entry.index) },
                        onMove = model::moveLiveStream,
                        index = entry.index,
                        maxIndex = state.livestreams.lastIndex,
                        placementModifier = Modifier.animateItem())
                }
            }
        } else {
            val tracks = state.libraryTracks.filter { it.title.contains(search, true) || it.uri.toString().contains(search, true) }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                itemsIndexed(tracks, key = { _, entry -> entry.uri.toString() }) { _, entry ->
                    LibraryTrackRow(
                        item = entry,
                        selected = state.live == null && state.current?.uri == entry.uri,
                        inQueue = state.playlist.any { it.uri == entry.uri },
                        onClick = { model.playLibraryTrack(entry); onTrackSelected() },
                        onAddToQueue = { model.addLibraryTrackToQueue(entry) },
                        onDelete = { model.deleteLibraryTrack(entry) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(
    state: PlayerState,
    model: PlayerViewModel,
    themeMode: String,
    onThemeModeChange: (String) -> Unit,
    detail: String?,
    onDetail: (String?) -> Unit,
    onImportTracks: () -> Unit,
    onExportTracks: () -> Unit,
    onImportStreams: () -> Unit,
    onExportStreams: () -> Unit,
) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        state.error?.let { message ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                shape = RoundedCornerShape(14.dp)) {
                Text(message, Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (detail != null) IconButton(onClick = { onDetail(null) }) {
                Icon(painterResource(android.R.drawable.ic_media_previous), contentDescription = "Back")
            }
            Column {
                Text(detail ?: "Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                if (detail == null) Text("Make the player yours", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (detail == null) {
            listOf("Playback", "Appearance", "Network", "Library", "Advanced").forEach { title ->
                Card(Modifier.fillMaxWidth().clickable { onDetail(title) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(18.dp)) {
                    Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(settingsGlyph(title), color = MaterialTheme.colorScheme.primary, fontSize = 21.sp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Text(settingsSubtitle(title), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 22.sp)
                    }
                }
            }
        } else when (detail) {
            "Appearance" -> {
                Text("Theme", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Light", "Dark", "System").forEach { option ->
                        FilterChip(selected = themeMode == option, onClick = { onThemeModeChange(option) }, label = { Text(option) })
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Violet accent", style = MaterialTheme.typography.titleSmall)
                        Text("Soft surfaces and adaptive light/dark appearance", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            "Playback" -> {
                val selectedVariant = state.live?.variant ?: state.current?.header?.variant
                val selectedCodebooks = state.live?.codebooks ?: state.current?.header?.numCodebooks
                val canUseVocos = selectedVariant == EncodecVariant.MONO_24_KHZ && selectedCodebooks in setOf(2, 4, 8, 16)
                SettingsToggle(
                    title = "Experimental Vocos decoder",
                    subtitle = when {
                        !state.experimentalVocos -> "EnCodec is used for playback"
                        selectedVariant == null -> "Available for supported 24 kHz mono audio"
                        canUseVocos -> "Vocos selected for this audio"
                        else -> "This format continues to use EnCodec"
                    },
                    checked = state.experimentalVocos,
                    onChecked = model::setExperimentalVocos,
                )
                SettingsToggle(
                    title = "Rescale decoded audio",
                    subtitle = if (state.rescaleEnabled) {
                        "Peak level is limited to prevent clipping"
                    } else {
                        "Off · preserve the decoder's original level"
                    },
                    checked = state.rescaleEnabled,
                    onChecked = model::setRescaleEnabled,
                )
            }
            "Network" -> SettingsInfo("Live streams refresh their manifest as needed and buffer ahead based on connection performance.")
            "Advanced" -> SettingsToggle("Playback diagnostics", "Extra decoder and stream logs", state.diagnosticsEnabled) { model.toggleDiagnostics() }
            "Library" -> {
                Text("Tracks · ${state.playlist.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("Import or export your saved ECDC file list.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onImportTracks, modifier = Modifier.weight(1f)) { Text("Import files") }
                    FilledTonalButton(onClick = onExportTracks, modifier = Modifier.weight(1f)) { Text("Export files") }
                }
                Text("Livestreams · ${state.livestreams.size}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text("Import or export saved stream addresses separately.", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = onImportStreams, modifier = Modifier.weight(1f)) { Text("Import streams") }
                    FilledTonalButton(onClick = onExportStreams, modifier = Modifier.weight(1f)) { Text("Export streams") }
                }
                Text("File entries refer to their original location; copied local files need to remain accessible.",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> SettingsInfo("EnCodec Player · version 0.11.8\nHQ EnCodec playback with experimental Vocos support for compatible 24 kHz mono audio.")
        }
    }
}

@Composable
private fun FullPlayerPage(
    state: PlayerState,
    model: PlayerViewModel,
    sliderPosition: Float,
    onSlider: (Float) -> Unit,
    onSeek: () -> Unit,
    onClose: () -> Unit,
) {
    var overflowOpen by remember { mutableStateOf(false) }
    val live = state.live
    val item = state.current
    Column(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF151322), Color(0xFF090912))))
        .padding(horizontal = 22.dp, vertical = 8.dp), verticalArrangement = Arrangement.SpaceBetween,
        horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Text("⌄", color = Color.White, fontSize = 24.sp) }
            Spacer(Modifier.weight(1f))
            Text(if (live != null) "◉ LIVE" else "NOW PLAYING", color = Color(0xFFB7A6FF), style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            Box {
                IconButton(onClick = { overflowOpen = true }) { Text("⋮", color = Color.White, fontSize = 24.sp) }
                DropdownMenu(expanded = overflowOpen, onDismissRequest = { overflowOpen = false }) {
                    DropdownMenuItem(text = { Text("Stop playback") }, onClick = {
                        overflowOpen = false
                        model.stop()
                    })
                }
            }
        }
        StationArtwork(Modifier.size(250.dp), label = live?.title ?: item?.title ?: "EnCodec", large = true)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(live?.title ?: item?.title ?: "Nothing playing", color = Color.White,
                style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                live?.status ?: item?.let { "${formatBitrate(it.header)} · ${formatAudioFormat(it.header)}" } ?: "EnCodec Player",
                color = Color(0xFFB8B4C8), style = MaterialTheme.typography.bodyMedium,
            )
        }
        Waveform(
            Modifier.fillMaxWidth().height(58.dp),
            liveActive = state.playing && !state.paused,
            dark = true,
            isLive = live != null,
            progress = state.progress,
        )
        if (live != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(live.sequence?.let { "Sequence $it" } ?: "Connecting", color = Color.White, style = MaterialTheme.typography.labelSmall)
                Text("LIVE · ${live.bufferedSegments}/${live.targetBufferedSegments}", color = Color(0xFFB7A6FF), style = MaterialTheme.typography.labelSmall)
            }
            LiveActions(model)
        } else PlaybackTimeline(state, sliderPosition, onSlider, onSeek, dark = true)
        TransportControls(state, model, compact = false, dark = true)
        if (item != null) {
            Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF201E2C)), shape = RoundedCornerShape(18.dp)) {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Track info", Modifier.weight(1f), color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text("${formatBitrate(item.header)} · ${item.header.numCodebooks} codebooks · ${formatAudioFormat(item.header)}",
                        color = Color(0xFFCBC7D8), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun PlaybackTimeline(
    state: PlayerState,
    sliderPosition: Float,
    onSlider: (Float) -> Unit,
    onSeek: () -> Unit,
    dark: Boolean = false,
) {
    val duration = state.current?.let { it.header.audioLengthSamples / it.header.variant.sampleRate } ?: 0L
    Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
        Slider(value = sliderPosition, enabled = state.current != null, onValueChange = onSlider,
            onValueChangeFinished = onSeek, modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatTime((duration * sliderPosition).toLong()), color = if (dark) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall)
            Text(formatTime(duration), color = if (dark) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun TransportControls(state: PlayerState, model: PlayerViewModel, compact: Boolean, dark: Boolean = false) {
    val muted = if (dark) Color.White else MaterialTheme.colorScheme.onSurface
    val canChangeLiveStation = state.live != null && state.livestreams.size > 1
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
        PlayerAction("⤨", "Shuffle", state.shuffle, compact, dark, enabled = state.live == null && state.playlist.isNotEmpty(), onClick = model::toggleShuffle)
        PlayerAction("|◀", "Previous", false, compact, dark,
            enabled = canChangeLiveStation || (state.live == null && (state.currentIndex > 0 || state.repeatMode == RepeatMode.LIST)), onClick = model::previous)
        Surface(
            modifier = Modifier.size(if (compact) 54.dp else 68.dp).clickable(enabled = state.current != null || state.live != null, onClick = model::playPause),
            shape = androidx.compose.foundation.shape.CircleShape,
            color = MaterialTheme.colorScheme.primary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(painterResource(if (state.playing && !state.paused) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play),
                    contentDescription = if (state.playing && !state.paused) "Pause" else "Play", tint = Color.White,
                    modifier = Modifier.size(if (compact) 26.dp else 32.dp))
            }
        }
        PlayerAction("▶|", "Next", false, compact, dark,
            enabled = canChangeLiveStation || (state.live == null && state.playlist.size > 1 && (state.shuffle || state.currentIndex < state.playlist.lastIndex || state.repeatMode == RepeatMode.LIST)), onClick = model::next)
        PlayerAction(if (state.repeatMode == RepeatMode.TRACK) "1↻" else "↻", "Repeat", state.repeatMode != RepeatMode.OFF, compact, dark,
            enabled = state.live == null && state.playlist.isNotEmpty(), onClick = model::cycleRepeatMode)
    }
}

@Composable
private fun PlayerAction(glyph: String, description: String, active: Boolean, compact: Boolean, dark: Boolean,
    enabled: Boolean, onClick: () -> Unit) {
    val iconColor = when {
        !enabled -> (if (dark) Color.White else MaterialTheme.colorScheme.onSurface).copy(alpha = 0.35f)
        active -> MaterialTheme.colorScheme.primary
        else -> if (dark) Color.White else MaterialTheme.colorScheme.onSurface
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(enabled = enabled, onClick = onClick, modifier = Modifier.size(if (compact) 42.dp else 54.dp)) {
            Text(glyph, modifier = Modifier.semantics { contentDescription = description }, color = iconColor,
                fontSize = if (compact) 19.sp else 23.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LiveActions(model: PlayerViewModel) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        TextButton(onClick = model::reconnectLive) { Text("⟳  Reconnect") }
        TextButton(onClick = model::jumpToLive) { Text("⇥  Live edge") }
        TextButton(onClick = model::disconnectLive) { Text("×  Disconnect") }
    }
}

@Composable
private fun StreamRow(
    item: SavedLiveStream,
    selected: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    index: Int = 0,
    maxIndex: Int = 0,
    onMove: ((Int, Int) -> Unit)? = null,
    placementModifier: Modifier = Modifier,
) {
    val dragModifier = onMove?.let { rememberReorderModifier(index, maxIndex, it) } ?: Modifier
    Card(placementModifier.fillMaxWidth().then(dragModifier).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp), elevation = CardDefaults.cardElevation(1.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            StationArtwork(Modifier.size(48.dp), item.title)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(item.manifestUrl, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    item.bandwidthKbps?.let { SmallBadge("${formatNumber(it)} kbps") }
                    item.variant?.let { SmallBadge("${it.sampleRate / 1000} kHz") }
                    item.codebooks?.let { SmallBadge("$it codebooks") }
                }
            }
            IconButton(onClick = onRemove) { Icon(painterResource(android.R.drawable.ic_menu_delete), contentDescription = "Remove ${item.title}") }
            if (selected) Icon(painterResource(android.R.drawable.ic_media_play), contentDescription = "Playing", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun TrackRow(
    index: Int,
    item: PlaylistItem,
    selected: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
    maxIndex: Int = index,
    onMove: ((Int, Int) -> Unit)? = null,
    minIndex: Int = 0,
    placementModifier: Modifier = Modifier,
) {
    val dragModifier = onMove?.let { rememberReorderModifier(index, maxIndex, it, minIndex) } ?: Modifier
    Card(placementModifier.fillMaxWidth().then(dragModifier).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp), elevation = CardDefaults.cardElevation(1.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${index + 1}.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(26.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${item.uri.scheme.orEmpty().uppercase(Locale.US)} · ${formatBitrate(item.header)} · ${item.header.numCodebooks} codebooks · ${formatAudioFormat(item.header)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(formatTime(item.header.audioLengthSamples / item.header.variant.sampleRate), style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            IconButton(onClick = onRemove) { Icon(painterResource(android.R.drawable.ic_menu_delete), contentDescription = "Remove ${item.title}") }
        }
    }
}

@Composable
private fun LibraryTrackRow(
    item: PlaylistItem,
    selected: Boolean,
    inQueue: Boolean,
    onClick: () -> Unit,
    onAddToQueue: () -> Unit,
    onDelete: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp), elevation = CardDefaults.cardElevation(1.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (selected) "▶" else "♫", color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.width(26.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.title, style = MaterialTheme.typography.titleSmall, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${item.uri.scheme.orEmpty().uppercase(Locale.US)} · ${formatBitrate(item.header)} · ${item.header.numCodebooks} codebooks · ${formatAudioFormat(item.header)}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (inQueue) SmallBadge("Queued")
            Box {
                IconButton(onClick = { menuOpen = true }) { Text("⋮", fontSize = 21.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(text = { Text(if (inQueue) "Already in queue" else "Add to Queue") }, enabled = !inQueue,
                        onClick = { menuOpen = false; onAddToQueue() })
                    DropdownMenuItem(text = { Text("Delete from Tracks") }, onClick = { menuOpen = false; onDelete() })
                }
            }
        }
    }
}

@Composable
private fun RecentRow(
    title: String,
    subtitle: String,
    isStream: Boolean,
    onClick: () -> Unit,
    onRemove: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (isStream) "◉" else "♫", color = MaterialTheme.colorScheme.primary, fontSize = 20.sp)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = onRemove) {
                Icon(painterResource(android.R.drawable.ic_menu_close_clear_cancel), contentDescription = "Remove from recents")
            }
        }
    }
}

@Composable
private fun rememberReorderModifier(
    index: Int,
    maxIndex: Int,
    onMove: (Int, Int) -> Unit,
    minIndex: Int = 0,
): Modifier {
    val currentIndex = rememberUpdatedState(index)
    val currentMaxIndex = rememberUpdatedState(maxIndex)
    val currentOnMove = rememberUpdatedState(onMove)
    val density = LocalDensity.current
    var dragging by remember { mutableStateOf(false) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val scale by animateFloatAsState(if (dragging) 1.035f else 1f, label = "drag-scale")
    val liftTarget = with(density) { if (dragging) 12.dp.toPx() else 1.dp.toPx() }
    val lift by animateFloatAsState(liftTarget, label = "drag-lift")
    return Modifier.graphicsLayer {
        scaleX = scale
        scaleY = scale
        shadowElevation = lift
        translationY = dragOffsetY
        alpha = if (dragging) 0.98f else 1f
    }.zIndex(if (dragging) 1f else 0f).pointerInput(Unit) {
        val spacing = with(density) { 7.dp.toPx() }
        val threshold = size.height + spacing
        var accumulated = 0f
        var movingIndex = currentIndex.value
        detectDragGesturesAfterLongPress(
            onDragStart = {
                accumulated = 0f
                movingIndex = currentIndex.value
                dragOffsetY = 0f
                dragging = true
            },
            onDragEnd = { accumulated = 0f; dragOffsetY = 0f; dragging = false },
            onDragCancel = { accumulated = 0f; dragOffsetY = 0f; dragging = false },
            onDrag = { change, dragAmount ->
                change.consume()
                accumulated += dragAmount.y
                dragOffsetY += dragAmount.y
                while (accumulated >= threshold && movingIndex < currentMaxIndex.value) {
                    val from = movingIndex
                    currentOnMove.value(from, from + 1)
                    movingIndex++
                    accumulated -= threshold
                    dragOffsetY -= threshold
                }
                while (accumulated <= -threshold && movingIndex > minIndex) {
                    val from = movingIndex
                    currentOnMove.value(from, from - 1)
                    movingIndex--
                    accumulated += threshold
                    dragOffsetY += threshold
                }
            },
        )
    }
}

@Composable
private fun StationArtwork(modifier: Modifier = Modifier, label: String, large: Boolean = false) {
    val shape = RoundedCornerShape(if (large) 28.dp else 16.dp)
    Surface(modifier = modifier, shape = shape,
        color = if (large) Color(0xFF10101C) else MaterialTheme.colorScheme.primaryContainer,
        tonalElevation = 1.dp) {
        Box(contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(if (large) 16.dp else 3.dp)) {
                Text("▂▅▇▅▂", color = if (large) Color(0xFF9B72FF) else MaterialTheme.colorScheme.primary,
                    fontSize = if (large) 58.sp else 22.sp, fontWeight = FontWeight.Black)
                if (large) Text(label, color = Color.White, fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                else Text(label.take(16), style = MaterialTheme.typography.labelSmall, maxLines = 1,
                    overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun Waveform(
    modifier: Modifier,
    liveActive: Boolean,
    dark: Boolean = false,
    isLive: Boolean = false,
    progress: Float = 0f,
) {
    val activeColor = if (dark) Color(0xFF8665FF) else MaterialTheme.colorScheme.primary
    val inactiveColor = if (dark) Color(0xFF514D5D) else MaterialTheme.colorScheme.secondary.copy(alpha = 0.32f)
    var heights by remember { mutableStateOf(List(56) { 0.24f + Random.nextFloat() * 0.72f }) }
    LaunchedEffect(liveActive) {
        if (liveActive) {
            while (true) {
                heights = List(56) { 0.22f + Random.nextFloat() * 0.76f }
                kotlinx.coroutines.delay(170)
            }
        }
    }
    val antennaFront = if (isLive && liveActive) AntennaPulse() else 0f

    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        repeat(56) { index ->
            val position = index / 55f
            val edgeDistance = kotlin.math.abs(position - 0.5f) * 2f
            val distanceFromCenter = 27 - minOf(index, 55 - index)
            val antennaStep = (antennaFront * 27f).toInt().coerceAtMost(27)
            val lit = if (isLive) {
                liveActive && distanceFromCenter == antennaStep
            } else {
                liveActive && progress > 0f && position <= progress
            }
            val barHeight = heights[index]
            Box(
                Modifier.weight(1f)
                    .fillMaxHeight(barHeight)
                    .background(if (lit) activeColor else inactiveColor, RoundedCornerShape(3.dp)),
            )
        }
    }
}

@Composable
private fun AntennaPulse(): Float {
    val transition = rememberInfiniteTransition(label = "live antenna pulse")
    val front by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1_400, easing = LinearEasing),
            repeatMode = ComposeRepeatMode.Restart,
        ),
        label = "center to edge",
    )
    return front
}

@Composable
private fun MetaTile(glyph: String, value: String, caption: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(14.dp)) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 7.dp), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(glyph, color = MaterialTheme.colorScheme.primary, fontSize = 12.sp)
                Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Text(caption, style = MaterialTheme.typography.labelSmall, maxLines = 1,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun BadgeText(text: String) {
    Surface(color = MaterialTheme.colorScheme.primary, shape = RoundedCornerShape(7.dp)) {
        Text(text, Modifier.padding(horizontal = 7.dp, vertical = 3.dp), color = Color.White,
            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun SmallBadge(text: String) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
        Text(text, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

@Composable
private fun SettingsToggle(title: String, subtitle: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Switch(checked = checked, onCheckedChange = onChecked)
        }
    }
}

@Composable
private fun SettingsInfo(text: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), shape = RoundedCornerShape(18.dp)) {
        Text(text, Modifier.fillMaxWidth().padding(16.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

private fun settingsGlyph(title: String) = when (title) {
    "Playback" -> "▶"
    "Appearance" -> "◉"
    "Network" -> "⌁"
    "Library" -> "▣"
    else -> "⚙"
}

private fun settingsSubtitle(title: String) = when (title) {
    "Playback" -> "Decoder, volume and queue behavior"
    "Appearance" -> "Theme and display options"
    "Network" -> "Proxy, timeouts and live streams"
    "Library" -> "Saved streams and playlists"
    else -> "Codec, performance and diagnostics"
}

private fun livePlaybackLabel(status: String): String = when {
    status.contains("Vocos", ignoreCase = true) -> "Vocos"
    status.contains("LIVE", ignoreCase = true) -> "LIVE"
    else -> "Connecting"
}

private fun formatTime(totalSeconds: Long): String {
    val hours = totalSeconds / 3_600
    val minutes = (totalSeconds % 3_600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

@Composable
private fun NowPlaying(state: PlayerState) {
    val live = state.live
    val item = state.current
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("NOW PLAYING", style = MaterialTheme.typography.labelSmall)
        Text(
            text = live?.title ?: item?.title ?: "Add .ecdc tracks or open a livestream",
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (live != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (live.buffering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                    )
                }
            Text(live.status, style = MaterialTheme.typography.bodySmall)
            }
            val details = buildList {
                live.bandwidthKbps?.let { add("${formatNumber(it)} kbps") }
                live.codebooks?.let { add("$it codebooks") }
                add("${live.bufferedSegments}/${live.targetBufferedSegments} queued")
                live.variant?.let { add(formatAudioFormat(it)) }
            }
            Text(details.joinToString(" • "), style = MaterialTheme.typography.bodySmall)
        } else if (item != null) {
            Text(
                "Track ${state.currentIndex + 1}/${state.playlist.size}",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "${formatBitrate(item.header)} • ${item.header.numCodebooks} codebooks • " +
                    formatAudioFormat(item.header),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun formatNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toInt().toString()
    else String.format(Locale.US, "%.2f", value).trimEnd('0').trimEnd('.')

private fun formatBitrate(header: EcdcHeader): String {
    val kbps = header.nominalBitrateBps / 1_000.0
    val number = if (header.nominalBitrateBps % 1_000 == 0) {
        (header.nominalBitrateBps / 1_000).toString()
    } else {
        String.format(Locale.US, "%.2f", kbps).trimEnd('0').trimEnd('.')
    }
    return "$number kbps"
}

private fun formatAudioFormat(header: EcdcHeader): String = formatAudioFormat(header.variant)

private fun formatAudioFormat(variant: EncodecVariant): String {
    val sampleRateKhz = variant.sampleRate / 1_000.0
    val sampleRate = if (sampleRateKhz % 1.0 == 0.0) {
        sampleRateKhz.toInt().toString()
    } else {
        formatNumber(sampleRateKhz)
    }
    val channelLayout = if (variant.channels == 1) "mono" else "stereo"
    return "$sampleRate kHz $channelLayout"
}
