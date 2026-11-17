package io.github.fuzzyfly0.navbtdisplay

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import java.text.DateFormat
import java.util.Date

/**
 * Android Auto: a screen that always shows the current hint of the navigation
 * app and the latest Blitzer.de warning, as text.
 *
 * The app is not from the Play Store, so Android Auto only lists it with
 * "Unknown sources" switched on in the Android Auto developer settings.
 */
class CarHintService : CarAppService() {
    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(R.array.car_hosts)
                .build()
        }

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen = HintScreen(carContext)
    }
}

/**
 * Always the same template with the same number of rows, so Android Auto
 * treats updates as a refresh and does not limit how often the text changes.
 */
private class HintScreen(context: CarContext) : Screen(context), DefaultLifecycleObserver {
    private val handler = Handler(Looper.getMainLooper())
    private var lastInvalidateAt = 0L
    private val invalidateRunnable = Runnable {
        lastInvalidateAt = SystemClock.elapsedRealtime()
        invalidate()
    }
    private val listener: () -> Unit = {
        handler.removeCallbacks(invalidateRunnable)
        val wait = lastInvalidateAt + MIN_UPDATE_INTERVAL_MS - SystemClock.elapsedRealtime()
        handler.postDelayed(invalidateRunnable, wait.coerceAtLeast(0))
    }

    init {
        lifecycle.addObserver(this)
    }

    override fun onStart(owner: LifecycleOwner) {
        LiveHint.addListener(listener)
        DebugLog.log(carContext, "android auto screen shown")
        invalidate()
    }

    override fun onStop(owner: LifecycleOwner) {
        LiveHint.removeListener(listener)
        handler.removeCallbacks(invalidateRunnable)
    }

    override fun onGetTemplate(): Template {
        val s = LiveHint.state
        val c = carContext
        val guidance = s.guidance
        val first = if (guidance != null) {
            row(guidance.title.ifBlank { c.getString(R.string.car_guidance_running) }, guidance.artist)
        } else {
            row(c.getString(R.string.car_no_guidance), c.getString(R.string.car_no_guidance_hint))
        }
        val second = row(guidance?.album?.ifBlank { null } ?: "–", null)
        val warning = s.warning
        val third = if (warning != null) {
            row(warning.title, warning.artist.ifBlank { null })
        } else {
            row(c.getString(R.string.car_no_warning), null)
        }
        val source = listOfNotNull(s.warningApp ?: s.guidanceApp, updatedText(s.updatedAt))
            .joinToString(" · ")
        val fourth = row(source.ifBlank { "–" }, null)

        val pane = Pane.Builder()
            .addRow(first)
            .addRow(second)
            .addRow(third)
            .addRow(fourth)
            .build()
        @Suppress("DEPRECATION")
        return PaneTemplate.Builder(pane)
            .setTitle(c.getString(R.string.app_name))
            .setHeaderAction(Action.APP_ICON)
            .build()
    }

    private fun updatedText(at: Long): String? =
        if (at == 0L) {
            null
        } else {
            carContext.getString(R.string.car_updated, DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(at)))
        }

    private fun row(title: String, text: String?): Row =
        Row.Builder().setTitle(title).apply { if (!text.isNullOrBlank()) addText(text) }.build()

    private companion object {
        const val MIN_UPDATE_INTERVAL_MS = 1_000L
    }
}
