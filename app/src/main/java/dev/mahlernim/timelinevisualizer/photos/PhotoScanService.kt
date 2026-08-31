package dev.mahlernim.timelinevisualizer.photos

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
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dev.mahlernim.timelinevisualizer.MainActivity
import dev.mahlernim.timelinevisualizer.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PhotoScanSnapshot(
    val status: Status,
    val scanned: Int = 0,
    val withLocation: Int = 0,
    val total: Int = 0,
    val result: PhotoLibraryScanner.ScanResult? = null,
) {
    enum class Status { RUNNING, COMPLETE, CANCELLED, FAILED }
    val percent: Int get() = if (total > 0) ((scanned * 100f / total).toInt().coerceIn(0, 100)) else 0
    val fraction: Float get() = if (total > 0) scanned.toFloat() / total else 0f
}

object PhotoScanCoordinator {
    private val _state = MutableStateFlow<PhotoScanSnapshot?>(null)
    val state: StateFlow<PhotoScanSnapshot?> = _state
    fun publish(snapshot: PhotoScanSnapshot?) { _state.value = snapshot }
    fun clear() { _state.value = null }
}

class PhotoScanService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var notificationManager: NotificationManager
    private var scanJob: Job? = null
    private var latestStartId: Int = 0
    private var lastNotifiedAt: Long = 0L
    private var lastNotifiedProgress: Int = -1

    override fun onCreate() {
        super.onCreate()
        notificationManager = getSystemService(NotificationManager::class.java)
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        when (intent?.action) {
            ACTION_CANCEL -> cancelScan()
            else -> startScan()
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun startScan() {
        if (scanJob?.isActive == true) return
        startForegroundWithNotification(buildStartingNotification())
        scanJob = serviceScope.launch {
            try {
                val scanner = PhotoLibraryScanner(applicationContext)
                // Quick total count to show percent from start
                val totalHint = withContext(Dispatchers.IO) { scanner.countTotal() }
                PhotoScanCoordinator.publish(PhotoScanSnapshot(PhotoScanSnapshot.Status.RUNNING, 0, 0, totalHint))
                updateProgressNotification(0, 0, totalHint, force = true)

                val result = scanner.scanWithProgress { scanned: Int, withLocation: Int, total: Int ->
                    val totalResolved = if (total > 0) total else totalHint
                    val snapshot = PhotoScanSnapshot(PhotoScanSnapshot.Status.RUNNING, scanned, withLocation, totalResolved)
                    PhotoScanCoordinator.publish(snapshot)
                    // Throttle notifications like VideoExportService
                    val now = SystemClock.elapsedRealtime()
                    val percent = snapshot.percent
                    if (now - lastNotifiedAt >= NOTIF_INTERVAL_MS || percent != lastNotifiedProgress) {
                        lastNotifiedAt = now
                        lastNotifiedProgress = percent
                        notificationManager.notify(NOTIFICATION_ID, buildProgressNotification(scanned, withLocation, totalResolved))
                    }
                }
                val completed = PhotoScanSnapshot(PhotoScanSnapshot.Status.COMPLETE, result.scannedCount, result.points.size, result.scannedCount, result)
                PhotoScanCoordinator.publish(completed)
                // Build completed notification – keep it as completion channel, remove foreground
                ServiceCompat.stopForeground(this@PhotoScanService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                notificationManager.notify(NOTIFICATION_ID, buildCompletedNotification(result))
                // Prepare json in background like old MainActivity did, cache for import
                withContext(Dispatchers.Default) {
                    try {
                        val json = PhotoTimelineJsonBuilder.build(result.points)
                        PhotoScanCoordinator.publish(completed) // ensure result still available
                        // Store pending json via coordinator helper file? MainActivity will rebuild from result anyway
                    } catch (_: Exception) {}
                }
                stopSelfResult(latestStartId)
            } catch (e: CancellationException) {
                Log.i(TAG, "Photo scan cancelled", e)
                PhotoScanCoordinator.publish(PhotoScanSnapshot(PhotoScanSnapshot.Status.CANCELLED))
                ServiceCompat.stopForeground(this@PhotoScanService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                notificationManager.notify(NOTIFICATION_ID, buildCancelledNotification())
                stopSelfResult(latestStartId)
            } catch (e: Exception) {
                Log.e(TAG, "Photo scan failed", e)
                PhotoScanCoordinator.publish(PhotoScanSnapshot(PhotoScanSnapshot.Status.FAILED))
                ServiceCompat.stopForeground(this@PhotoScanService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                notificationManager.notify(NOTIFICATION_ID, buildFailedNotification(e.message))
                stopSelfResult(latestStartId)
            } finally {
                scanJob = null
            }
        }
    }

    private fun cancelScan() {
        val job = scanJob
        if (job?.isActive == true) {
            job.cancel(CancellationException("User cancelled"))
        } else {
            PhotoScanCoordinator.publish(PhotoScanSnapshot(PhotoScanSnapshot.Status.CANCELLED))
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            notificationManager.notify(NOTIFICATION_ID, buildCancelledNotification())
            notificationManager.cancel(NOTIFICATION_ID)
            stopSelfResult(latestStartId)
        }
    }

    private fun startForegroundWithNotification(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, n)
        }
    }

    private fun updateProgressNotification(scanned: Int, withLocation: Int, total: Int, force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotifiedAt < NOTIF_INTERVAL_MS) return
        lastNotifiedAt = now
        notificationManager.notify(NOTIFICATION_ID, buildProgressNotification(scanned, withLocation, total))
    }

    private fun buildStartingNotification(): Notification = baseBuilder()
        .setContentTitle(getString(R.string.scanning_photos_notification_title))
        .setContentText(getString(R.string.scanning_photos))
        .setProgress(0, 0, true)
        .setOngoing(true)
        .addAction(0, getString(R.string.cancel), cancelPendingIntent())
        .build()

    private fun buildProgressNotification(scanned: Int, withLocation: Int, total: Int): Notification {
        val percent = if (total > 0) (scanned * 100 / total) else 0
        val text = if (total > 0) getString(R.string.scanning_photos_progress, scanned, total, percent, withLocation)
        else getString(R.string.scanning_photos) + " $scanned"
        return baseBuilder()
            .setContentTitle(getString(R.string.scanning_photos_notification_title))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setProgress(if (total > 0) 100 else 0, percent, total == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(0, getString(R.string.cancel), cancelPendingIntent())
            .build()
    }

    private fun buildCompletedNotification(result: PhotoLibraryScanner.ScanResult): Notification {
        val title = if (result.points.isEmpty()) getString(R.string.photo_scan_complete_no_location) else getString(R.string.photo_scan_complete)
        val text = getString(R.string.photos_scanned, result.scannedCount, result.points.size)
        return completionBuilder()
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOngoing(false)
            .setProgress(0, 0, false)
            .build()
    }

    private fun buildCancelledNotification(): Notification = baseBuilder()
        .setContentTitle(getString(R.string.photo_scan_cancelled))
        .setContentText(getString(R.string.photo_scan_cancelled_detail))
        .setAutoCancel(true)
        .setOngoing(false)
        .setProgress(0, 0, false)
        .build()

    private fun buildFailedNotification(msg: String?): Notification = completionBuilder()
        .setContentTitle(getString(R.string.photo_scan_failed))
        .setContentText(msg ?: getString(R.string.import_failed))
        .setStyle(NotificationCompat.BigTextStyle().bigText(msg))
        .setAutoCancel(true)
        .setOngoing(false)
        .build()

    private fun baseBuilder(): NotificationCompat.Builder = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentIntent(openAppPendingIntent())
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

    private fun completionBuilder(): NotificationCompat.Builder = NotificationCompat.Builder(this, COMPLETION_CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_launcher_foreground)
        .setContentIntent(openAppPendingIntent())
        .setCategory(NotificationCompat.CATEGORY_STATUS)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)

    private fun openAppPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this, OPEN_APP_REQUEST_CODE,
        Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun cancelPendingIntent(): PendingIntent = PendingIntent.getService(
        this, CANCEL_REQUEST_CODE,
        Intent(this, PhotoScanService::class.java).setAction(ACTION_CANCEL),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun createChannels() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        notificationManager.createNotificationChannels(listOf(
            NotificationChannel(CHANNEL_ID, getString(R.string.photo_scan_notification_channel), NotificationManager.IMPORTANCE_LOW).apply {
                description = getString(R.string.photo_scan_notification_channel_description)
                setShowBadge(false)
            },
            NotificationChannel(COMPLETION_CHANNEL_ID, getString(R.string.photo_scan_completion_channel), NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = getString(R.string.photo_scan_completion_channel_description)
            }
        ))
    }

    companion object {
        const val ACTION_START = "dev.mahlernim.timelinevisualizer.action.START_PHOTO_SCAN"
        const val ACTION_CANCEL = "dev.mahlernim.timelinevisualizer.action.CANCEL_PHOTO_SCAN"
        const val CHANNEL_ID = "photo_scan"
        const val COMPLETION_CHANNEL_ID = "photo_scan_completion"
        const val NOTIFICATION_ID = 4109
        private const val NOTIF_INTERVAL_MS = 500L
        private const val OPEN_APP_REQUEST_CODE = 4110
        private const val CANCEL_REQUEST_CODE = 4111
        private const val TAG = "PhotoScanService"

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, PhotoScanService::class.java).setAction(ACTION_START))
        }
        fun cancel(context: Context) {
            context.startService(Intent(context, PhotoScanService::class.java).setAction(ACTION_CANCEL))
        }
        fun clearNotification(context: Context) {
            context.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }
}
