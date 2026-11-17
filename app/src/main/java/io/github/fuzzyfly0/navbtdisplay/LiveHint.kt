package io.github.fuzzyfly0.navbtdisplay

import android.os.Handler
import android.os.Looper

/**
 * The latest guidance and warning, independent of the Bluetooth display mode.
 * Read by the Android Auto screen ([CarHintService]). Main thread only.
 */
object LiveHint {
    private const val WARNING_MS = 10_000L

    data class State(
        val guidance: CarText? = null,
        val guidanceApp: String? = null,
        val warning: CarText? = null,
        val warningApp: String? = null,
        val updatedAt: Long = 0L,
    )

    var state = State()
        private set

    private var guidanceKey: String? = null
    private val handler = Handler(Looper.getMainLooper())
    private val listeners = mutableSetOf<() -> Unit>()
    private val warningEnd = Runnable { update(state.copy(warning = null, warningApp = null)) }

    fun onGuidance(notificationKey: String, appLabel: String, car: CarText) {
        guidanceKey = notificationKey
        if (car == state.guidance && appLabel == state.guidanceApp) return
        update(state.copy(guidance = car, guidanceApp = appLabel))
    }

    fun onGuidanceEnded(notificationKey: String) {
        if (notificationKey != guidanceKey) return
        guidanceKey = null
        update(state.copy(guidance = null, guidanceApp = null))
    }

    fun onWarning(appLabel: String, car: CarText) {
        handler.removeCallbacks(warningEnd)
        handler.postDelayed(warningEnd, WARNING_MS)
        if (car == state.warning) return
        update(state.copy(warning = car, warningApp = appLabel))
    }

    fun clear() {
        guidanceKey = null
        handler.removeCallbacks(warningEnd)
        update(State())
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun update(new: State) {
        state = new.copy(updatedAt = System.currentTimeMillis())
        listeners.toList().forEach { it() }
    }
}
