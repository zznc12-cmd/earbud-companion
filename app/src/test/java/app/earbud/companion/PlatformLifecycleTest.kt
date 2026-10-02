package app.earbud.companion

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** API 33 JVM lifecycle tests are separate from S26 audio/hardware acceptance. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PlatformLifecycleTest {
    @Test fun homeActivityStartsWithoutOptionalPermissions() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertFalse(controller.get().isFinishing)
        assertNotNull(controller.get().window.decorView)
        controller.pause().stop().destroy()
    }
    @Test fun missingPermissionsBlockStartingAnnouncements() {
        val context = RuntimeEnvironment.getApplication() as CompanionApp
        assertFalse(ReadingService.prerequisites(context))
        val route = HeadphoneRoutes(context).current(ReadSettings())
        assertFalse(route.ready)
        assertEquals("블루투스 권한이 필요합니다", route.label)
    }
    @Test fun foregroundServiceCreatesAndCleansUpWithoutAnAudioDevice() {
        val app = RuntimeEnvironment.getApplication() as CompanionApp
        val controller = Robolectric.buildService(ReadingService::class.java).create()
        assertSame(controller.get(), ReadingService.instance)
        assertTrue(app.status.value.serviceRunning)
        controller.destroy()
        assertNull(ReadingService.instance)
        assertFalse(app.status.value.serviceRunning)
        assertFalse(app.status.value.speaking)
    }
    @Test fun manifestRestrictsListenerAndAccessibilityBindingAndHasNoInternetPermission() {
        val context = RuntimeEnvironment.getApplication() as CompanionApp
        val pm = context.packageManager
        val listener = pm.getServiceInfo(ComponentName(context, NotificationReader::class.java), PackageManager.ComponentInfoFlags.of(0))
        val observer = pm.getServiceInfo(ComponentName(context, BannerObserver::class.java), PackageManager.ComponentInfoFlags.of(0))
        assertEquals("android.permission.BIND_NOTIFICATION_LISTENER_SERVICE", listener.permission)
        assertEquals("android.permission.BIND_ACCESSIBILITY_SERVICE", observer.permission)
        assertEquals(PackageManager.PERMISSION_DENIED, pm.checkPermission("android.permission.INTERNET", context.packageName))
    }
    @Test fun accessibilityObserverDoesNotTreatDisappearanceAsDismissal() {
        val app = RuntimeEnvironment.getApplication() as CompanionApp
        val controller = Robolectric.buildService(BannerObserver::class.java).create()
        val event = android.view.accessibility.AccessibilityEvent.obtain(android.view.accessibility.AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED)
        event.packageName = "com.android.systemui"
        controller.get().onAccessibilityEvent(event)
        assertEquals(0, app.status.value.bannerEventCount)
        assertFalse(app.status.value.speaking)
        controller.destroy()
    }
}
