package com.aeriotv.android.core.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.media.session.MediaButtonReceiver
import com.aeriotv.android.MainActivity
import com.aeriotv.android.R
import com.aeriotv.android.core.network.PlaylistFetcher
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that keeps the held MPV instance alive after the user
 * backs out of the fullscreen player. Without a foreground service Android
 * eventually frees the process and the audio cuts.
 *
 * Uses `foregroundServiceType=mediaPlayback` already declared in the manifest
 * for the DVR recording service — same type fits this use case.
 *
 * Notification surfaces a play/pause action that toggles MPV pause via the
 * [MPVPlayerHolder] singleton, shows the channel logo as the large icon, and
 * routes tap → MainActivity (SINGLE_TOP, so it resumes the running stream).
 *
 * Phase 143: wrapped in [NotificationCompat.MediaStyle] backed by a
 * [MediaSessionCompat]. That unlocks four real wins beyond the previous
 * plain-text notification:
 *   1. Lock-screen artwork (full-bleed channel logo) on phones.
 *   2. Bluetooth headset/headphone play-pause buttons route through the
 *      session callback to MPVPlayerHolder.setPaused (no app focus required).
 *   3. Android Auto / Wear / Assistant surface the session metadata + actions.
 *   4. System media-route picker (Cast button on the QS panel, "now playing"
 *      tile) reflects AerioTV.
 *
 * The session is created in onCreate and released in onDestroy, so its
 * lifetime exactly mirrors the foreground service.
 */
@AndroidEntryPoint
class PlaybackService : Service() {

