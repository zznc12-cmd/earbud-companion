package app.earbud.companion

import android.app.Notification
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import kotlinx.coroutines.flow.update

class NotificationReader : NotificationListenerService() {
    private val main = Handler(Looper.getMainLooper())
    private val app get() = application as CompanionApp

    override fun onListenerConnected() {
        app.status.update { it.copy(listenerConnected = true) }
        // Deliberately do not enumerate activeNotifications: old messages are never replayed.
    }

    override fun onListenerDisconnected() {
        app.status.update { it.copy(listenerConnected = false) }
        main.post { ReadingService.instance?.stopAll("알림 접근 연결이 끊겼습니다") }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val test = TestNotifications.isTest(sbn, packageName)
        if (sbn.packageName == packageName && !test) return
        val n = sbn.notification
        val extras = n.extras
        val latest = extras.getParcelableArray(Notification.EXTRA_MESSAGES, android.os.Bundle::class.java)?.lastOrNull()
        val person = latest?.getParcelable("sender_person", android.app.Person::class.java)
        val sender = person?.name?.toString() ?: latest?.getCharSequence("sender")?.toString()
        val title = sender ?: extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val body = latest?.getCharSequence("text")?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.lastOrNull()?.toString().orEmpty()
        val notice = Notice(sbn.key, sbn.packageName, title.take(200), body.take(4000), sbn.postTime,
            ongoing = n.flags and Notification.FLAG_ONGOING_EVENT != 0,
            groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0, isTest = test)
        main.post { ReadingService.instance?.offer(notice) }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification, rankingMap: RankingMap, reason: Int) {
        // Any actual removal means this notification is no longer relevant, including read/click.
        main.post { ReadingService.instance?.remove(sbn.key) }
    }
}
