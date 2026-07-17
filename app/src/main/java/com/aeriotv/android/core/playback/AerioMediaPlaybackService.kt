package com.aeriotv.android.core.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaSession
import com.aeriotv.android.MainActivity
import com.aeriotv.android.R
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Media3 [MediaLibraryService] backing both background audio (notification +
 * lock screen + Bluetooth) AND Android Auto.
 *
 * Auto support (CarPlay parity) is the [LibraryCallback] browse tree built by
 * [AutoBrowseTree]: root -> Favorites + Groups -> channels, each row showing
 * name / now-playing programme / logo. Playback from a browse pick resolves the
 * channel's stream URL + Dispatcharr auth headers (rebuilt from the current
 * effective base so a car on cellular never gets a home-LAN URL), loads the
 * sibling list as the timeline so the head unit's next/previous flips channels,
 * and drops the video track (the car is audio-only).
 *
 * MediaLibraryService is a superset of the previous MediaSessionService, so
 * the existing foreground-audio behavior is unchanged. The one lifecycle
 * adjustment: the 5s foreground-start placeholder moved from onCreate to
 * onStartCommand. Android Auto BINDS the service (no onStartCommand) just to
 * browse, so it must NOT trigger a foreground "Starting playback..."
 * notification (which would also risk a ForegroundServiceStartNotAllowed crash
 * when bound from the background). The explicit background-audio start path
 * (startForegroundService -> onStartCommand) still satisfies the deadline.
 *
 * The MediaSession wraps the SAME ExoPlayer instance mounted in
 * PersistentExoWindow, so foreground PlayerScreen, background controls, and Auto
 * all read/drive identical state.
 */
@OptIn(UnstableApi::class)
@AndroidEntryPoint
class AerioMediaPlaybackService : MediaLibraryService() {

    @Inject lateinit var exoHolder: AerioExoPlayerHolder
    @Inject lateinit var browseTree: AutoBrowseTree
    @Inject lateinit var castReceiver: com.aeriotv.android.core.cast.AerioCastReceiverController
    @Inject lateinit var castSender: com.aeriotv.android.core.cast.AerioCastSender

    private var mediaSession: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Tap target for the foreground notification -> back into the app (the
     *  stream when local, the cast remote when casting via the mini controller). */
    private val launchPi: PendingIntent by lazy {
        PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()

        val player = exoHolder.acquireOrCreate(this)

        mediaSession = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(launchPi)
            .build()

        // Cast Connect (GH #33): hand this session's token to the receiver so a
        // sender casting to this Android TV sees live play/pause + now-playing
        // metadata. getSessionCompatToken() is exactly the type MediaManager
        // wants; the controller no-ops off-TV / without Cast, so this is safe on
        // every device. Playback itself still runs through the shared ExoPlayer
        // the receiver drives via the fullscreen player.
        mediaSession?.let { castReceiver.publishSessionToken(it.sessionCompatToken) }

        // Let Media3 own the foreground notification so it shows the REAL
        // now-playing (channel name, programme, logo, play/pause/next) pulled
        // from the session's current MediaItem metadata. Pinned to the SAME
        // channel + notification id as our onStartCommand placeholder so the
        // rich notification REPLACES "Starting playback..." instead of leaving
        // it stuck (car report: the only sign the app was running was that
        // useless placeholder, in Auto AND on the phone). Once Auto plays, Media3
        // promotes the bound service via onStartCommand, so the placeholder did
        // show there too -- this aligns the ids so it's swapped out immediately.
        setMediaNotificationProvider(
            androidx.media3.session.DefaultMediaNotificationProvider.Builder(this)
                .setChannelId(CHANNEL_ID)
                .setChannelName(R.string.app_name)
                .setNotificationId(NOTIF_ID)
                .build(),
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Explicit start (background-audio promotion from PlayerScreen) must
        // beat the API 31+ 5s foreground-start deadline. Android Auto only
        // BINDS to browse, so it never reaches here -- no spurious foreground
        // notification, no FGS-start-not-allowed risk.
        startForegroundCompat(buildNowPlayingNotification())
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaLibrarySession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player != null && !player.playWhenReady) {
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        scope.cancel()
        mediaSession?.run {
            // Do NOT release the player here -- it's owned by
            // AerioExoPlayerHolder and may still be in use by PlayerScreen.
            release()
        }
        mediaSession = null
        super.onDestroy()
    }

    /**
     * The Android Auto / Assistant browse + play callback. Browse methods walk
     * the [AutoBrowseTree]; play methods resolve the pick into a header-authed,
     * audio-only timeline.
     */
    private inner class LibraryCallback : MediaLibrarySession.Callback {

        // Only the app itself, system-level controllers (Auto, Assistant,
        // Bluetooth, media resumption -- all have MEDIA_CONTENT_CONTROL and
        // show as isTrusted), and known car packages may drive playback.
        // Any other third-party app is rejected to prevent player-state hijack.
        override fun onConnect(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): MediaSession.ConnectionResult {
            val pkg = controller.packageName
            val isCar = pkg == "com.google.android.projection.gearhead" ||
                pkg == "com.google.android.apps.automotive.templates.host" ||
                pkg.startsWith("com.google.android.gms.car") ||
                packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)
            return if (pkg == packageName || controller.isTrusted || isCar) {
                MediaSession.ConnectionResult.accept(
                    MediaSession.ConnectionResult.DEFAULT_SESSION_AND_LIBRARY_COMMANDS,
                    MediaSession.ConnectionResult.DEFAULT_PLAYER_COMMANDS,
                )
            } else {
                MediaSession.ConnectionResult.reject()
            }
        }

        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<MediaItem>> = future {
            LibraryResult.ofItem(browseTree.rootItem(), params)
        }

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?,
        ): ListenableFuture<LibraryResult<com.google.common.collect.ImmutableList<MediaItem>>> = future {
            LibraryResult.ofItemList(browseTree.children(parentId), params)
        }