    @Inject lateinit var holder: MPVPlayerHolder
    @Inject lateinit var fetcher: PlaylistFetcher

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var mediaSession: MediaSessionCompat

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // MediaButtonReceiver is the recommended bridge for routing hardware
        // media keys / BT events to the service while it's running. The PI
        // wires the receiver back to THIS service via ACTION_MEDIA_BUTTON.
        val mbrIntent = Intent(Intent.ACTION_MEDIA_BUTTON).apply {
            setClass(this@PlaybackService, MediaButtonReceiver::class.java)
        }
        val mbrPi = PendingIntent.getBroadcast(
            this, 0, mbrIntent, PendingIntent.FLAG_IMMUTABLE,
        )
        mediaSession = MediaSessionCompat(this, "AerioTVPlayback").apply {
            setMediaButtonReceiver(mbrPi)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { holder.setPaused(false); updateState() }
                override fun onPause() { holder.setPaused(true); updateState() }
                override fun onStop() {
                    // BT "stop" / lock-screen dismiss: drop the foreground
                    // notification. Keep MPV alive: the user may still want
                    // to resume from MainActivity. Same contract as
                    // ACTION_STOP below.
                    stopForegroundCompat()
                    stopSelf()
                }
            })
            // Active session can be controlled even when the app isn't in
            // foreground — required for headset media keys to land here.
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val title = intent.getStringExtra(EXTRA_TITLE) ?: "AerioTV"
                val subtitle = intent.getStringExtra(EXTRA_SUBTITLE).orEmpty()
                val logoUrl = intent.getStringExtra(EXTRA_LOGO)
                ensureChannel()
                // Seed the session with metadata for the new channel BEFORE
                // foregrounding so the first notification render already has
                // a populated MediaStyle row instead of "AerioTV / blank".
                updateMetadata(title, subtitle, null)
                updateState()
                startForegroundCompat(buildNotification(title, subtitle, null))
                if (!logoUrl.isNullOrBlank()) {
                    scope.launch {
                        val bmp = runCatching {
                            val bytes = fetcher.fetchBytes(logoUrl)
                            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        }.getOrNull()
                        if (bmp != null) {
                            updateMetadata(title, subtitle, bmp)
                            getSystemService(NotificationManager::class.java)
                                .notify(NOTIF_ID, buildNotification(title, subtitle, bmp))
                        }
                    }
                }
            }
            ACTION_TOGGLE_PAUSE -> {
                val nowPaused = holder.isPaused()
                holder.setPaused(!nowPaused)
                updateState()
            }
            ACTION_STOP -> {
                // CRITICAL: do NOT call holder.destroy() here. This service
                // entry point fires from BOTH:
                //   1. The mini-player Dismiss button (user wants MPV gone).
                //   2. Every PlayerScreen mount (LaunchedEffect(Unit) ->
                //      PlaybackService.stop, telling the bg service to drop
                //      its notification because we're foreground again).
                // Case 2 wants to keep MPV alive -- we just turned video on
                // and called playFile microseconds earlier; destroying MPV
                // here is exactly the "stream loads chrome but never plays"
                // regression. Case 1 already calls mpvHolder.destroy()
                // explicitly in MiniPlayerRow.onDismiss before invoking
                // this action, so MPV teardown is owned by the caller, not
                // by ACTION_STOP. ACTION_STOP is just "remove notification +
                // exit foreground service" from now on.
                stopForegroundCompat()
                stopSelf()
            }
            else -> {
                // Hand off ACTION_MEDIA_BUTTON broadcasts to the session so
                // BT headset / steering-wheel keys route through onPlay/onPause.
                MediaButtonReceiver.handleIntent(mediaSession, intent)
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        mediaSession.isActive = false
        mediaSession.release()
        scope.cancel()
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                NOTIF_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Background playback",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Ongoing notification while AerioTV plays audio in the background."
            setShowBadge(false)
        }
        mgr.createNotificationChannel(channel)
    }

    /**
     * Push title / subtitle / art into the session. The system uses this for
     * lock-screen artwork on phones + the "now playing" row in QS.
     */
    private fun updateMetadata(title: String, subtitle: String, art: Bitmap?) {
        val md = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, subtitle)
            .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, subtitle)
        if (art != null) {
            md.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, art)
            md.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
            md.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, art)
        }
        mediaSession.setMetadata(md.build())
    }

    /**
     * Sync session state to MPV's pause flag so the lock-screen button shows
     * the right glyph and external controllers know whether to send PLAY or
     * PAUSE next. Position/duration are 0 — live TV has no scrubbable
     * timeline, and that's the right signal to send to controllers (no seek
     * bar offered).
     */
    private fun updateState() {
        val paused = runCatching { holder.isPaused() }.getOrDefault(false)
        val state = if (paused) PlaybackStateCompat.STATE_PAUSED else PlaybackStateCompat.STATE_PLAYING
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_STOP
        val sb = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(state, 0L, 1.0f)
            .build()
        mediaSession.setPlaybackState(sb)
    }

    private fun buildNotification(title: String, subtitle: String, largeIcon: Bitmap?): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            // SINGLE_TOP (not CLEAR_TOP) so tapping the notification RESUMES the
            // existing activity via onNewIntent -- the player + its stream are
            // still composed in the background -- instead of recreating it.
            // CLEAR_TOP on a standard/singleTop activity tears the task down and
            // relaunches fresh, which is what reopened the app instead of jumping
            // back into the running stream.
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val pauseToggle = PendingIntent.getService(
            this, 1,
            Intent(this, PlaybackService::class.java).setAction(ACTION_TOGGLE_PAUSE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 2,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val paused = runCatching { holder.isPaused() }.getOrDefault(false)
        val playPauseIcon =
            if (paused) android.R.drawable.ic_media_play else android.R.drawable.ic_media_pause
        val playPauseLabel = if (paused) "Play" else "Pause"
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(subtitle.ifBlank { "Playing in background" })
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            // Show full content on the lock screen so audio-only background
            // playback has reachable controls there, not just in the shade.
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(openPi)
            .addAction(playPauseIcon, playPauseLabel, pauseToggle)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stop)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession.sessionToken)
                    // Compact view (lock screen + collapsed Heads-Up) shows
                    // index 0 (play/pause). Stop stays in the expanded view.
                    .setShowActionsInCompactView(0),
            )
        if (largeIcon != null) builder.setLargeIcon(largeIcon)
        return builder.build()
    }

    companion object {
        const val ACTION_START = "com.aeriotv.android.PLAYBACK_START"
        const val ACTION_TOGGLE_PAUSE = "com.aeriotv.android.PLAYBACK_TOGGLE_PAUSE"
        const val ACTION_STOP = "com.aeriotv.android.PLAYBACK_STOP"
        const val EXTRA_TITLE = "title"
        const val EXTRA_SUBTITLE = "subtitle"
        const val EXTRA_LOGO = "logo"
        private const val CHANNEL_ID = "aeriotv_background_playback"
        private const val NOTIF_ID = 0xAF

        fun startBackground(context: Context, title: String, subtitle: String, logoUrl: String? = null) {
            val intent = Intent(context, PlaybackService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_SUBTITLE, subtitle)
                .putExtra(EXTRA_LOGO, logoUrl)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, PlaybackService::class.java).setAction(ACTION_STOP)
            context.startService(intent)
        }
    }
}
