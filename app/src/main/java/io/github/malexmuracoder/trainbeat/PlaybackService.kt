package io.github.malexmuracoder.trainbeat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

/**
 * Keeps the loop alive when the screen goes off.
 *
 * It does nothing to the audio itself - the player and the media session live in
 * Playback, and outlive every screen. This exists for one reason: without a
 * foreground service Android freezes the process shortly after the screen goes
 * dark, and a practice loop that dies the moment you look away from the phone is
 * useless, because looking away at the music is the whole point.
 *
 * It stops itself when nothing is playing any more, checked on a slow timer as well
 * as being told. Being told can be missed - a screen can be destroyed between the
 * stop and the message - and a foreground service left running with silence behind
 * it is a battery complaint and a notification that will not go away.
 */
class PlaybackService : Service() {

    companion object {
        const val ACTION_STOP = "io.github.malexmuracoder.trainbeat.PLAYBACK_STOP"
        private const val CHANNEL = "playback"
        private const val NOTIF_ID = 1002

        @Volatile private var running = false

        fun start(ctx: Context) {
            if (running) {
                // already up: just refresh the text
                try {
                    ctx.startService(Intent(ctx, PlaybackService::class.java))
                } catch (_: Exception) {
                }
                return
            }
            val i = Intent(ctx, PlaybackService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
                else ctx.startService(i)
            } catch (_: Exception) {
                // starting from the background is refused on newer Android; the loop
                // still plays, it just will not survive the screen going off
            }
        }

        fun stop(ctx: Context) {
            if (!running) return
            try {
                ctx.stopService(Intent(ctx, PlaybackService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    private var watchdog: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            // Playback.silence, not the handler. The handler belongs to a screen,
            // and the case this button exists for is precisely the one where there
            // is no screen: the app is out of sight and this notification is the
            // only control left. Routed through the handler it was a button that
            // did nothing, on a loop that could then not be stopped at all.
            Playback.silence(this)
            stopSelf()
            return START_NOT_STICKY
        }

        createChannel()
        startForegroundCompat(notification())
        running = true
        startWatchdog()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        watchdog = null
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    /**
     * Two seconds of silence and it goes away by itself.
     *
     * SILENCE means the player is quiet, not that no screen is registered. Asking
     * the handler was the old test, and a loop playing with its screen destroyed
     * answered "not busy": the notification withdrew itself while the music went
     * on, leaving sound with no control anywhere on the phone.
     */
    private fun startWatchdog() {
        if (watchdog != null) return
        val th = Thread {
            var quiet = 0
            while (running) {
                try {
                    Thread.sleep(1000)
                } catch (_: InterruptedException) {
                    break
                }
                if (Playback.audible) quiet = 0 else quiet++
                if (quiet >= 2) {
                    stopSelf()
                    break
                }
            }
        }
        th.name = "PlaybackWatchdog"
        th.isDaemon = true
        watchdog = th
        th.start()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, "Playback", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Shown while a loop is playing"
        ch.setShowBadge(false)
        nm.createNotificationChannel(ch)
    }

    private fun notification(): Notification {
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 1, Playback.openApp(this), flags)
        val stop = PendingIntent.getService(
            this, 3,
            Intent(this, PlaybackService::class.java).setAction(ACTION_STOP),
            flags
        )

        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)

        b.setContentTitle(Playback.title)
            .setContentText("looping")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setCategory(Notification.CATEGORY_TRANSPORT)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, "Stop", stop).build())

        // A media notification, bound to the session. Stop is the only action and
        // it is the one shown in the compact view; without the token this is just
        // an ordinary foreground notification, which Android 13 and later hides
        // unless the user has granted the notification permission.
        val style = Notification.MediaStyle().setShowActionsInCompactView(0)
        Playback.sessionToken(this)?.let { style.setMediaSession(it) }
        b.setStyle(style)
        return b.build()
    }

    private fun startForegroundCompat(n: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (_: Exception) {
            // the loop keeps playing; it simply will not survive backgrounding
        }
    }
}