        // Android Auto / Automotive controllers play audio-only (no car
        // surface). EVERY other media-session controller on a PHONE (Bluetooth
        // device, Assistant, system media-resumption, the notification) also
        // routes through these callbacks, and blanking video for them left the
        // foreground player black with audio still running (user report,
        // Pixel 9 Pro XL). Only a real car controller should drop video.
        private fun isCarController(controller: MediaSession.ControllerInfo): Boolean {
            val pkg = controller.packageName
            return pkg == "com.google.android.projection.gearhead" ||
                pkg == "com.google.android.apps.automotive.templates.host" ||
                pkg.startsWith("com.google.android.gms.car") ||
                packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_AUTOMOTIVE)
        }

        // Android Auto / Assistant calls this on connect (and "resume" voice
        // commands) to restart the last session without the user re-browsing.
        // Supporting it is an Auto quality-guideline checklist item; we resume
        // the last channel the holder played, rebuilt against the current
        // LAN/WAN base so a car on cellular gets the remote URL.
        override fun onPlaybackResumption(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
            val lastId = exoHolder.currentChannelId
                ?: throw UnsupportedOperationException("no previous channel to resume")
            val info = browseTree.resolveForPlayback(lastId)
                ?: throw UnsupportedOperationException("previous channel no longer available")
            exoHolder.httpHeaders = info.headers
            if (isCarController(controller)) exoHolder.setVideoTrackEnabled(false)
            MediaSession.MediaItemsWithStartPosition(info.items, info.startIndex, C.TIME_UNSET)
        }

        override fun onSetMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
            startIndex: Int,
            startPositionMs: Long,
        ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> = future {
            val pickedId = mediaItems.getOrNull(startIndex)?.mediaId
                ?: mediaItems.firstOrNull()?.mediaId
            val info = pickedId?.let { browseTree.resolveForPlayback(it) }
            if (info != null) {
                exoHolder.httpHeaders = info.headers
                if (isCarController(controller)) exoHolder.setVideoTrackEnabled(false)
                MediaSession.MediaItemsWithStartPosition(info.items, info.startIndex, C.TIME_UNSET)
            } else {
                MediaSession.MediaItemsWithStartPosition(mediaItems, startIndex, startPositionMs)
            }
        }

        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> = future {
            val resolved = mediaItems.mapNotNull { item ->
                val info = browseTree.resolveForPlayback(item.mediaId) ?: return@mapNotNull null
                exoHolder.httpHeaders = info.headers
                if (isCarController(controller)) exoHolder.setVideoTrackEnabled(false)
                info.items.getOrNull(info.startIndex)
            }
            if (resolved.isNotEmpty()) resolved.toMutableList() else mediaItems
        }
    }

    /** Bridge a suspend producer to the Guava ListenableFuture the session
     *  callbacks expect. Runs on the session's main thread so player touches
     *  are thread-safe; the repository's suspend reads dispatch internally. */
    private fun <T> future(block: suspend () -> T): ListenableFuture<T> {
        val f = SettableFuture.create<T>()
        scope.launch {
            try {
                f.set(block())
            } catch (t: Throwable) {
                f.setException(t)
            }
        }
        return f
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

    /** The live "Now Playing" notification: real channel + programme (or
     *  "Casting to <TV>" while casting), tappable back into the app. Replaces the
     *  old static "Starting playback..." placeholder that stuck + did nothing. */
    private fun buildNowPlayingNotification(): Notification {
        val casting = castSender.state.value is
            com.aeriotv.android.core.cast.AerioCastSender.State.Connected
        val title = com.aeriotv.android.core.pip.PipState.nowPlayingTitle.ifBlank { "AerioTV" }
        val text = if (casting) {
            val device = (
                castSender.state.value as?
                    com.aeriotv.android.core.cast.AerioCastSender.State.Connected
                )?.deviceName
            if (!device.isNullOrBlank()) "Casting to $device" else "Casting"
        } else {
            com.aeriotv.android.core.pip.PipState.nowPlayingSubtitle.ifBlank { "Live" }
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(launchPi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()
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

    companion object {
        private const val CHANNEL_ID = "aeriotv_background_playback"
        private const val NOTIF_ID = 0xAF

        fun startBackground(context: android.content.Context) {
            val intent = Intent(context, AerioMediaPlaybackService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: android.content.Context) {
            val intent = Intent(context, AerioMediaPlaybackService::class.java)
            context.stopService(intent)
        }
    }
}
