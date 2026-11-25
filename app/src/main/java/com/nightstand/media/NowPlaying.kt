package com.nightstand.media

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.nightstand.NotificationFeed
import com.nightstand.WatcherService

/**
 * Follows the system's active media sessions (needs the Notification access
 * Nightstand already has) and picks the one to show: a playing session first,
 * Spotify preferred, otherwise the most recent paused one. Player-agnostic, so
 * YouTube Music works too. Controls go through the session's TransportControls.
 */
class NowPlaying(private val context: Context, private val onChange: () -> Unit) {

    data class Track(
        val pkg: String, val appLabel: String,
        val title: String, val artist: String,
        val art: Bitmap?, val durationMs: Long,
    )

    private val main = Handler(Looper.getMainLooper())
    private val msm = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, NotificationFeed::class.java)
    private var controller: MediaController? = null
    var track: Track? = null; internal set
    var state: PlaybackState? = null; internal set
    /** Preview/test override for [hasSession]. */
    internal var fakeSession = false

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }
    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(m: MediaMetadata?) { readTrack(); onChange() }
        override fun onPlaybackStateChanged(s: PlaybackState?) { state = s; onChange() }
        override fun onSessionDestroyed() { pick(sessions()) }
    }

    fun start() {
        try {
            msm.addOnActiveSessionsChangedListener(sessionsListener, component, main)
            pick(sessions())
        } catch (e: SecurityException) {
            WatcherService.log("now playing: notification access missing")
        }
    }

    fun stop() {
        runCatching { msm.removeOnActiveSessionsChangedListener(sessionsListener) }
        controller?.unregisterCallback(callback)
        controller = null
    }

    val isPlaying get() = state?.state == PlaybackState.STATE_PLAYING
    val hasSession get() = controller != null || fakeSession

    /** Position now, extrapolated from the last update (no polling needed). */
    fun positionMs(): Long {
        val s = state ?: return 0
        if (s.state != PlaybackState.STATE_PLAYING) return s.position
        val dt = SystemClock.elapsedRealtime() - s.lastPositionUpdateTime
        return (s.position + dt * s.playbackSpeed).toLong().coerceAtLeast(0)
    }

    fun playPause() = controller?.transportControls?.let { if (isPlaying) it.pause() else it.play() }
    fun next() = controller?.transportControls?.skipToNext()
    fun previous() = controller?.transportControls?.skipToPrevious()

    private fun sessions(): List<MediaController> =
        runCatching { msm.getActiveSessions(component) }.getOrDefault(emptyList())

    private fun pick(list: List<MediaController>?) {
        val all = list.orEmpty()
        val best = all.sortedWith(compareByDescending<MediaController> {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        }.thenByDescending { it.packageName == SPOTIFY }).firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback, main)
        }
        state = best?.playbackState
        readTrack()
        onChange()
    }

    private fun readTrack() {
        val c = controller
        val m = c?.metadata
        if (c == null || m == null) { track = null; return }
        val pm = context.packageManager
        track = Track(
            pkg = c.packageName,
            appLabel = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(c.packageName, 0)).toString() }
                .getOrDefault(c.packageName),
            title = m.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: "",
            artist = m.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: m.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: "",
            art = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: m.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
                ?: artFromUri(m),
            durationMs = m.getLong(MediaMetadata.METADATA_KEY_DURATION),
        )
    }

    private fun artFromUri(m: MediaMetadata): Bitmap? {
        val s = m.getString(MediaMetadata.METADATA_KEY_ALBUM_ART_URI)
            ?: m.getString(MediaMetadata.METADATA_KEY_ART_URI) ?: return null
        val uri = Uri.parse(s)
        if (uri.scheme !in setOf("content", "android.resource", "file")) return null
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }.getOrNull()
    }

    companion object {
        const val SPOTIFY = "com.spotify.music"

        fun format(ms: Long): String {
            val s = (ms / 1000).coerceAtLeast(0)
            return "%d:%02d".format(s / 60, s % 60)
        }
    }
}
