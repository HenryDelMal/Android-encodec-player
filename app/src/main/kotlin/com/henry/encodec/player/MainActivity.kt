package com.henry.encodec.player

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import com.henry.encodec.ecdc.EcdcHeader
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val playerModel: PlayerViewModel by lazy {
        (application as PlayerApplication).playerModel
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface { PlayerScreen(playerModel) } } }
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
private fun PlayerScreen(model: PlayerViewModel) {
    val state by model.state.collectAsState()
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var draggingSlider by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var urlText by remember { mutableStateOf(model.lastLiveUrl()) }
    LaunchedEffect(state.progress) {
        if (!draggingSlider) sliderPosition = state.progress
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) model.addToPlaylist(uris)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("EnCodec Player", style = MaterialTheme.typography.headlineMedium)

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                onClick = { picker.launch(arrayOf("*/*")) },
            ) { Text("Add files") }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = !state.addingUrl,
                onClick = { showUrlDialog = true },
            ) { Text(if (state.addingUrl) "Checking…" else "Open URL") }
            IconButton(
                enabled = state.playlist.isNotEmpty(),
                onClick = model::clearPlaylist,
            ) {
                Icon(
                    painterResource(android.R.drawable.ic_menu_delete),
                    contentDescription = "Delete all playlist items",
                )
            }
        }

        NowPlaying(state)

        if (state.live == null) {
            Slider(
                value = sliderPosition,
                enabled = state.current != null,
                onValueChange = {
                    draggingSlider = true
                    sliderPosition = it
                },
                onValueChangeFinished = {
                    model.seekToFraction(sliderPosition)
                    draggingSlider = false
                },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                val durationSeconds = state.current?.let {
                    it.header.audioLengthSamples / it.header.variant.sampleRate
                } ?: 0L
                Text(formatTime((durationSeconds * sliderPosition).toLong()))
                Text(formatTime(durationSeconds))
            }
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("LIVE", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(state.live?.sequence?.let { "Sequence $it" } ?: "Buffering")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                enabled = state.live == null && (state.currentIndex > 0 ||
                    (state.repeatMode == RepeatMode.LIST && state.playlist.isNotEmpty())),
                onClick = model::previous,
            ) {
                Icon(
                    painterResource(android.R.drawable.ic_media_previous),
                    contentDescription = "Previous",
                )
            }
            IconButton(
                enabled = state.current != null || state.live != null,
                onClick = model::playPause,
            ) {
                if (state.playing && !state.paused) {
                    Icon(painterResource(android.R.drawable.ic_media_pause), contentDescription = "Pause")
                } else {
                    Icon(painterResource(android.R.drawable.ic_media_play), contentDescription = "Play")
                }
            }
            IconButton(
                enabled = state.playing || state.paused,
                onClick = model::stop,
            ) {
                Icon(
                    painterResource(android.R.drawable.ic_menu_close_clear_cancel),
                    contentDescription = "Stop",
                )
            }
            IconButton(
                enabled = state.live == null && state.playlist.size > 1 &&
                    (state.shuffle || state.currentIndex < state.playlist.lastIndex ||
                        state.repeatMode == RepeatMode.LIST),
                onClick = model::next,
            ) {
                Icon(
                    painterResource(android.R.drawable.ic_media_next),
                    contentDescription = "Next",
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = state.live == null && state.playlist.isNotEmpty(),
                onClick = model::toggleShuffle,
            ) { Text(if (state.shuffle) "Shuffle: On" else "Shuffle: Off") }
            OutlinedButton(
                modifier = Modifier.weight(1f),
                enabled = state.live == null && state.playlist.isNotEmpty(),
                onClick = model::cycleRepeatMode,
            ) {
                Text(
                    when (state.repeatMode) {
                        RepeatMode.OFF -> "Loop: Off"
                        RepeatMode.TRACK -> "Loop: Track"
                        RepeatMode.LIST -> "Loop: List"
                    },
                )
            }
        }

        state.live?.let {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(modifier = Modifier.weight(1f), onClick = model::reconnectLive) {
                    Text("Reconnect")
                }
                OutlinedButton(modifier = Modifier.weight(1f), onClick = model::jumpToLive) {
                    Text("Jump to live")
                }
                OutlinedButton(modifier = Modifier.weight(1f), onClick = model::disconnectLive) {
                    Text("Disconnect")
                }
            }
        }

        Text(
            "Playlist (${state.playlist.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(state.playlist, key = { _, item -> item.uri.toString() }) { index, item ->
                val selected = state.live == null && index == state.currentIndex
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { model.selectTrack(index) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                    ),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${index + 1}. ${item.title}",
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                text = buildString {
                                    if (item.uri.scheme.equals("https", ignoreCase = true)) append("HTTPS stream • ")
                                    append("${formatBitrate(item.header)} • ")
                                    append("${item.header.numCodebooks} codebooks • ")
                                    append("48 kHz stereo")
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        IconButton(onClick = { model.removeTrack(index) }) {
                            Icon(
                                painterResource(android.R.drawable.ic_menu_delete),
                                contentDescription = "Remove ${item.title}",
                            )
                        }
                    }
                }
            }
        }

        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    if (showUrlDialog) {
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("Open URL") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter a finite HTTPS .ecdc URL or an HTTP(S) EnCodec live manifest URL.")
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
                Row {
                    TextButton(
                        enabled = urlText.trim().startsWith("https://", ignoreCase = true),
                        onClick = {
                            model.addUrl(urlText)
                            showUrlDialog = false
                        },
                    ) { Text("Add file") }
                    TextButton(
                        enabled = urlText.trim().let {
                            it.startsWith("http://", true) || it.startsWith("https://", true)
                        },
                        onClick = {
                            model.openLive(urlText)
                            showUrlDialog = false
                        },
                    ) { Text("Open livestream") }
                }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false }) { Text("Cancel") }
            },
        )
    }
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
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Now playing", style = MaterialTheme.typography.labelLarge)
        Text(
            text = live?.title ?: item?.title ?: "Add .ecdc tracks or open a livestream",
            style = MaterialTheme.typography.titleLarge,
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
                Text(live.status, style = MaterialTheme.typography.bodyMedium)
            }
            val details = buildList {
                live.bandwidthKbps?.let { add("${formatNumber(it)} kbps") }
                live.codebooks?.let { add("$it codebooks") }
                add("${live.bufferedSegments}/${live.targetBufferedSegments} buffered")
                add("48 kHz stereo")
            }
            Text(details.joinToString(" • "), style = MaterialTheme.typography.bodySmall)
        } else if (item != null) {
            Text(
                "Track ${state.currentIndex + 1} of ${state.playlist.size}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "${formatBitrate(item.header)} • ${item.header.numCodebooks} codebooks • 48 kHz stereo",
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
