package io.github.fuzzyfly0.navbtdisplay

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.service.notification.StatusBarNotification

/** Builds the square album art images sent to the car. */
object ArtImages {
    /** AVRCP cover art thumbnails are 200×200; a little more leaves the car room to scale. */
    const val SIZE = 320

    /**
     * The maneuver picture of a navigation notification: its big picture, else the
     * large icon (Google Maps draws the arrow there), else the tinted small icon.
     */
    fun fromNotification(context: Context, sbn: StatusBarNotification): Bitmap? {
        val n = sbn.notification
        @Suppress("DEPRECATION")
        (n.extras.getParcelable(Notification.EXTRA_PICTURE) as? Bitmap)?.let { return centerSquare(it) }
        n.getLargeIcon()?.let { icon ->
            runCatching { icon.loadDrawable(context) }.getOrNull()?.let { return fromDrawable(it) }
        }
        val small = n.smallIcon?.let { runCatching { it.loadDrawable(context) }.getOrNull() } ?: return null
        return fromDrawable(small.mutate().apply { setTint(Color.WHITE) })
    }

    /** Centers [d] with some margin on a background that contrasts with it. */
    fun fromDrawable(d: Drawable): Bitmap {
        val inner = (SIZE * 0.8f).toInt()
        val icon = Bitmap.createBitmap(inner, inner, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, inner, inner)
        d.draw(Canvas(icon))
        val out = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(if (isLight(icon)) Color.BLACK else Color.WHITE)
        val offset = (SIZE - inner) / 2f
        canvas.drawBitmap(icon, offset, offset, null)
        icon.recycle()
        return out
    }

    /** The middle square of [b], scaled to [SIZE]. */
    fun centerSquare(b: Bitmap): Bitmap {
        val side = minOf(b.width, b.height)
        val square = Bitmap.createBitmap(b, (b.width - side) / 2, (b.height - side) / 2, side, side)
        return Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
    }

    private fun isLight(b: Bitmap): Boolean {
        var sum = 0.0
        var count = 0
        val step = maxOf(1, b.width / 40)
        for (y in 0 until b.height step step) {
            for (x in 0 until b.width step step) {
                val p = b.getPixel(x, y)
                if (Color.alpha(p) < 128) continue
                sum += 0.299 * Color.red(p) + 0.587 * Color.green(p) + 0.114 * Color.blue(p)
                count++
            }
        }
        return count == 0 || sum / count > 128
    }
}
