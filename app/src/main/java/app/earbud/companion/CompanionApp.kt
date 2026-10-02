package app.earbud.companion

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow

data class AppStatus(
    val serviceRunning: Boolean = false,
    val listenerConnected: Boolean = false,
    val output: String = "출력 확인 중",
    val routeReady: Boolean = false,
    val unknownDevice: String? = null,
    val message: String = "설정을 마친 뒤 알림 읽기를 켜세요",
    val speaking: Boolean = false,
    val offlineVoiceReady: Boolean = false,
    val voices: List<Pair<String, String>> = emptyList(),
    val enginePackage: String = "",
    val engineLabel: String = "확인 중",
    val voiceLabel: String = "",
    val ttsError: String = "",
    val systemEngineLabel: String = "확인 중",
    val ttsNotice: String = "",
    val lastResult: String = "아직 낭독 기록 없음",
    val bannerConnected: Boolean = false,
    val bannerEventCount: Int = 0,
    val lastBannerEvent: Int = 0,
)

class CompanionApp : Application() {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    lateinit var settings: SettingsStore
        private set
    val status = MutableStateFlow(AppStatus())
    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this, scope)
    }
}
