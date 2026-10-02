package app.earbud.companion

import android.content.Context
import android.media.*
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.PI
import kotlin.math.sin

/** One serialized utterance. The track and focus are synchronously released on cancellation. */
class SpeechEngine(private val app: CompanionApp, private val routes: HeadphoneRoutes) {
    private val init = CompletableDeferred<Boolean>()
    private val waits = ConcurrentHashMap<String, CompletableDeferred<Unit>>()
    @Volatile private var track: AudioTrack? = null
    private var focus: AudioFocusRequest? = null
    private val tts = TextToSpeech(app) { code -> init.complete(code == TextToSpeech.SUCCESS) }
    private val audio = routes.audio
    private val tempDir = File(app.cacheDir, "speech").apply {
        mkdirs(); listFiles()?.forEach { it.delete() }
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) = Unit
            override fun onDone(id: String?) { id?.let { waits.remove(it)?.complete(Unit) } }
            @Deprecated("Legacy callback") override fun onError(id: String?) { fail(id) }
            override fun onError(id: String?, code: Int) { fail(id) }
            override fun onStop(id: String?, interrupted: Boolean) { id?.let { waits.remove(it)?.cancel() } }
            private fun fail(id: String?) {
                id?.let { waits.remove(it)?.completeExceptionally(IllegalStateException("음성 생성 실패")) }
            }
        })
    }

    private fun offlineVoices(): List<Voice> = runCatching { tts.voices.orEmpty() }.getOrDefault(emptySet()).filter {
        it.locale.language == "ko" && !it.isNetworkConnectionRequired &&
            TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty()
    }.sortedWith(compareByDescending<Voice> { it.quality }.thenBy { it.name })

    suspend fun prepare(): Boolean {
        val initialized = withTimeoutOrNull(15_000) { init.await() } == true
        val voices = if (initialized) offlineVoices() else emptyList()
        app.status.update { it.copy(offlineVoiceReady = voices.isNotEmpty(),
            voices = voices.map { v -> v.name to "${v.locale.displayName} · ${v.name}" }) }
        return voices.isNotEmpty()
    }

    suspend fun speak(text: String, settings: ReadSettings, device: AudioDeviceInfo,
                      valid: () -> Boolean) {
        check(prepare()) { "오프라인 한국어 음성을 설치하세요" }
        currentCoroutineContext().ensureActive()
        check(valid()) { "낭독 조건이 바뀌었습니다" }
        val voices = offlineVoices()
        val voice = voices.firstOrNull { it.name == settings.voice } ?: voices.first()
        check(tts.setVoice(voice) == TextToSpeech.SUCCESS)
        tts.setLanguage(Locale.KOREA)
        // setLanguage can replace a voice; select the verified offline voice last.
        check(tts.setVoice(voice) == TextToSpeech.SUCCESS)
        tts.setSpeechRate(settings.rate)
        val id = UUID.randomUUID().toString()
        val file = File(tempDir, "$id.wav")
        val done = CompletableDeferred<Unit>()
        waits[id] = done
        try {
            check(tts.synthesizeToFile(text, Bundle(), file, id) == TextToSpeech.SUCCESS)
            withTimeout(30_000) { done.await() }
            currentCoroutineContext().ensureActive()
            check(valid()) { "취소된 알림입니다" }
            val wave = withContext(Dispatchers.IO) { WaveData.read(file) }
            play(wave, device, settings.speechVolume, valid)
        } finally {
            waits.remove(id)?.cancel()
            tts.stop()
            stopAudio()
            file.delete()
        }
    }

    suspend fun testChannels(settings: ReadSettings, device: AudioDeviceInfo, valid: () -> Boolean) {
        val rate = 24_000
        val frames = rate * 2
        val data = ByteArray(frames * 4)
        val buffer = java.nio.ByteBuffer.wrap(data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        repeat(frames) { frame ->
            val audible = frame % rate < rate * 3 / 4
            val value = if (audible) (sin(2 * PI * 440 * frame / rate) * 5000).toInt().toShort() else 0.toShort()
            buffer.putShort(if (frame < rate) value else 0)
            buffer.putShort(if (frame >= rate) value else 0)
        }
        try { play(WaveData(rate, 2, 16, data), device, settings.speechVolume, valid) }
        finally { stopAudio() }
    }

    private suspend fun play(wave: WaveData, device: AudioDeviceInfo, volume: Float, valid: () -> Boolean) {
        currentCoroutineContext().ensureActive()
        check(valid())
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(routes.attributes).setAcceptsDelayedFocusGain(false)
            .setOnAudioFocusChangeListener({ change ->
                if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                    ReadingService.instance?.stopCurrent("다른 오디오가 시작되어 낭독을 중단했습니다")
                }
            }, Handler(Looper.getMainLooper())).build()
        focus = request
        check(audio.requestAudioFocus(request) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "오디오 포커스를 얻지 못했습니다" }
        val encoding = if (wave.bits == 16) AudioFormat.ENCODING_PCM_16BIT else AudioFormat.ENCODING_PCM_8BIT
        val channels = if (wave.channels == 1) AudioFormat.CHANNEL_OUT_MONO else AudioFormat.CHANNEL_OUT_STEREO
        val bytesPerFrame = wave.channels * wave.bits / 8
        val minBuffer = AudioTrack.getMinBufferSize(wave.sampleRate, channels, encoding)
        check(minBuffer > 0)
        val t = AudioTrack.Builder().setAudioAttributes(routes.attributes)
            .setAudioFormat(AudioFormat.Builder().setSampleRate(wave.sampleRate)
                .setEncoding(encoding).setChannelMask(channels).build())
            .setBufferSizeInBytes(maxOf(minBuffer, wave.sampleRate * bytesPerFrame / 10))
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        track = t
        check(t.state == AudioTrack.STATE_INITIALIZED)
        check(t.setPreferredDevice(device))
        val routeListener = AudioRouting.OnRoutingChangedListener {
            if (track === t && t.routedDevice != null && !actualRouteSafe(t, device))
                ReadingService.instance?.stopCurrent("이어폰 출력이 변경되어 중단했습니다")
        }
        t.addOnRoutingChangedListener(routeListener, Handler(Looper.getMainLooper()))
        t.setVolume(volume)
        try {
            // Only silence is submitted until BOTH actual and anticipated routing are verified.
            val silent = ByteArray(wave.sampleRate * bytesPerFrame / 10) { if (wave.bits == 8) 128.toByte() else 0 }
            var prefilled = 0
            while (prefilled < silent.size) {
                val written = t.write(silent, prefilled, silent.size - prefilled, AudioTrack.WRITE_NON_BLOCKING)
                check(written >= 0)
                if (written == 0) break
                prefilled += written
            }
            t.play()
            withTimeout(1500) {
                while (!actualRouteSafe(t, device)) { check(valid()); delay(20) }
            }
            var offset = 0
            while (offset < wave.pcm.size) {
                currentCoroutineContext().ensureActive()
                check(valid() && actualRouteSafe(t, device)) { "이어폰 출력 확인 실패" }
                val count = minOf(wave.pcm.size - offset, wave.sampleRate * bytesPerFrame / 50)
                val n = t.write(wave.pcm, offset, count, AudioTrack.WRITE_NON_BLOCKING)
                check(n >= 0) { "음성 재생 실패" }
                offset += n
                delay(5)
            }
            val frames = (prefilled + wave.pcm.size) / bytesPerFrame
            withTimeout(wave.pcm.size.toLong() * 1000 / (wave.sampleRate * bytesPerFrame) + 2000) {
                while (t.playbackHeadPosition < frames) {
                    check(valid() && actualRouteSafe(t, device)); delay(15)
                }
            }
        } finally {
            runCatching { t.removeOnRoutingChangedListener(routeListener) }
            stopAudio()
        }
    }

    private fun actualRouteSafe(t: AudioTrack, expected: AudioDeviceInfo): Boolean = runCatching {
        val devices = if (Build.VERSION.SDK_INT >= 36) t.routedDevices else listOfNotNull(t.routedDevice)
        devices.size == 1 && devices.single().id == expected.id
    }.getOrDefault(false)

    fun stopAudio() {
        val t = track; track = null
        if (t != null) {
            runCatching { t.pause() }; runCatching { t.flush() }; runCatching { t.release() }
        }
        focus?.let { runCatching { audio.abandonAudioFocusRequest(it) } }; focus = null
    }

    fun cancel() {
        tts.stop()
        waits.values.forEach { it.cancel() }; waits.clear()
        stopAudio()
    }
    fun shutdown() { cancel(); tts.shutdown() }
}
