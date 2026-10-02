package app.earbud.companion

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.settingsDataStore by preferencesDataStore("earbud_settings")

class SettingsStore(private val context: Context, scope: CoroutineScope) {
    private object K {
        val enabled = booleanPreferencesKey("enabled")
        val apps = stringSetPreferencesKey("apps")
        val title = booleanPreferencesKey("title_only")
        val off = booleanPreferencesKey("screen_off_only")
        val quiet = booleanPreferencesKey("quiet")
        val start = intPreferencesKey("quiet_start")
        val end = intPreferencesKey("quiet_end")
        val max = intPreferencesKey("max_chars")
        val rate = floatPreferencesKey("rate")
        val volume = floatPreferencesKey("speech_volume")
        val voice = stringPreferencesKey("voice")
        val pause = longPreferencesKey("paused_until")
        val connect = intPreferencesKey("connection_volume")
        val devices = stringSetPreferencesKey("approved_devices")
        val banner = booleanPreferencesKey("banner_diagnostics")
    }
    private fun decode(p: Preferences) = ReadSettings(
        enabled = p[K.enabled] ?: false, apps = p[K.apps] ?: emptySet(),
        titleOnly = p[K.title] ?: false, screenOffOnly = p[K.off] ?: false,
        quietEnabled = p[K.quiet] ?: false, quietStart = p[K.start] ?: 1320,
        quietEnd = p[K.end] ?: 420, maxChars = p[K.max] ?: 200,
        rate = p[K.rate] ?: 1f, speechVolume = p[K.volume] ?: .8f,
        voice = p[K.voice] ?: "", pausedUntil = p[K.pause] ?: 0,
        connectionVolume = p[K.connect] ?: -1, approvedDevices = p[K.devices] ?: emptySet(),
        bannerDiagnostics = p[K.banner] ?: false,
    )
    val flow = context.settingsDataStore.data.map(::decode)
    val state = flow.stateIn(scope, SharingStarted.Eagerly, ReadSettings())

    suspend fun update(change: (ReadSettings) -> ReadSettings) {
        context.settingsDataStore.edit { p ->
            val s = change(decode(p))
            p[K.enabled] = s.enabled; p[K.apps] = s.apps
            p[K.title] = s.titleOnly; p[K.off] = s.screenOffOnly
            p[K.quiet] = s.quietEnabled; p[K.start] = s.quietStart; p[K.end] = s.quietEnd
            p[K.max] = s.maxChars.coerceIn(20, 1000); p[K.rate] = s.rate.coerceIn(.5f, 2f)
            p[K.volume] = s.speechVolume.coerceIn(0f, 1f); p[K.voice] = s.voice
            p[K.pause] = s.pausedUntil; p[K.connect] = s.connectionVolume
            p[K.devices] = s.approvedDevices; p[K.banner] = s.bannerDiagnostics
        }
    }
}
