package com.music.bitchord.data.canvas

import com.music.bitchord.data.DebugLog as Log
import com.music.bitchord.data.Http
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Result representing official Apple Music artwork and optional motion canvas for a track.
 */
data class AppleArtwork(
    val artworkUrl: String,
    val motionCanvas: CanvasArtwork? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
)

/**
 * Resolves official studio album artwork and motion covers from Apple Music.
 *
 * Caches results in an LRU memory cache so re-visits on TV or queue lookups
 * resolve synchronously without re-querying the network.
 */
object AppleArtworkRepository {

    private const val TAG = "AppleArtworkRepository"
    private const val CACHE_SIZE = 128

    private class Entry(val artwork: AppleArtwork?)

    private val cache = object : LinkedHashMap<String, Entry>(CACHE_SIZE, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>) =
            size > CACHE_SIZE
    }

    private val lock = Mutex()

    /**
     * Synchronously returns cached Apple artwork if already resolved, or null.
     */
    fun cached(song: Song?): AppleArtwork? {
        if (song == null) return null
        val key = cacheKey(song)
        return synchronized(cache) { cache[key]?.artwork }
    }

    /**
     * Resolves Apple Artwork (high-res official album cover and motion video if available).
     * Guaranteed never to throw.
     */
    suspend fun artworkFor(song: Song?): AppleArtwork? {
        if (song == null) return null
        if (song.videoId.isBlank() && song.title.isBlank()) return null

        val rawTitle = song.title.cleanedTrackTitle()
        val rawArtist = song.artist.cleanedTrackTitle()
        if (rawTitle.isBlank() && rawArtist.isBlank()) return null

        val key = cacheKey(song)
        synchronized(cache) {
            val entry = cache[key]
            if (entry != null) return entry.artwork
        }

        return lock.withLock {
            synchronized(cache) {
                val entry = cache[key]
                if (entry != null) return entry.artwork
            }

            val resolved = withContext(Dispatchers.IO) {
                runCatching { resolve(rawTitle, rawArtist, song.albumName) }
                    .onFailure { Log.d(TAG, "lookup failed for $rawTitle by $rawArtist: $it") }
                    .getOrNull()
            }

            synchronized(cache) {
                cache[key] = Entry(resolved)
            }
            resolved
        }
    }

    private fun cacheKey(song: Song): String =
        if (song.videoId.isNotBlank()) "song|${song.videoId}" else "song|${song.title}|${song.artist}"

    private fun resolve(title: String, artist: String, album: String?): AppleArtwork? {
        // 1. Check AppleMusicCanvas to see if an Apple Music motion cover exists
        val motion = runCatching { AppleMusicCanvas.search(title, artist, album) }.getOrNull()

        // 2. Fetch official Apple Music studio album art from iTunes Search API
        val term = buildString {
            if (!title.contains(artist, ignoreCase = true)) append(artist).append(' ')
            append(title)
            if (!album.isNullOrBlank() && !title.contains(album, ignoreCase = true)) {
                append(' ').append(album)
            }
        }.trim()

        val url = "https://itunes.apple.com/search?term=${URLEncoder.encode(term, "UTF-8")}&entity=song&limit=5"
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .build()

        val body = runCatching {
            Http.client.newCall(request).execute().use { it.body?.string() }
        }.getOrNull()

        if (body != null) {
            val root = runCatching { JSONObject(body) }.getOrNull()
            val results = root?.optJSONArray("results")
            if (results != null) {
                val wantedTitle = title.normalizeForMatch()
                val wantedArtists = splitArtists(artist)
                val wantedAlbum = album?.normalizeForMatch()

                for (i in 0 until results.length()) {
                    val r = results.optJSONObject(i) ?: continue
                    val trackName = r.optString("trackName").normalizeForMatch()
                    val collectionName = r.optString("collectionName").normalizeForMatch()

                    // Match validation
                    val titleOk = trackName.contains(wantedTitle) || wantedTitle.contains(trackName) ||
                        trackName.replace(" ", "") == wantedTitle.replace(" ", "")
                    val artistOk = wantedArtists.isEmpty() || wantedArtists.any { want ->
                        splitArtists(r.optString("artistName")).any { credited ->
                            credited.contains(want) || want.contains(credited)
                        }
                    }
                    val albumOk = wantedAlbum.isNullOrBlank() || collectionName.isBlank() ||
                        collectionName.contains(wantedAlbum) || wantedAlbum.contains(collectionName)

                    if (titleOk && artistOk) {
                        val raw100 = r.optString("artworkUrl100")
                        if (raw100.isNotBlank()) {
                            val highRes = raw100
                                .replace("100x100bb", "1000x1000bb")
                                .replace("60x60bb", "1000x1000bb")
                            Log.d(TAG, "resolved Apple artwork for '$title' -> $highRes (motion: ${motion != null})")
                            return AppleArtwork(
                                artworkUrl = highRes,
                                motionCanvas = motion,
                                title = r.optString("trackName"),
                                artist = r.optString("artistName"),
                                album = r.optString("collectionName"),
                            )
                        }
                    }
                }
            }
        }

        // If motion exists even if iTunes search had no hit:
        if (motion != null) {
            return AppleArtwork(
                artworkUrl = "",
                motionCanvas = motion,
                title = motion.title,
                artist = motion.artist,
                album = motion.album,
            )
        }

        return null
    }
}

private val TRACK_NOISE = Regex(
    """\((?:from|official|lyrical|video|audio)[^)]*\)|\[[^]]*]|""" +
        """\b(?:official (?:video|audio|music video)|lyrical|full song|4k video)\b""",
    RegexOption.IGNORE_CASE,
)

fun String.cleanedTrackTitle(): String = replace(TRACK_NOISE, " ")
    .substringBefore(" | ")
    .replace(Regex("\\s+"), " ")
    .trim()
    .ifBlank { this }
