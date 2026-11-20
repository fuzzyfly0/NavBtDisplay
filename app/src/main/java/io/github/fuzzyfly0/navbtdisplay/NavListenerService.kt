package io.github.fuzzyfly0.navbtdisplay

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Receives the notifications of the supported apps and feeds the guidance
 * (or warning) text to [NavPublisher].
 *
 * The system binds this service once the user grants notification access.
 */
class NavListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        DebugLog.log(this, "listener connected")
        runCatching { activeNotifications }.getOrNull()?.forEach(::onNotificationPosted)
    }

    override fun onListenerDisconnected() {
        DebugLog.log(this, "listener disconnected")
        LiveHint.clear()
        NavPublisher.stop(this)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = NavApps.forPackage(sbn.packageName) ?: return
        val enabled = Prefs(this).isAppEnabled(app)
        if (app.kind == NavApp.Kind.WARNING) {
            if (!enabled) return
            DebugLog.log(this, "posted " + NotificationDump.describe(this, sbn))
            val car = NavText.composeWarning(app.label, NotificationDump.fields(this, sbn, app.label)) ?: return
            DebugLog.log(this, "warning title='${car.title}' artist='${car.artist}' key='${car.maneuverKey}'")
            LiveHint.onWarning(app.label, car)
            NavPublisher.onWarning(this, sbn.key, car)
            return
        }
        if (!enabled) {
            LiveHint.onGuidanceEnded(sbn.key)
            NavPublisher.onGuidanceEnded(this, sbn.key)
            return
        }
        DebugLog.log(this, "posted " + NotificationDump.describe(this, sbn))
        if (!NotificationDump.isGuidanceCandidate(sbn)) return
        val car = NavText.compose(app.label, NotificationDump.fields(this, sbn, app.label), Prefs(this).symbolStyle)
        if (car == null) {
            // Status line without guidance (e.g. "Mit Google Maps fahren"): end any shown hint.
            LiveHint.onGuidanceEnded(sbn.key)
            NavPublisher.onGuidanceEnded(this, sbn.key)
            return
        }
        DebugLog.log(this, "parsed title='${car.title}' artist='${car.artist}' album='${car.album}' key='${car.maneuverKey}'")
        if (Prefs(this).artMode == ArtMode.NOTIFICATION) {
            NavPublisher.onArt(this, ArtMode.NOTIFICATION, runCatching { ArtImages.fromNotification(this, sbn) }.getOrNull())
        }
        LiveHint.onGuidance(sbn.key, app.label, car)
        NavPublisher.onGuidance(this, sbn.key, car)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val app = NavApps.forPackage(sbn.packageName) ?: return
        DebugLog.log(this, "removed pkg=${sbn.packageName} id=${sbn.id} tag=${sbn.tag}")
        // A warning stays on the display for its full time, even if the heads-up is gone.
        if (app.kind == NavApp.Kind.NAVIGATION) {
            LiveHint.onGuidanceEnded(sbn.key)
            NavPublisher.onGuidanceEnded(this, sbn.key)
        }
    }
}
