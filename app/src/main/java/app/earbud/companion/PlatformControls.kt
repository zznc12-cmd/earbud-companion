package app.earbud.companion

import android.accessibilityservice.AccessibilityService
import android.app.PendingIntent
import android.content.*
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Diagnostics only: disappearance is NEVER treated as evidence of a user gesture. */
class BannerObserver : AccessibilityService() {
    private val app get() = application as CompanionApp
    override fun onServiceConnected() { app.status.update { it.copy(bannerConnected = true) } }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() != "com.android.systemui" || !app.settings.state.value.bannerDiagnostics) return
        app.status.update { it.copy(bannerEventCount = it.bannerEventCount + 1, lastBannerEvent = event.eventType) }
        // No node/text capture, gesture interception, or cancellation from ambiguous UI events.
        // Promote to a gesture detector only after device-specific evidence and regression tests.
    }
    override fun onInterrupt() = Unit
    override fun onDestroy() { app.status.update { it.copy(bannerConnected = false) }; super.onDestroy() }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val result = goAsync()
        val app = context.applicationContext as CompanionApp
        app.scope.launch {
            try {
                if (app.settings.flow.first().enabled && ReadingService.prerequisites(context)) {
                    // Boot restores specialUse monitoring, never starts mediaPlayback at boot.
                    runCatching { ReadingService.command(context, ReadingService.START) }
                        .onFailure { app.status.update { s -> s.copy(message = "앱을 열어 알림 읽기를 다시 시작하세요") } }
                }
            } finally { result.finish() }
        }
    }
}

class ReadingTile : TileService() {
    private val app get() = application as CompanionApp
    override fun onStartListening() {
        app.scope.launch { render(app.settings.flow.first()) }
    }
    private fun render(s: ReadSettings) {
        qsTile?.apply {
            state = if (s.enabled && s.pausedUntil <= System.currentTimeMillis()) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            subtitle = if (s.enabled && s.pausedUntil > System.currentTimeMillis()) "쉬는 중" else if (s.enabled) "켜짐" else "꺼짐"
            updateTile()
        }
    }
    override fun onClick() {
        super.onClick()
        if (!ReadingService.prerequisites(this)) {
            val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 4, intent, PendingIntent.FLAG_IMMUTABLE))
            else openOnAndroid13(intent)
            return
        }
        app.scope.launch {
            val s = app.settings.flow.first()
            val enable = !s.enabled || s.pausedUntil > System.currentTimeMillis()
            app.settings.update { it.copy(enabled = enable, pausedUntil = 0) }
            ReadingService.command(this@ReadingTile, if (enable) ReadingService.START else ReadingService.STOP)
            render(app.settings.flow.first())
        }
    }
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated")
    @Suppress("DEPRECATION")
    private fun openOnAndroid13(intent: Intent) {
        // PendingIntent overload does not exist on API 33; caller guards this compatibility path.
        startActivityAndCollapse(intent)
    }
}
