package io.github.fuzzyfly0.navbtdisplay

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Receives the notifications of the supported navigation apps.
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
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val app = NavApps.forPackage(sbn.packageName) ?: return
        if (!Prefs(this).isAppEnabled(app)) return
        DebugLog.log(this, "posted " + NotificationDump.describe(this, sbn))
        if (!NotificationDump.isGuidanceCandidate(sbn)) return
        val car = NavText.compose(app.label, NotificationDump.fields(this, sbn)) ?: return
        DebugLog.log(this, "parsed title='${car.title}' artist='${car.artist}' key='${car.maneuverKey}'")
        Prefs(this).lastHint = "${car.title}\n${car.artist}"
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        val app = NavApps.forPackage(sbn.packageName) ?: return
        if (!Prefs(this).isAppEnabled(app)) return
        DebugLog.log(this, "removed pkg=${sbn.packageName} id=${sbn.id} tag=${sbn.tag}")
    }
}
