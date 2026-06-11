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

    private var mediaSession: MediaLibrarySession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        ensureChannel()

        val player = exoHolder.acquireOrCreate(this)

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
        }
        val launchPi = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        mediaSession = MediaLibrarySession.Builder(this, player, LibraryCallback())
            .setSessionActivity(launchPi)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Explicit start (background-audio promotion from PlayerScreen) must
        // beat the API 31+ 5s foreground-start deadline. Android Auto only
        // BINDS to browse, so it never reaches here -- no spurious foreground
        // notification, no FGS-start-not-allowed risk.
        startForegroundCompat(buildPlaceholderNotification())
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

    private fun buildPlaceholderNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle("AerioTV")
            .setContentText("Starting playback...")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

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
