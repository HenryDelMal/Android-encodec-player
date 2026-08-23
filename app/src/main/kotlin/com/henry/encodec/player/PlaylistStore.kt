package com.henry.encodec.player

import android.content.Context
import android.net.Uri
import com.henry.encodec.ecdc.EcdcHeader
import com.henry.encodec.ecdc.EncodecVariant
import org.json.JSONArray
import org.json.JSONObject

class PlaylistStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun load(): PlayerState = runCatching {
        val raw = preferences.getString(PLAYLIST_KEY, null) ?: return PlayerState()
        val root = JSONObject(raw)
        val itemsJson = root.optJSONArray("items") ?: JSONArray()
        val items = buildList {
            for (index in 0 until itemsJson.length()) {
                val item = itemsJson.getJSONObject(index)
                val variant = EncodecVariant.fromWireName(item.getString("variant"))
                val header = EcdcHeader(
                    version = item.getInt("version"),
                    variant = variant,
                    audioLengthSamples = item.getLong("audioLengthSamples"),
                    numCodebooks = item.getInt("numCodebooks"),
                    usesLanguageModel = item.getBoolean("usesLanguageModel"),
                )
                if (variant == EncodecVariant.STEREO_48_KHZ && !header.usesLanguageModel) {
                    add(
                        PlaylistItem(
                            uri = Uri.parse(item.getString("uri")),
                            title = item.getString("title"),
                            header = header,
                        ),
                    )
                }
            }
        }
        val storedIndex = root.optInt("currentIndex", 0)
        val repeatMode = runCatching {
            RepeatMode.valueOf(root.optString("repeatMode", RepeatMode.OFF.name))
        }.getOrDefault(RepeatMode.OFF)
        PlayerState(
            playlist = items,
            currentIndex = if (items.isEmpty()) -1 else storedIndex.coerceIn(items.indices),
            shuffle = root.optBoolean("shuffle", false),
            repeatMode = repeatMode,
        )
    }.getOrElse { PlayerState() }

    fun save(state: PlayerState) {
        val items = JSONArray()
        state.playlist.forEach { playlistItem ->
            items.put(
                JSONObject()
                    .put("uri", playlistItem.uri.toString())
                    .put("title", playlistItem.title)
                    .put("version", playlistItem.header.version)
                    .put("variant", playlistItem.header.variant.wireName)
                    .put("audioLengthSamples", playlistItem.header.audioLengthSamples)
                    .put("numCodebooks", playlistItem.header.numCodebooks)
                    .put("usesLanguageModel", playlistItem.header.usesLanguageModel),
            )
        }
        val root = JSONObject()
            .put("items", items)
            .put("currentIndex", state.currentIndex)
            .put("shuffle", state.shuffle)
            .put("repeatMode", state.repeatMode.name)
        preferences.edit().putString(PLAYLIST_KEY, root.toString()).apply()
    }

    fun loadLastLiveUrl(): String =
        (preferences.getString(LAST_LIVE_URL_KEY, "") ?: "")
            .takeUnless { it == "https://example.com/stream/stream.json" }
            .orEmpty()

    fun saveLastLiveUrl(url: String) {
        preferences.edit().putString(LAST_LIVE_URL_KEY, url).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "encodec_player"
        const val PLAYLIST_KEY = "saved_playlist_v1"
        const val LAST_LIVE_URL_KEY = "last_live_url"
    }
}
