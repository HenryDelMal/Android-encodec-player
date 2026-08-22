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
import androidx.activity.viewModels
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import com.henry.encodec.ecdc.EcdcHeader
import java.util.Locale

class MainActivity : ComponentActivity() {
    private val playerModel: PlayerViewModel by viewModels()

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
        }
    }

    companion object {
        const val ACTION_OPEN = "com.henry.encodec.player.OPEN"
        const val ACTION_PLAY_PAUSE = "com.henry.encodec.player.PLAY_PAUSE"
        const val ACTION_PREVIOUS = "com.henry.encodec.player.PREVIOUS"
        const val ACTION_NEXT = "com.henry.encodec.player.NEXT"
    }
}

@Composable
private fun PlayerScreen(model: PlayerViewModel) {
    val state by model.state.collectAsState()
    var sliderPosition by remember { mutableFloatStateOf(0f) }
    var draggingSlider by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var urlText by remember { mutableStateOf("https://") }
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
            OutlinedButton(
                enabled = state.playlist.isNotEmpty(),
                onClick = model::clearPlaylist,
            ) { Text("Clear") }
        }

        NowPlaying(state)

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

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                enabled = state.currentIndex > 0,
                onClick = model::previous,
            ) { Text("Previous") }
            Button(
                enabled = state.current != null,
                onClick = model::playPause,
            ) {
                Text(
                    when {
                        state.paused -> "Resume"
                        state.playing -> "Pause"
                        else -> "Play"
                    },
                )
            }
            OutlinedButton(
                enabled = state.playing || state.paused,
                onClick = model::stop,
            ) { Text("Stop") }
            OutlinedButton(
                enabled = state.currentIndex in 0 until state.playlist.lastIndex,
                onClick = model::next,
            ) { Text("Next") }
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
                val selected = index == state.currentIndex
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
                    Column(modifier = Modifier.padding(14.dp)) {
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
                }
            }
        }

        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }

    if (showUrlDialog) {
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("Open HTTPS stream") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Enter the direct HTTPS address of an .ecdc file.")
                    OutlinedTextField(
                        value = urlText,
                        onValueChange = { urlText = it },
                        label = { Text("HTTPS URL") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = urlText.trim().length > "https://".length,
                    onClick = {
                        model.addUrl(urlText)
                        showUrlDialog = false
                    },
                ) { Text("Add to playlist") }
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
    val item = state.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Now playing", style = MaterialTheme.typography.labelLarge)
        Text(
            text = item?.title ?: "Add .ecdc tracks to begin",
            style = MaterialTheme.typography.titleLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (item != null) {
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

private fun formatBitrate(header: EcdcHeader): String {
    val kbps = header.nominalBitrateBps / 1_000.0
    val number = if (header.nominalBitrateBps % 1_000 == 0) {
        (header.nominalBitrateBps / 1_000).toString()
    } else {
        String.format(Locale.US, "%.2f", kbps).trimEnd('0').trimEnd('.')
    }
    return "$number kbps"
}
