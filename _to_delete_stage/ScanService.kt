package io.github.malexmuracoder.trainbeat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder

/**
 * Runs the library scan as a foreground service, so it survives the screen going
 * off or the user switching app. Results are appended file by file, which makes
 * an interrupted scan harmless: the next run picks up what is missing.
 *
 * If the user denied the notification permission the progress notification is
 * simply not shown - the service still runs. Samsung's battery optimisation is
 * the real threat to a long scan, not that permission.
 */
class ScanService : Service() {

    companion object {
        const val ACTION_START = "io.github.malexmuracoder.trainbeat.SCAN_START"
        const val ACTION_CANCEL = "io.github.malexmuracoder.trainbeat.SCAN_CANCEL"
        private const val CHANNEL = "scan"
        private const val NOTIF_ID = 1001

        fun start(ctx: Context) {
            val i = Intent(ctx, ScanService::class.java).setAction(ACTION_START)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) ctx.startForegroundService(i)
            else ctx.startService(i)
        }

        fun cancel(ctx: Context) {
            ScanState.cancelRequested = true
            try {
                ctx.startService(Intent(ctx, ScanService::class.java).setAction(ACTION_CANCEL))
            } catch (_: Exception) {
            }
        }
    }

    private var worker: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL) {
            ScanState.cancelRequested = true
            return START_NOT_STICKY
        }
        if (worker != null) return START_NOT_STICKY

        createChannel()
        startForegroundCompat(notification("Starting...", 0, 0))

        val th = Thread { runScan() }
        th.priority = Thread.NORM_PRIORITY
        th.name = "LibraryScan"
        worker = th
        th.start()
        return START_NOT_STICKY
    }

    private fun runScan() {
        ScanState.running = true
        try {
            val store = LibraryFolders.store(this)
            val known = store.loadMap()

            val entries = ArrayList<DocTree.Entry>()
            for (folder in LibraryFolders.load(this)) {
                entries.addAll(DocTree.listAudio(this, Uri.parse(folder)))
            }
            // same file reachable from two roots: keep it once
            val unique = entries.distinctBy { TrackRecord.cacheKeyOf(it.name, it.size) }
            ScanState.reset(unique.size)
            update("Found ${unique.size} files", 0, unique.size)

            var analysed = 0
            var elapsed = 0L
            for (e in unique) {
                if (ScanState.cancelRequested) break
                val key = TrackRecord.cacheKeyOf(e.name, e.size)
                val cached = known[key]
                if (cached != null && cached.error == null) {
                    ScanState.skipped++
                    continue
                }
                ScanState.current = e.name
                val t0 = System.currentTimeMillis()
                val record = analyse(e)
                elapsed += System.currentTimeMillis() - t0
                analysed++
                ScanState.msPerFile = elapsed.toDouble() / analysed
                store.append(record)
                if (record.error != null) ScanState.failed++
                ScanState.done++
                update(e.name, ScanState.done + ScanState.skipped, ScanState.total)
            }

            store.compact()
            ScanState.lastMessage =
                if (ScanState.cancelRequested) "Cancelled: ${ScanState.done} analysed, ${ScanState.skipped} already known"
                else "Done: ${ScanState.done} analysed, ${ScanState.skipped} already known, ${ScanState.failed} failed"
        } catch (e: Throwable) {
            ScanState.lastMessage = "Scan error: ${e.message ?: e.javaClass.simpleName}"
        } finally {
            ScanState.running = false
            ScanState.current = ""
            worker = null
            stopForegroundCompat()
            stopSelf()
        }
    }

    private fun analyse(e: DocTree.Entry): TrackRecord {
        return try {
            val clip = AudioLoader.loadForAnalysis(this, Uri.parse(e.uri))
            val r = Analyzer.analyze(clip.samples, clip.channels, clip.sampleRate, clip.loopExact)
            TrackRecord(
                name = e.name,
                size = e.size,
                uri = e.uri,
                sampleRate = clip.sampleRate,
                channels = clip.channels,
                durationMs = clip.fullMs,
                bpm = r.bpm,
                beats = r.beats,
                bars = r.bars,
                barsRemainder = r.barsRemainder,
                keyProbs = r.keyProbs
            )
        } catch (t: Throwable) {
            TrackRecord(
                name = e.name,
                size = e.size,
                uri = e.uri,
                error = (t.message ?: t.javaClass.simpleName).take(200)
            )
        }
    }

    // --- notification plumbing ---

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, "Library scan", NotificationManager.IMPORTANCE_LOW)
        ch.description = "Progress of the library analysis"
        nm.createNotificationChannel(ch)
    }

    private fun notification(text: String, done: Int, total: Int): Notification {
        val cancelIntent = PendingIntent.getService(
            this, 2,
            Intent(this, ScanService::class.java).setAction(ACTION_CANCEL),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            else PendingIntent.FLAG_UPDATE_CURRENT
        )

        val b = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL) else @Suppress("DEPRECATION") Notification.Builder(this)

        b.setContentTitle("Scanning library")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    Notification.Action.Builder(null, "Cancel", cancelIntent).build()
                else @Suppress("DEPRECATION") Notification.Action.Builder(0, "Cancel", cancelIntent).build()
            )
        if (total > 0) b.setProgress(total, done, false)
        return b.build()
    }

    private fun update(text: String, done: Int, total: Int) {
        try {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.notify(NOTIF_ID, notification(text, done, total))
        } catch (_: Exception) {
        }
    }

    private fun startForegroundCompat(n: Notification) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            ScanState.lastMessage = "Could not start in the background: ${e.message}"
        }
    }

    private fun stopForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION") stopForeground(true)
            }
        } catch (_: Exception) {
        }
    }
}
