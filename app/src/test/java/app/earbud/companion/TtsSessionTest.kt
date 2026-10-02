package app.earbud.companion

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowTextToSpeech
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TtsSessionTest {
    private val app get() = RuntimeEnvironment.getApplication() as CompanionApp
    private val engines = mutableListOf<String>()
    private val instances = mutableListOf<TextToSpeech>()

    @Before fun setup() {
        registerEngine("test.google")
        registerEngine("test.samsung")
        ShadowTextToSpeech.addLanguageAvailability(Locale.KOREA)
        ShadowTextToSpeech.addVoice(Voice("offline-ko", Locale.KOREA, Voice.QUALITY_NORMAL, Voice.LATENCY_NORMAL, false, emptySet()))
    }

    private fun registerEngine(name: String) {
        val application = ApplicationInfo().apply { packageName = name; enabled = true; nonLocalizedLabel = name }
        val service = ServiceInfo().apply { packageName = name; this.name = "$name.TtsService"; enabled = true; applicationInfo = application }
        shadowOf(app.packageManager).installPackage(PackageInfo().apply { packageName = name; applicationInfo = application; services = arrayOf(service) })
        shadowOf(app.packageManager).addResolveInfoForIntent(Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE).setPackage(name),
            ResolveInfo().apply { serviceInfo = service })
    }

    private fun session(available: List<String> = listOf("test.google", "test.samsung"),
                        code: () -> Int = { TextToSpeech.SUCCESS }) = TtsSession(app,
        factory = { c, listener, engine ->
            engines += engine
            TextToSpeech(c, listener, engine).also { instances += it; listener.onInit(code()) }
        }, catalog = { available.map { name -> TextToSpeech.EngineInfo().apply { this.name = name; label = name } } })

    @Test fun systemEngineChangeReleasesOldEngineAndBindsTheNewOne() = runBlocking {
        Settings.Secure.putString(app.contentResolver, "tts_default_synth", "test.google")
        val session = session()
        assertTrue(session.prepare())
        val old = instances.single()
        Settings.Secure.putString(app.contentResolver, "tts_default_synth", "test.samsung")
        assertTrue(session.prepare())
        assertEquals(listOf("test.google", "test.samsung"), engines)
        assertTrue(shadowOf(old).isShutdown)
        assertEquals("test.samsung", session.enginePackage)
        session.shutdown()
    }

    @Test fun failedInitializationIsRetriedInsteadOfKeepingADeadClient() = runBlocking {
        Settings.Secure.putString(app.contentResolver, "tts_default_synth", "test.samsung")
        var first = true
        val session = session { if (first) { first = false; TextToSpeech.ERROR } else TextToSpeech.SUCCESS }
        assertFalse(session.prepare())
        assertTrue(shadowOf(instances.single()).isShutdown)
        assertTrue(session.prepare())
        assertEquals(2, instances.size)
        session.shutdown()
    }

    @Test fun unavailableSelectedEngineIsNotSilentlySubstituted() = runBlocking {
        Settings.Secure.putString(app.contentResolver, "tts_default_synth", "missing.engine")
        val session = session()
        assertFalse(session.prepare())
        assertTrue(instances.isEmpty())
        assertTrue(session.voices.isEmpty())
    }

    @Test fun privateSystemEngineIsExplicitlyReplacedAndReportedAsPublicEngine() = runBlocking {
        Settings.Secure.putString(app.contentResolver, "tts_default_synth", "test.samsung")
        val session = session(available = listOf("test.google"))
        assertTrue(session.prepare())
        assertEquals(listOf("test.samsung", "test.google"), engines)
        assertTrue(shadowOf(instances.first()).isShutdown)
        assertEquals("test.google", session.enginePackage)
        assertEquals("test.samsung", session.systemEngineLabel)
        assertTrue(session.notice.contains("test.google"))
        assertTrue(session.prepare())
        assertEquals(2, instances.size) // Does not repeatedly probe the rejected default.
        session.shutdown()
    }
}
