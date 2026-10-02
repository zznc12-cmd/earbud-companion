package app.earbud.companion

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.time.LocalTime

class ReadingService : Service() {
    companion object {
        const val START = "app.earbud.START"
        const val STOP = "app.earbud.STOP"
        const val PAUSE = "app.earbud.PAUSE"
        const val CANCEL = "app.earbud.CANCEL"
        const val TEST = "app.earbud.TEST"
        const val CHANNELS = "app.earbud.CHANNELS"
        private const val CHANNEL = "reading_status"
        private const val ID = 10
        var instance: ReadingService? = null
            private set

        fun command(context: Context, action: String) {
            val intent = Intent(context, ReadingService::class.java).setAction(action)
            if (action in listOf(START, TEST, CHANNELS)) ContextCompat.startForegroundService(context, intent)
            else if (instance != null) context.startService(intent)
        }

        fun prerequisites(context: Context): Boolean =
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
                NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName) &&
                NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as CompanionApp
    private lateinit var routes: HeadphoneRoutes
    private lateinit var engine: SpeechEngine
    private val queue = NoticeQueue()
    private var settings = ReadSettings()
    private var activeJob: Job? = null
    private var active: Notice? = null
    private var testActive = false
    private var lastDevice: Int? = null
    private var lastNotification = ""
    private var settingsLoaded = false
    private var permanentStop = false
    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(devices: Array<out AudioDeviceInfo>) { refreshRoute() }
        override fun onAudioDevicesRemoved(devices: Array<out AudioDeviceInfo>) { refreshRoute() }
    }
    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_ON && settings.screenOffOnly)
                stopAll("화면이 켜져 낭독을 중단했습니다")
            if (intent.action == NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED && isDnd())
                stopAll("방해금지가 켜져 낭독을 중단했습니다")
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        routes = HeadphoneRoutes(this)
        engine = SpeechEngine(app, routes)
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "알림 읽기 상태", NotificationManager.IMPORTANCE_LOW).apply {
            description = "이어폰 알림 읽기 작동 상태와 제어"; setSound(null, null)
        })
        promote(false)
        app.status.update { it.copy(serviceRunning = true) }
        routes.audio.registerAudioDeviceCallback(deviceCallback, Handler(mainLooper))
        routes.audio.addOnModeChangedListener(mainExecutor, modeListener)
        registerReceiver(stateReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }, RECEIVER_NOT_EXPORTED)
        scope.launch {
            app.settings.flow.collect { s ->
                val previous = settings
                settings = s
                settingsLoaded = true
                if ((!s.enabled && previous.enabled) || s.pausedUntil > System.currentTimeMillis()) stopAll("알림 읽기가 쉬는 중입니다")
                active?.let { if (!testActive && !allowed(it, false)) stopCurrent("낭독 조건이 바뀌었습니다") }
                refreshRoute()
                updateNotification()
            }
        }
        scope.launch {
            engine.prepare()
            while (isActive) {
                refreshRoute()
                if (isInCall() || isDnd() || (settings.screenOffOnly && isScreenOn()) ||
                    ReadingPolicy.quietNow(settings, LocalTime.now()) ||
                    !NotificationManagerCompat.getEnabledListenerPackages(this@ReadingService).contains(packageName)) {
                    if (activeJob != null) stopAll("현재 상황에서는 낭독하지 않습니다")
                }
                updateNotification()
                delay(250)
            }
        }
    }

    private val modeListener = AudioManager.OnModeChangedListener { mode ->
        if (mode != AudioManager.MODE_NORMAL) stopAll("통화 또는 다른 음성 작업 중입니다")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action ?: START
        scope.launch {
            settings = app.settings.flow.first()
            settingsLoaded = true
            when (action) {
                STOP -> {
                    permanentStop = true
                    app.settings.update { it.copy(enabled = false, pausedUntil = 0) }
                    stopAll("알림 읽기를 껐습니다"); stopSelf()
                }
                PAUSE -> {
                    app.settings.update { it.copy(pausedUntil = System.currentTimeMillis() + 30 * 60_000) }
                    stopAll("30분 동안 쉬는 중입니다")
                }
                CANCEL -> stopCurrent("낭독을 중단했습니다")
                TEST, CHANNELS -> runTest(action == CHANNELS)
                START -> {
                    if (!settings.enabled || !prerequisites(this@ReadingService)) {
                        stopAll("필수 권한을 확인하세요"); stopSelf()
                    } else {
                        status("새 알림을 기다리는 중입니다")
                        refreshRoute()
                    }
                }
            }
        }
        return START_STICKY
    }

    fun offer(n: Notice) {
        val reason = if (!settingsLoaded) "설정을 불러오는 중입니다" else
            ReadingPolicy.skipReason(n, settings, System.currentTimeMillis(), LocalTime.now(), isScreenOn(), isInCall(), isDnd())
                ?: routes.current(settings).let { if (it.ready) null else it.label }
        if (reason != null) {
            if (n.isTest) app.status.update { it.copy(lastResult = "테스트 알림 건너뜀: $reason") }
            return
        }
        app.status.update { it.copy(lastResult = if (n.isTest) "테스트 알림 수신 · 대기열에 추가" else "새 알림 수신 · 대기열에 추가") }
        if (queue.offer(n)) pump()
    }

    suspend fun refreshVoiceCatalog() {
        // Never replace an engine while its current utterance is being generated or played.
        if (activeJob == null) engine.prepare()
    }

    fun remove(key: String) {
        if (queue.remove(key, forgetContent = true)) stopCurrent("알림을 지워 낭독을 중단했습니다")
    }

    private fun allowed(n: Notice, requireFresh: Boolean = true) = ReadingPolicy.allowed(n, settings, System.currentTimeMillis(),
        LocalTime.now(), isScreenOn(), isInCall(), isDnd(), requireFresh)
    private fun isScreenOn() = getSystemService(PowerManager::class.java).isInteractive
    private fun isInCall() = routes.audio.mode != AudioManager.MODE_NORMAL
    private fun isDnd() = getSystemService(NotificationManager::class.java).currentInterruptionFilter.let {
        it != NotificationManager.INTERRUPTION_FILTER_ALL && it != NotificationManager.INTERRUPTION_FILTER_UNKNOWN
    }

    private fun pump() {
        if (activeJob != null || permanentStop) return
        var n = queue.next()
        while (n != null && !allowed(n)) { queue.finish(n); n = queue.next() }
        val notice = n ?: return
        val device = routes.current(settings).device
        if (device == null) { queue.clear(); return }
        active = notice
        launchReading(notice, false) {
            engine.speak(ReadingPolicy.speech(notice, settings), settings, device) {
                queue.isCurrent(notice) && allowed(notice, false) && routes.stillSafe(device, settings)
            }
        }
    }

    private fun runTest(channels: Boolean) {
        if (activeJob != null) { status("현재 낭독을 먼저 중단하세요"); return }
        val d = routes.current(settings).device
        if (d == null || isInCall() || isDnd()) { status("이어폰 출력과 통화·방해금지 상태를 확인하세요"); return }
        val valid = { routes.stillSafe(d, settings) && !isInCall() && !isDnd() }
        launchReading(null, true) {
            if (channels) engine.testChannels(settings, d, valid)
            else engine.speak("이어폰 도우미입니다. 알림을 읽는 동안 음악 소리가 작아집니다.", settings, d, valid)
        }
    }

    private fun launchReading(n: Notice?, test: Boolean, task: suspend () -> Unit) {
        testActive = test
        val job = scope.launch(start = CoroutineStart.LAZY) {
            promote(true)
            app.status.update { it.copy(speaking = true, message = if (test) "이어폰 테스트 중" else "알림을 읽는 중") }
            updateNotification()
            try {
                task()
                app.status.update { it.copy(message = "낭독을 마쳤습니다", lastResult = "낭독 완료 · ${it.engineLabel}") }
            }
            catch (_: TimeoutCancellationException) {
                val message = "TTS 또는 출력 확인 시간이 초과되었습니다"
                status(message)
                app.status.update { it.copy(lastResult = message) }
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val reason = if (e is IllegalStateException) e.message.orEmpty().ifBlank { "음성 생성·출력 확인 실패" }
                    else "음성 생성·출력 확인 실패"
                status("낭독 실패: $reason")
                app.status.update { it.copy(lastResult = "낭독 실패: $reason") }
            }
            finally {
                engine.stopAudio()
                app.status.update { it.copy(speaking = false) }
                if (!permanentStop) promote(false)
            }
        }
        activeJob = job
        job.invokeOnCompletion {
            scope.launch {
                if (activeJob === job) {
                    n?.let(queue::finish)
                    activeJob = null; active = null; testActive = false
                    if (test && !settings.enabled) stopSelf() else pump()
                }
            }
        }
        job.start()
    }

    fun stopCurrent(message: String) {
        active?.let { queue.remove(it.key) }
        activeJob?.cancel()
        engine.cancel()
        app.status.update { it.copy(lastResult = message) }
        status(message)
    }

    fun stopAll(message: String) {
        val wasReading = activeJob != null
        queue.clear()
        activeJob?.cancel()
        engine.cancel()
        if (wasReading) app.status.update { it.copy(lastResult = message) }
        status(message)
    }

    private fun refreshRoute() {
        if (!::routes.isInitialized) return
        val route = routes.current(settings)
        app.status.update { it.copy(output = route.label, routeReady = route.ready, unknownDevice = route.unknownId) }
        val id = route.device?.id
        if (lastDevice != null && lastDevice != id) stopAll("이어폰 출력이 변경되어 중단했습니다")
        if (id != null && id != lastDevice && settings.connectionVolume >= 0) {
            val max = routes.audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            runCatching { routes.audio.setStreamVolume(AudioManager.STREAM_MUSIC,
                (max * settings.connectionVolume / 100).coerceIn(0, max), 0) }
        }
        lastDevice = id
    }

    private fun status(message: String) {
        app.status.update { it.copy(message = message) }
        updateNotification()
    }

    private fun action(action: String, code: Int) = PendingIntent.getService(this, code,
        Intent(this, ReadingService::class.java).setAction(action), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val s = app.status.value
        val text = when {
            settings.pausedUntil > System.currentTimeMillis() -> "30분 쉬기 · 알림창에서 설정을 열 수 있습니다"
            s.speaking -> "낭독 중 · 중단하면 음악 소리가 복구됩니다"
            !s.routeReady -> s.output
            else -> "${s.output} · 새 알림을 기다립니다"
        }
        return NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_earbud)
            .setContentTitle("이어폰 알림 읽기").setContentText(text).setContentIntent(open)
            .setOngoing(true).setSilent(true).setOnlyAlertOnce(true)
            .addAction(0, "낭독 중단", action(CANCEL, 1))
            .addAction(0, "30분 쉬기", action(PAUSE, 2))
            .addAction(0, "끄기", action(STOP, 3)).build()
    }

    private fun promote(speaking: Boolean) {
        val type = (if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0) or
            if (speaking) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0
        startForeground(ID, notification(), type)
    }

    private fun updateNotification() {
        if (!::routes.isInitialized || permanentStop) return
        val s = app.status.value
        val signature = "${s.output}|${s.speaking}|${settings.pausedUntil}"
        if (signature != lastNotification) {
            lastNotification = signature
            runCatching { getSystemService(NotificationManager::class.java).notify(ID, notification()) }
        }
    }

    override fun onDestroy() {
        permanentStop = true
        stopAll("알림 읽기가 중지되었습니다")
        scope.cancel(); engine.shutdown()
        routes.audio.unregisterAudioDeviceCallback(deviceCallback)
        routes.audio.removeOnModeChangedListener(modeListener)
        unregisterReceiver(stateReceiver)
        stopForeground(STOP_FOREGROUND_REMOVE)
        app.status.update { it.copy(serviceRunning = false, speaking = false) }
        instance = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
