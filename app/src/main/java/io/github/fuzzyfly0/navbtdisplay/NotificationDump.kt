package io.github.fuzzyfly0.navbtdisplay

import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.RemoteViews
import android.widget.TextView

/** Helpers to get at the text of a notification, including custom RemoteViews layouts. */
object NotificationDump {

    /** Human-readable dump of everything text-like in the notification, for the debug log. */
    fun describe(context: Context, sbn: StatusBarNotification): String {
        val n = sbn.notification
        val sb = StringBuilder()
        sb.append("pkg=").append(sbn.packageName)
            .append(" id=").append(sbn.id)
            .append(" tag=").append(sbn.tag)
            .append(" ongoing=").append(n.flags and Notification.FLAG_ONGOING_EVENT != 0)
            .append(" groupSummary=").append(n.flags and Notification.FLAG_GROUP_SUMMARY != 0)
            .append(" category=").append(n.category)
            .append(" channel=").append(n.channelId)
            .append(" style=").append(n.extras.getString(Notification.EXTRA_TEMPLATE))
        n.tickerText?.let { sb.append("\n  ticker=").append(it) }
        for (key in n.extras.keySet().sorted()) {
            @Suppress("DEPRECATION")
            val value = n.extras.get(key) ?: continue
            if (value is CharSequence || value is Number || value is Boolean) {
                sb.append("\n  ").append(key).append('=').append(value)
            } else if (value is Array<*> && value.isArrayOf<CharSequence>()) {
                sb.append("\n  ").append(key).append('=').append(value.joinToString(" | "))
            }
        }
        @Suppress("DEPRECATION")
        val views = listOf(
            "content" to n.contentView,
            "big" to n.bigContentView,
            "headsUp" to n.headsUpContentView,
        )
        for ((name, rv) in views) {
            if (rv == null) continue
            sb.append("\n  remoteViews[").append(name).append("]=")
                .append(remoteViewsTexts(context, rv).joinToString(" | "))
        }
        return sb.toString()
    }

    /**
     * Whether [sbn] can be a running-guidance notification at all: navigation
     * apps also post other notifications (downloads, trip summaries, ...).
     */
    fun isGuidanceCandidate(sbn: StatusBarNotification): Boolean {
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return false
        return n.flags and Notification.FLAG_ONGOING_EVENT != 0 ||
            n.category == Notification.CATEGORY_NAVIGATION
    }

    /** The raw text fields; RemoteViews are only inflated when the extras carry no text. */
    fun fields(context: Context, sbn: StatusBarNotification): RawNavFields {
        val n = sbn.notification
        val e = n.extras
        fun cs(key: String) = e.getCharSequence(key)?.toString()
        val raw = RawNavFields(
            title = cs(Notification.EXTRA_TITLE_BIG) ?: cs(Notification.EXTRA_TITLE),
            text = cs(Notification.EXTRA_TEXT),
            bigText = cs(Notification.EXTRA_BIG_TEXT),
            subText = cs(Notification.EXTRA_SUB_TEXT),
            infoText = cs(Notification.EXTRA_INFO_TEXT),
        )
        if (!NavText.clean(raw.title).isNullOrEmpty() || !NavText.clean(raw.text).isNullOrEmpty()) {
            return raw
        }
        @Suppress("DEPRECATION")
        val rv = n.bigContentView ?: n.contentView ?: return raw
        return raw.copy(viewTexts = remoteViewsTexts(context, rv).filterNot { it.startsWith("<inflate") })
    }

    /**
     * Inflates [rv] off-screen and collects the text of all visible TextViews.
     * Some navigation apps only draw their guidance into a custom layout.
     */
    fun remoteViewsTexts(context: Context, rv: RemoteViews): List<String> = runCatching {
        val root = rv.apply(context, FrameLayout(context))
        val out = mutableListOf<String>()
        collect(root, out)
        out.toList()
    }.getOrElse { listOf("<inflate failed: ${it.javaClass.simpleName}>") }

    private fun collect(view: View, out: MutableList<String>) {
        if (view.visibility != View.VISIBLE) return
        if (view is TextView) {
            view.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let(out::add)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) collect(view.getChildAt(i), out)
        }
    }
}
