package com.aeriotv.android.core.cast

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.aeriotv.android.MainActivity
import com.aeriotv.android.R
import com.aeriotv.android.core.playback.AerioMediaPlaybackService
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import com.aeriotv.android.core.data.guideMatchKey
import com.aeriotv.android.feature.playlist.nowPlaying
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Process-scoped "Casting to <TV>" notification (GH #33). The media foreground-
 * service notification (AerioMediaPlaybackService) can't cover the cast case: the
 * phone isn't playing locally while casting, so a mediaPlayback FGS isn't valid,
 * and after a force-close the service is dead with nothing to restart it. This
 * standalone (non-FGS) ongoing notification is driven purely by cast STATE, so it
 * re-posts whenever the process is alive and casting -- including right after the
 * app is reopened and Play-services resumes the session. Tapping it returns to
 * the app (the Now-Casting mini controller / remote).
 *
 * Only fires on the SENDER (a phone that initiated a cast); an Android-TV receiver
 * is never a sender, so [AerioCastSender.state] there is never Connected.
 */
@Singleton
class CastNotificationController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val castSender: AerioCastSender,
    private val multiviewCast: com.aeriotv.android.core.cast.multiview.MultiviewCastController,
    private val repository: com.aeriotv.android.core.data.repository.PlaylistRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var started = false
    private var wasCasting = false

    /** Idempotent; called once from Application.onCreate. */
    fun start() {
        if (started) return
        started = true
        ensureChannel()
        // Cancel any chip left over from a PRIOR process death: this is a plain
        // notify() (not an FGS notification), so it survives an OS kill. If the
        // cast ended while we were dead, no true->false transition will fire to
        // clear it, so clear up front -- the collector re-posts immediately if a
        // session is genuinely still active/resuming.
        clear()
        scope.launch {
            // An involuntary drop (network loss, receiver death) otherwise just
            // makes the ongoing chip vanish -- Kenton 2026-08-18: TV on the idle
            // screen, phone showing nothing. Tell the user what happened with a
            // normal-priority, auto-cancel notification; tapping it reopens the
            // app, where the cast button offers the device again.
            castSender.involuntaryEnd.collect { end -> postDisconnected(end) }
        }
        scope.launch {
            // Inputs of the one casting notification: the cast state and
            // content, the receiver's play state, the composite session (audio
            // focus and tiles), whether the relay FGS owns the notification
            // id, and a minute tick so the program line rolls over.
            val minuteTick = kotlinx.coroutines.flow.flow {
                while (true) {
                    emit(System.currentTimeMillis() / 60_000L)
                    kotlinx.coroutines.delay(60_000L - System.currentTimeMillis() % 60_000L)
                }
            }
            combine(
                combine(castSender.state, castSender.content, castSender.isPlaying) { s, c, p -> Triple(s, c, p) },
                multiviewCast.session,
                com.aeriotv.android.core.cast.hlsproxy.CastHlsProxyService.foreground,
                minuteTick,
            ) { (state, content, playing), composite, fgs, _ -> Inputs(state, content, playing, composite, fgs) }
                .collectLatest { input ->
                    val casting = input.state is AerioCastSender.State.Connected && input.content != null
                    if (casting) {
                        // Entering a cast: the phone stops local playback, so the
                        // media FGS notification is stale/invalid -- retire it so
                        // this standalone chip is the single casting indicator.
                        if (!wasCasting) runCatching { AerioMediaPlaybackService.stop(context) }
                        wasCasting = true
                        val details = resolveDetails(input)
                        post(details, input)
                    } else if (wasCasting) {
                        wasCasting = false
                        clear()
                    }
                }
        }
    }

    private data class Inputs(
        val state: AerioCastSender.State,
        val content: AerioCastSender.Content?,
        val playing: Boolean,
        val composite: com.aeriotv.android.core.cast.multiview.MultiviewCastController.Session?,
        val proxyForeground: Boolean,
    )

    /** What the casting card shows. Single channel: the channel, its current
     *  program, its logo. Composited Multiview (Logan 2026-10-08): "Multiview",
     *  the audio-focused tile's channel, that channel's current program, that
     *  channel's logo. A composite dropped to one tile reads as that channel,
     *  like the in-app cast card. */
    private data class Details(
        val title: String,
        val subtitle: String?,
        val program: String?,
        val artUrl: String?,
        val deviceName: String?,
        val key: String,
    )

    private suspend fun resolveDetails(input: Inputs): Details {
        val content = input.content!!
        val device = (input.state as? AerioCastSender.State.Connected)?.deviceName
        val composite = input.composite.takeIf {
            content.mediaId == com.aeriotv.android.core.cast.multiview.MultiviewCastController.MEDIA_ID
        }
        if (composite != null) {
            val focused = composite.tiles.getOrNull(composite.focused) ?: composite.tiles.firstOrNull()
            val program = focused?.let { programTitle(it.id) }
            val single = composite.singleChannelName
            return Details(
                title = single ?: com.aeriotv.android.core.cast.multiview.MultiviewCastController.TITLE,
                // The shade's media player draws only title and artist, so the
                // focused channel and its program share the subtitle line.
                subtitle = if (single != null) program else
                    listOfNotNull(focused?.displayName, program).joinToString(" \u00b7 ").ifBlank { null },
                program = null,
                artUrl = focused?.logoUrl?.takeIf { it.isNotBlank() },
                deviceName = device,
                key = "mv:${focused?.id}",
            )
        }
        val channel = channelFor(content.mediaId)
        val program = if (content.kind == AerioCastReceiverController.Kind.LIVE) {
            programTitle(content.mediaId) ?: content.subtitle
        } else content.subtitle
        return Details(
            title = content.title.ifBlank { "Now casting" },
            subtitle = program?.takeIf { it.isNotBlank() },
            program = null,
            artUrl = content.artUri?.takeIf { it.isNotBlank() } ?: channel?.tvgLogo?.takeIf { it.isNotBlank() },
            deviceName = device,
            key = "ch:${content.mediaId}",
        )
    }

    // ---- guide and logo lookups (cached; the card re-resolves each minute) ----

    private var channelsPlaylistId: String? = null
    private var channelsById: Map<String, com.aeriotv.android.core.data.M3UChannel> = emptyMap()

    private suspend fun channelFor(id: String): com.aeriotv.android.core.data.M3UChannel? = runCatching {
        val playlist = repository.activePlaylist() ?: return@runCatching null
        if (playlist.id != channelsPlaylistId) {
            channelsById = repository.loadCachedChannels(playlist.id).associateBy { it.id }
            channelsPlaylistId = playlist.id
        }
        channelsById[id] ?: channelsById[id.substringAfter(':', id)]
    }.getOrNull()

    /** The channel's currently airing program title from the cached guide
     *  (same rows and canonical key the guide and the cast card read). */
    private suspend fun programTitle(channelId: String): String? = runCatching {
        val playlist = repository.activePlaylist() ?: return@runCatching null
        val channel = channelFor(channelId) ?: return@runCatching null
        val now = System.currentTimeMillis()
        val key = channel.guideMatchKey
        repository.loadCachedEpg(playlist.id, now, now)
            .filter { it.channelId == key }
            .nowPlaying(now)
            ?.title?.trim()?.takeIf { it.isNotEmpty() }
    }.getOrNull()

    private var artCacheUrl: String? = null
    private var artCacheBitmap: android.graphics.Bitmap? = null

    /** The channel logo through the app's Coil loader (the cache the guide,
     *  the cast card and the composite tiles use). */
    private suspend fun artwork(url: String?): android.graphics.Bitmap? {
        if (url.isNullOrBlank()) return null
        if (url == artCacheUrl && artCacheBitmap != null) return artCacheBitmap
        val bmp = runCatching {
            val loader = coil3.SingletonImageLoader.get(context)
            val req = coil3.request.ImageRequest.Builder(context).data(url).build()
            loader.execute(req).image?.let {
                com.aeriotv.android.feature.multiview.TileLogoCrop.softwareBitmap(it)
            }
        }.onFailure { android.util.Log.w("CastNotif", "[Cast] notification art failed url=$url", it) }
            .getOrNull()
        android.util.Log.i("CastNotif", "[Cast] notification art url=$url bitmap=${bmp?.let { "${it.width}x${it.height}" }}")
        artCacheUrl = url
        artCacheBitmap = bmp
        return bmp
    }

    // ---- media session ----

    private var mediaSession: MediaSessionCompat? = null
    private var volumeProvider: androidx.media.VolumeProviderCompat? = null

    private fun session(): MediaSessionCompat {
        mediaSession?.let { return it }
        val s = MediaSessionCompat(context, "AerioTVCast").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() = castSender.play()
                override fun onPause() = castSender.pause()
                override fun onStop() = castSender.stopPlayback()
                // The Android 13+ shade media player draws buttons from the
                // PlaybackState (not the notification actions), so Stop rides
                // there as a custom action.
                override fun onCustomAction(action: String?, extras: android.os.Bundle?) {
                    if (action == CUSTOM_STOP) castSender.stopPlayback()
                }
            })
            setSessionActivity(launchIntent())
        }
        // Remote playback: the card's volume drives the TV, as the system's
        // own cast card did.
        val vp = object : androidx.media.VolumeProviderCompat(
            VOLUME_CONTROL_ABSOLUTE, 100,
            ((castSender.deviceVolume() ?: 0.5) * 100).toInt(),
        ) {
            override fun onSetVolumeTo(volume: Int) {
                castSender.setDeviceVolume(volume / 100.0)
                currentVolume = volume
            }
            override fun onAdjustVolume(direction: Int) {
                val next = (currentVolume + direction * 5).coerceIn(0, 100)
                castSender.setDeviceVolume(next / 100.0)
                currentVolume = next
            }
        }
        s.setPlaybackToRemote(vp)
        volumeProvider = vp
        mediaSession = s
        return s
    }

    private fun releaseSession() {
        mediaSession?.let { runCatching { it.isActive = false; it.release() } }
        mediaSession = null
        volumeProvider = null
    }

    private fun launchIntent(): PendingIntent = PendingIntent.getActivity(
        context,
        REQ_CODE,
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private var actionReceiverRegistered = false

    private fun ensureActionReceiver() {
        if (actionReceiverRegistered) return
        actionReceiverRegistered = true
        val filter = android.content.IntentFilter().apply {
            addAction(ACTION_PLAY); addAction(ACTION_PAUSE); addAction(ACTION_STOP)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            context,
            object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    when (intent.action) {
                        ACTION_PLAY -> castSender.play()
                        ACTION_PAUSE -> castSender.pause()
                        ACTION_STOP -> castSender.stopPlayback()
                    }
                }
            },
            filter,
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private fun actionIntent(action: String, req: Int): PendingIntent = PendingIntent.getBroadcast(
        context, req, Intent(action).setPackage(context.packageName),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private suspend fun post(details: Details, input: Inputs) {
        ensureActionReceiver()
        val art = artwork(details.artUrl)
        android.util.Log.i("CastNotif", "[Cast] notification post title=${details.title} sub=${details.subtitle} program=${details.program} art=${art != null} artUrl=${details.artUrl} playing=${input.playing}")
        val session = session()
        session.setMetadata(
            MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_MEDIA_ID, details.key)
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, details.title)
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, details.title)
                .apply {
                    details.subtitle?.let {
                        putString(MediaMetadataCompat.METADATA_KEY_ARTIST, it)
                        putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, it)
                    }
                    details.program?.let {
                        putString(MediaMetadataCompat.METADATA_KEY_ALBUM, it)
                        putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_DESCRIPTION, it)
                    }
                    art?.let {
                        putBitmap(MediaMetadataCompat.METADATA_KEY_ART, it)
                        putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, it)
                    }
                }
                .build(),
        )
        session.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or PlaybackStateCompat.ACTION_STOP,
                )
                .addCustomAction(
                    PlaybackStateCompat.CustomAction.Builder(
                        CUSTOM_STOP, "Stop", android.R.drawable.ic_menu_close_clear_cancel,
                    ).build(),
                )
                .setState(
                    if (input.playing) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED,
                    PlaybackStateCompat.PLAYBACK_POSITION_UNKNOWN,
                    1f,
                )
                .build(),
        )
        castSender.deviceVolume()?.let { v -> volumeProvider?.currentVolume = (v * 100).toInt() }
        if (!session.isActive) session.isActive = true

        val casting = if (!details.deviceName.isNullOrBlank()) "Casting to ${details.deviceName}" else "Casting"
        val lines = listOfNotNull(details.subtitle, details.program, casting)
        val playPause = if (input.playing) {
            NotificationCompat.Action(android.R.drawable.ic_media_pause, "Pause", actionIntent(ACTION_PAUSE, REQ_CODE + 1))
        } else {
            NotificationCompat.Action(android.R.drawable.ic_media_play, "Play", actionIntent(ACTION_PLAY, REQ_CODE + 2))
        }
        val stop = NotificationCompat.Action(
            android.R.drawable.ic_menu_close_clear_cancel, "Stop", actionIntent(ACTION_STOP, REQ_CODE + 3),
        )
        val notif = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(details.title)
            .setContentText(lines.first())
            .setSubText(casting.takeIf { lines.size > 1 })
            .apply { art?.let { setLargeIcon(it) } }
            .addAction(playPause)
            .addAction(stop)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(session.sessionToken)
                    .setShowActionsInCompactView(0, 1),
            )
            .setContentIntent(launchIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        latestNotification = notif
        val mgr = NotificationManagerCompat.from(context)
        // One AerioTV notification (Logan 2026-10-08): while the relay service
        // is foreground its required notification id carries this card and
        // the standalone chip is withdrawn; otherwise (Cast Connect to the
        // Android TV app, no relay) the chip id carries it.
        runCatching {
            if (input.proxyForeground) {
                mgr.cancel(NOTIF_ID)
                mgr.notify(com.aeriotv.android.core.cast.hlsproxy.CastHlsProxyService.NOTIF_ID, notif)
            } else {
                mgr.notify(NOTIF_ID, notif)
            }
        }
    }

    private fun postDisconnected(end: AerioCastSender.InvoluntaryEnd) {
        val launchPi = PendingIntent.getActivity(
            context,
            REQ_CODE,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val title = if (!end.deviceName.isNullOrBlank()) {
            "Casting to ${end.deviceName} disconnected"
        } else {
            "Casting disconnected"
        }
        val body = buildString {
            if (!end.contentTitle.isNullOrBlank()) append("${end.contentTitle} stopped. ")
            append("The connection to the TV was lost. Open AerioTV to cast again.")
        }
        val notif = NotificationCompat.Builder(context, ALERT_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(launchPi)
            .setAutoCancel(true)
            // Its own channel, not the ongoing chip's. On Android 8+ the CHANNEL
            // importance governs alerting and setPriority is ignored, so posting
            // this to the IMPORTANCE_LOW "Casting" channel would make it silent
            // and buried -- nearly as invisible as the silent disappearance this
            // is meant to fix. A channel's importance is also immutable once
            // created, so it has to be a separate channel, not a bumped one.
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(ALERT_NOTIF_ID, notif) }
    }

    private fun clear() {
        latestNotification = null
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_ID) }
        releaseSession()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val mgr = context.getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Casting", NotificationManager.IMPORTANCE_LOW).apply {
                    setShowBadge(false)
                },
            )
        }
        // Separate channel for the "cast dropped" alert: IMPORTANCE_DEFAULT so it
        // actually makes a sound and sorts normally in the shade. The user can
        // still silence it on its own without losing the ongoing casting chip.
        if (mgr.getNotificationChannel(ALERT_CHANNEL_ID) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(
                    ALERT_CHANNEL_ID,
                    "Casting interrupted",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ),
            )
        }
    }

    companion object {
        /** The casting card last built; the relay service starts foreground
         *  with it so its required notification is this card, not a second one. */
        @Volatile var latestNotification: android.app.Notification? = null
            private set

        private const val ACTION_PLAY = "com.aeriotv.android.cast.PLAY"
        private const val ACTION_PAUSE = "com.aeriotv.android.cast.PAUSE"
        private const val CUSTOM_STOP = "com.aeriotv.android.cast.CUSTOM_STOP"
        private const val ACTION_STOP = "com.aeriotv.android.cast.STOP"
        private const val CHANNEL_ID = "aeriotv_casting"
        private const val ALERT_CHANNEL_ID = "aeriotv_cast_alerts"
        private const val NOTIF_ID = 0xC5

        // Notification ids in use across the app, so the next addition does not
        // collide the way this one did: 0xAD/0xAE LocalRecordingService,
        // 0xAF AerioMediaPlaybackService, 0xC5 the ongoing cast chip (above),
        // 0xC6 CastHlsProxyService's FOREGROUND-SERVICE notification.
        //
        // This alert MUST NOT reuse 0xC6. Posting to a foreground service's id
        // does not create a second notification, it overwrites that service's
        // own notification; and when endCleanup() then stops the proxy service,
        // the system reaps id 0xC6 and takes the alert with it. Verified on a
        // Z Fold 5 against a live cast to a Google TV Streamer: the alert was
        // posted and gone within the same teardown, leaving the user with
        // nothing, which is the exact bug this is meant to fix.
        private const val ALERT_NOTIF_ID = 0x0C7A
        private const val REQ_CODE = 0xC5
    }
}
