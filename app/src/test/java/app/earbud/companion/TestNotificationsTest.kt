package app.earbud.companion

import android.app.NotificationManager
import android.service.notification.StatusBarNotification
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TestNotificationsTest {
    private val app get() = RuntimeEnvironment.getApplication() as CompanionApp
    private val manager get() = app.getSystemService(NotificationManager::class.java)

    @Test fun generatedNotificationsAreRealDistinctAndClassifiedAsTests() {
        TestNotifications.post(app, "직접 작성한 테스트", 3)
        val notifications = manager.activeNotifications
        assertEquals(3, notifications.size)
        assertEquals(3, notifications.map { it.key }.toSet().size)
        assertTrue(notifications.all { TestNotifications.isTest(it, app.packageName) })
        assertTrue(notifications.all { it.notification.extras.getCharSequence("android.bigText") == "직접 작성한 테스트" })
    }

    @Test fun clearRemovesOnlyTestNotificationsAndKeepsMonitoringStatus() {
        TestNotifications.post(app, "테스트")
        val status = manager.activeNotifications.single().notification
        manager.notify(10, status)
        TestNotifications.clear(app)
        assertEquals(1, manager.activeNotifications.size)
        assertEquals(10, manager.activeNotifications.single().id)
        assertFalse(TestNotifications.isTest(manager.activeNotifications.single(), app.packageName))
    }

    @Test fun anotherPackageCannotUseOurMarkerToBypassAppSelection() {
        TestNotifications.post(app, "테스트")
        val real = manager.activeNotifications.single()
        val spoofed = StatusBarNotification("other.app", "other.app", real.id, real.tag, 123, 0, 0,
            real.notification, real.user, real.postTime)
        assertFalse(TestNotifications.isTest(spoofed, app.packageName))
    }
}
