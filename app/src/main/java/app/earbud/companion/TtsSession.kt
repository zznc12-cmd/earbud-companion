package app.earbud.companion

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.update
import java.util.Locale

/** Rebind when the system selection changes, instead of keeping the startup engine forever. */
class TtsSession(
    private val context: Context,
    private val factory: (Context, TextToSpeech.OnInitListener, String) -> TextToSpeech =
        { c, listener, engine -> TextToSpeech(c, listener, engine) },
    private val catalog: (TextToSpeech) -> List<TextToSpeech.EngineInfo> = { it.engines },
) {
    private val mutex = Mutex()
    private var initialized: CompletableDeferred<Boolean>? = null
    private var systemSelection = ""
    var client: TextToSpeech? = null
        private set
    var enginePackage: String = ""
        private set
    var engineLabel: String = "확인 중"
        private set
    var voices: List<Voice> = emptyList()
        private set
    var error: String = ""
        private set
    var notice: String = ""
        private set
    var systemEngineLabel: String = "확인 중"
        private set
    var availableEngines: List<Pair<String, String>> = emptyList()
        private set

    suspend fun prepare(): Boolean = mutex.withLock {
        val selected = systemEngine(context)
        if (selected.isBlank() || !engineAvailable(context, selected)) {
            shutdown()
            enginePackage = ""
            notice = ""
            systemEngineLabel = if (selected.isBlank()) "미설정" else label(selected)
            engineLabel = "사용 가능한 기본 TTS 없음"
            error = "휴대폰 설정에서 TTS 엔진을 선택하세요"
            return@withLock false
        }
        systemEngineLabel = label(selected)
        if (client == null || selected != systemSelection) {
            shutdown()
            systemSelection = selected
            if (!connect(selected)) return@withLock false
            // One UI can redirect Samsung's private engine even after SUCCESS. Its public
            // engine catalog excludes the private engine, so bind a listed engine explicitly.
            availableEngines = catalog(client!!).map { it.name to it.label }
            val usable = availableEngines.firstOrNull { it.first == selected } ?: availableEngines.firstOrNull()
            if (usable == null) {
                shutdown()
                engineLabel = "사용 가능한 TTS 없음"
                error = "이 앱에서 사용할 수 있는 TTS 엔진을 설치하세요"
                return@withLock false
            }
            if (usable.first != selected) {
                shutdown()
                if (!connect(usable.first)) return@withLock false
            }
            engineLabel = usable.second
            notice = if (usable.first != selected)
                "휴대폰 기본값은 ${systemEngineLabel}입니다. 이 앱에 허용된 엔진 목록에 없어 ${engineLabel}을 사용합니다." else ""
        }
        if (systemEngine(context) != selected) {
            shutdown()
            error = "TTS 설정이 변경되었습니다. 다시 시도하세요"
            return@withLock false
        }
        // Initialize Korean before asking engines such as Samsung for installed voices.
        val tts = client!!
        tts.setLanguage(Locale.KOREA)
        voices = tts.voices.orEmpty().filter(::offlineKorean)
            .sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.name })
        error = if (voices.isEmpty()) "${engineLabel}의 오프라인 한국어 음성을 설치하세요" else ""
        voices.isNotEmpty()
    }

    private fun label(engine: String): String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(
            engine, PackageManager.ApplicationInfoFlags.of(0))).toString()
    }.getOrDefault(engine)

    private suspend fun connect(engine: String): Boolean {
        enginePackage = engine
        engineLabel = label(engine)
        val ready = CompletableDeferred<Boolean>()
        initialized = ready
        try {
            client = factory(context, TextToSpeech.OnInitListener { ready.complete(it == TextToSpeech.SUCCESS) }, engine)
        } catch (_: Exception) {
            shutdown()
            error = "${engineLabel} 연결을 시작하지 못했습니다"
            return false
        }
        if (withTimeoutOrNull(15_000) { ready.await() } != true) {
            shutdown()
            error = "${engineLabel}에 연결하지 못했습니다. 다시 시도하세요"
            return false
        }
        return true
    }

    fun chooseVoice(name: String): Voice? = voices.firstOrNull { it.name == name }
        ?: client?.voice?.takeIf { it in voices }
        ?: voices.firstOrNull()

    fun stop() { client?.stop() }
    fun shutdown() {
        client?.stop()
        client?.shutdown()
        client = null
        initialized?.cancel()
        initialized = null
        voices = emptyList()
    }

    companion object {
        fun systemEngine(context: Context): String = Settings.Secure.getString(
            context.contentResolver, "tts_default_synth").orEmpty()

        fun engineAvailable(context: Context, engine: String): Boolean =
            context.packageManager.queryIntentServices(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE)
                .setPackage(engine), PackageManager.ResolveInfoFlags.of(0)).any {
                it.serviceInfo.enabled && it.serviceInfo.applicationInfo.enabled
            }

        private fun offlineKorean(v: Voice): Boolean = v.locale.language == "ko" &&
            !v.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features.orEmpty()
    }
}

suspend fun refreshTtsCatalog(app: CompanionApp) {
    ReadingService.instance?.let { it.refreshVoiceCatalog(); return }
    val session = TtsSession(app)
    try {
        val ready = session.prepare()
        if (!app.status.value.serviceRunning) app.status.update { it.copy(
            offlineVoiceReady = ready, enginePackage = session.enginePackage, engineLabel = session.engineLabel,
            voiceLabel = session.chooseVoice(app.settings.state.value.voice)?.name.orEmpty(),
            voices = session.voices.map { v -> v.name to "${v.locale.displayName} · ${v.name}" }, ttsError = session.error,
            systemEngineLabel = session.systemEngineLabel, ttsNotice = session.notice,
        ) }
    } finally { session.shutdown() }
}
