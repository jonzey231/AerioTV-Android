package com.aeriotv.android.core.cast.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri as AndroidUri
import android.os.Build
import androidx.media3.common.util.BitmapLoader
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.session.CacheBitmapLoader
import com.google.common.util.concurrent.MoreExecutors
import android.os.Bundle
import androidx.media3.session.CommandButton
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import androidx.core.app.NotificationCompat
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.aeriotv.android.MainActivity
import com.aeriotv.android.R
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Media notification while the phone controls an AerioTV TV (GH #33
 * companion remote; Logan 2026-09-02): a [CompanionRemotePlayer] mirrors the
 * TV's state into a Media3 session, so the system media notification shows
 * the channel/programme with play/pause and rewind/fast-forward, and tapping
 * it reopens the remote. Started on connect, stopped on disconnect.
 */
@AndroidEntryPoint
class CompanionRemoteService : MediaSessionService() {

    @Inject lateinit var controller: CompanionRemoteController

    private var session: MediaSession? = null
    private var player: CompanionRemotePlayer? = null
    private var placeholderPosted = false
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val p = CompanionRemotePlayer(controller)
        player = p
        // Rewind/forward are custom session buttons: the stock notification
        // layout only knows previous / play-pause / next, and Player seek
        // commands are not rendered as buttons.
        val back = CommandButton.Builder(CommandButton.ICON_SKIP_BACK_30)
            .setSessionCommand(SessionCommand(CMD_SEEK_BACK, Bundle.EMPTY))
            .setDisplayName("Back 30 seconds")
            .setExtras(Bundle().apply { putInt(DefaultMediaNotificationProvider.COMMAND_KEY_COMPACT_VIEW_INDEX, 0) })
            .build()
        val forward = CommandButton.Builder(CommandButton.ICON_SKIP_FORWARD_30)
            .setSessionCommand(SessionCommand(CMD_SEEK_FORWARD, Bundle.EMPTY))
            .setDisplayName("Forward 30 seconds")
            .setExtras(Bundle().apply { putInt(DefaultMediaNotificationProvider.COMMAND_KEY_COMPACT_VIEW_INDEX, 2) })
            .build()
        session = MediaSession.Builder(this, p)
            .setId("aeriotv_companion_remote")
            .setSessionActivity(remoteActivityIntent())
            .setCustomLayout(listOf(back, forward))
            .setBitmapLoader(CacheBitmapLoader(PaddedLogoLoader(DataSourceBitmapLoader(this))))
            .setCallback(object : MediaSession.Callback {
                override fun onConnect(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                ): MediaSession.ConnectionResult {
                    val cmds = MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(CMD_SEEK_BACK, Bundle.EMPTY))
                        .add(SessionCommand(CMD_SEEK_FORWARD, Bundle.EMPTY))
                        .build()
                    return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                        .setAvailableSessionCommands(cmds)
                        .build()
                }

                override fun onCustomCommand(
                    session: MediaSession,
                    controller: MediaSession.ControllerInfo,
                    customCommand: SessionCommand,
                    args: Bundle,
                ): ListenableFuture<SessionResult> {
                    when (customCommand.customAction) {
                        CMD_SEEK_BACK -> this@CompanionRemoteService.controller.seekBy(-SEEK_STEP_MS)
                        CMD_SEEK_FORWARD -> this@CompanionRemoteService.controller.seekBy(SEEK_STEP_MS)
                        else -> return Futures.immediateFuture(SessionResult(SessionResult.RESULT_ERROR_NOT_SUPPORTED))
                    }
                    return Futures.immediateFuture(SessionResult(SessionResult.RESULT_SUCCESS))
                }
            })
            .build()
        setMediaNotificationProvider(
            object : DefaultMediaNotificationProvider(this, { NOTIF_ID }, CHANNEL_ID, R.string.app_name) {
                // Order the row as back / play-pause / forward instead of the
                // default "custom buttons trail the stock ones".
                override fun getMediaButtons(
                    session: MediaSession,
                    playerCommands: androidx.media3.common.Player.Commands,
                    customLayout: ImmutableList<CommandButton>,
                    showPauseButton: Boolean,
                ): ImmutableList<CommandButton> {
                    val stock = super.getMediaButtons(session, playerCommands, customLayout, showPauseButton)
                    val playPause = stock.firstOrNull { it.sessionCommand == null }
                    val customs = stock.filter { it.sessionCommand != null }
                    val b = customs.firstOrNull { it.sessionCommand?.customAction == CMD_SEEK_BACK }
                    val f = customs.firstOrNull { it.sessionCommand?.customAction == CMD_SEEK_FORWARD }
                    return ImmutableList.copyOf(listOfNotNull(b, playPause, f))
                }
            },
        )
        // Nothing binds a controller to this service (it is started, not
        // bound), so add the session by hand: that is what attaches Media3's
        // notification manager and replaces the placeholder with the media
        // notification.
        session?.let { addSession(it) }
        // The tap target follows the channel the TV is on; leaving the
        // session ends the notification.
        controller.currentChannelId.onEach { session?.setSessionActivity(remoteActivityIntent()) }.launchIn(scope)
        controller.connection.onEach { conn ->
            if (conn is CompanionRemoteController.Conn.Idle || conn is CompanionRemoteController.Conn.Failed) {
                stopSelf()
            }
        }.launchIn(scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // API 31+: a started foreground service must post within 5s; Media3
        // replaces this placeholder with the real media notification. Media3
        // re-sends a start intent on every notification update, so post the
        // placeholder only on the first start or it clobbers the real one.
        if (placeholderPosted) return super.onStartCommand(intent, flags, startId)
        placeholderPosted = true
        val placeholder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Controlling TV")
            .setContentIntent(remoteActivityIntent())
            .setOngoing(true)
            .build()
        startForegroundCompat(placeholder)
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiping the app away should not keep a phantom remote around.
        controller.disconnect()
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        session?.release()
        session = null
        player?.release()
        player = null
        super.onDestroy()
    }

    private fun remoteActivityIntent(): PendingIntent {
        val channelId = controller.currentChannelId.value
        val intent = Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_VIEW
            if (channelId != null) data = Uri.parse("aeriotv://channel/$channelId")
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        return PendingIntent.getActivity(
            this, 0x33, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    /**
     * Channel logos are wide and busy; One UI stretches any artwork across the
     * whole card. Inset the logo on a transparent 512px square so it renders
     * as a small badge instead of a backdrop (Logan 2026-09-02).
     */
    private class PaddedLogoLoader(private val delegate: BitmapLoader) : BitmapLoader {
        override fun supportsMimeType(mimeType: String) = delegate.supportsMimeType(mimeType)
        override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> =
            Futures.transform(delegate.decodeBitmap(data), ::pad, MoreExecutors.directExecutor())
        override fun loadBitmap(uri: AndroidUri): ListenableFuture<Bitmap> =
            Futures.transform(delegate.loadBitmap(uri), ::pad, MoreExecutors.directExecutor())

        private fun pad(src: Bitmap): Bitmap {
            val size = 512
            val inset = 0.30f
            val avail = size * (1f - 2 * inset)
            val scale = minOf(avail / src.width, avail / src.height)
            val w = (src.width * scale).toInt().coerceAtLeast(1)
            val h = (src.height * scale).toInt().coerceAtLeast(1)
            val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(out)
            val left = (size - w) / 2
            val top = (size - h) / 2
            c.drawBitmap(src, null, Rect(left, top, left + w, top + h), Paint(Paint.FILTER_BITMAP_FLAG))
            return out
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "TV remote control", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Controls for the TV you are controlling from this phone"
                    setShowBadge(false)
                },
            )
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            @Suppress("DEPRECATION")
            startForeground(NOTIF_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "aeriotv_companion_remote"
        private const val NOTIF_ID = 0xC0
        private const val CMD_SEEK_BACK = "app.aeriotv.companion.SEEK_BACK"
        private const val CMD_SEEK_FORWARD = "app.aeriotv.companion.SEEK_FORWARD"
        private const val SEEK_STEP_MS = 30_000L

        fun start(context: Context) {
            val intent = Intent(context, CompanionRemoteService::class.java)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent)
                else context.startService(intent)
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, CompanionRemoteService::class.java)) }
        }
    }
}
