package app.earbud.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import java.util.concurrent.atomic.AtomicInteger

/** Real disposable system notifications; only these own-app notifications enter the reader. */
object TestNotifications {
    private const val CHANNEL = "reading_test"
    private const val TAG = "earbud_reading_test"
    private const val MARKER = "app.earbud.companion.TEST_NOTIFICATION"
    private val ids = AtomicInteger(1000)
    const val DEFAULT_BODY = "이것은 테스트 알림입니다. 이어폰으로 들리는지 확인해 주세요. " +
        "음악이나 영상을 재생하면서 알림을 지우면 낭독이 멈추고 원래 소리가 돌아오는지 확인할 수 있습니다."

    fun isTest(sbn: StatusBarNotification, ownPackage: String): Boolean = sbn.packageName == ownPackage &&
        sbn.tag == TAG && sbn.notification.extras.getBoolean(MARKER) &&
        sbn.notification.flags and Notification.FLAG_ONGOING_EVENT == 0

    fun post(context: Context, body: String, count: Int = 1) {
        require(body.isNotBlank())
        require(count in 1..3)
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "테스트 알림", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "낭독과 실제 알림 삭제를 확인하는 테스트"; setSound(null, null); enableVibration(false)
        })
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        repeat(count) { index ->
            val id = ids.incrementAndGet()
            val title = if (count == 1) "테스트 발신자" else "테스트 발신자 ${index + 1}"
            val notification = NotificationCompat.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_earbud)
                .setContentTitle(title).setContentText(body.take(2000))
                .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(2000)))
                .setContentIntent(open).setAutoCancel(true)
                .setExtras(Bundle().apply { putBoolean(MARKER, true) }).build()
            manager.notify(TAG, id, notification)
        }
    }

    fun clear(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.activeNotifications.filter { isTest(it, context.packageName) }.forEach { manager.cancel(it.tag, it.id) }
    }
}
