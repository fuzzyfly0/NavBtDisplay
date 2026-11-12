package io.github.fuzzyfly0.navbtdisplay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock

/**
 * Screen sharing for [ArtMode.SCREEN]: mirrors the screen into a small
 * [ImageReader] and sends the middle square as album art every few seconds.
 * Android asks the user for permission on every start; nothing leaves the phone
 * except the image sent to the car.
 */
class ScreenCaptureService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var lastFrameAt = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Every startForegroundService() needs a startForeground(), also for a repeated start;
        // and the projection may only be fetched once the service is in the foreground.
        startForeground(NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        if (projection != null) return START_NOT_STICKY
        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        val p = runCatching {
            getSystemService(MediaProjectionManager::class.java)
                .getMediaProjection(intent.getIntExtra(EXTRA_RESULT_CODE, 0), data)
        }.getOrNull()
        if (p == null) {
            DebugLog.log(this, "screen capture: no permission")
            stopSelf()
            return START_NOT_STICKY
        }
        projection = p
        p.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() = stopSelf()
            },
            handler,
        )
        val metrics = resources.displayMetrics
        val height = CAPTURE_WIDTH * metrics.heightPixels / metrics.widthPixels
        val r = ImageReader.newInstance(CAPTURE_WIDTH, height, PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener(::onFrame, handler)
        reader = r
        display = p.createVirtualDisplay(
            "NavBtDisplay",
            CAPTURE_WIDTH,
            height,
            metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            r.surface,
            null,
            handler,
        )
        running = true
        DebugLog.log(this, "screen capture started")
        return START_NOT_STICKY
    }

    private fun onFrame(r: ImageReader) {
        val image = r.acquireLatestImage() ?: return
        image.use {
            val now = SystemClock.elapsedRealtime()
            if (now - lastFrameAt < Prefs(this).artIntervalSec * 1_000L) return
            lastFrameAt = now
            val plane = it.planes[0]
            val padding = (plane.rowStride - plane.pixelStride * it.width) / plane.pixelStride
            val full = Bitmap.createBitmap(it.width + padding, it.height, Bitmap.Config.ARGB_8888)
            full.copyPixelsFromBuffer(plane.buffer)
            val cropped = Bitmap.createBitmap(full, 0, 0, it.width, it.height)
            NavPublisher.onArt(this, ArtMode.SCREEN, ArtImages.centerSquare(cropped))
        }
    }

    override fun onDestroy() {
        running = false
        display?.release()
        reader?.close()
        projection?.stop()
        display = null
        reader = null
        projection = null
        NavPublisher.onArt(this, ArtMode.SCREEN, null)
        DebugLog.log(this, "screen capture stopped")
        super.onDestroy()
    }

    private fun notification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.capture_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val stop = PendingIntent.getService(
            this,
            0,
            Intent(this, ScreenCaptureService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_nav)
            .setContentTitle(getString(R.string.capture_running))
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.capture_stop), stop).build())
            .build()
    }

    companion object {
        private const val CAPTURE_WIDTH = 480
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "screen_capture"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_RESULT_CODE = "result_code"
        private const val EXTRA_DATA = "data"

        @Volatile
        var running = false
            private set

        fun start(context: Context, resultCode: Int, data: Intent) {
            context.startForegroundService(
                Intent(context, ScreenCaptureService::class.java)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_DATA, data),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ScreenCaptureService::class.java))
        }
    }
}
