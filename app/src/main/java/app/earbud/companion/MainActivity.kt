package app.earbud.companion

import android.Manifest
import android.app.TimePickerDialog
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.provider.Telephony
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.update
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { EarbudScreen(application as CompanionApp) }
    }
}

private data class SelectableApp(val packageName: String, val label: String, val recommended: Boolean)
private data class Permissions(val bluetooth: Boolean = false, val notifications: Boolean = false,
                               val listener: Boolean = false, val battery: Boolean = false)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EarbudScreen(app: CompanionApp) {
    val context = LocalContext.current
    val settings by app.settings.state.collectAsStateWithLifecycle()
    val status by app.status.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var tab by remember { mutableIntStateOf(0) }
    var permissions by remember { mutableStateOf(Permissions()) }
    var apps by remember { mutableStateOf<List<SelectableApp>>(emptyList()) }
    var query by remember { mutableStateOf("") }
    var refresh by remember { mutableIntStateOf(0) }
    var uiMessage by remember { mutableStateOf("") }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    fun open(intent: Intent) {
        runCatching { context.startActivity(intent) }.onFailure { uiMessage = "이 설정 화면을 열 수 없습니다. 휴대폰 설정에서 확인하세요." }
    }
    fun change(update: (ReadSettings) -> ReadSettings) { scope.launch { app.settings.update(update) } }
    fun toggle(enabled: Boolean) {
        if (enabled && !ReadingService.prerequisites(context)) {
            uiMessage = "아래 권한 안내에서 알림 접근과 블루투스·앱 알림 권한을 켜세요."
            return
        }
        scope.launch {
            app.settings.update { it.copy(enabled = enabled, pausedUntil = 0) }
            runCatching { ReadingService.command(context, if (enabled) ReadingService.START else ReadingService.STOP) }
                .onFailure { uiMessage = "시작하지 못했습니다. 배터리 제한과 권한을 확인하세요." }
        }
    }
    LaunchedEffect(refresh) {
        while (isActive) {
            permissions = Permissions(
                bluetooth = ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED,
                notifications = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED &&
                    NotificationManagerCompat.from(context).areNotificationsEnabled(),
                listener = NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName),
                battery = context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName),
            )
            if (!app.status.value.serviceRunning) {
                val route = HeadphoneRoutes(context).current(app.settings.state.value)
                app.status.update { it.copy(output = route.label, routeReady = route.ready, unknownDevice = route.unknownId) }
            }
            delay(1000)
        }
    }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val sms = Telephony.Sms.getDefaultSmsPackage(context)
            context.packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
                .map { info -> SelectableApp(info.activityInfo.packageName, info.loadLabel(context.packageManager).toString(),
                    info.activityInfo.packageName in setOf("com.kakao.talk", sms)) }
                .filter { it.packageName != context.packageName }.distinctBy { it.packageName }
                .sortedWith(compareByDescending<SelectableApp> { it.recommended }.thenBy { it.label })
        }
        val initialized = CompletableDeferred<Boolean>()
        val probe = TextToSpeech(context) { initialized.complete(it == TextToSpeech.SUCCESS) }
        try {
            if (withTimeoutOrNull(15_000) { initialized.await() } == true) {
                val voices = probe.voices.orEmpty().filter { it.locale.language == "ko" && !it.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in it.features.orEmpty() }
                    .sortedWith(compareByDescending<android.speech.tts.Voice> { it.quality }.thenBy { it.name })
                app.status.update { it.copy(offlineVoiceReady = voices.isNotEmpty(), voices = voices.map { v -> v.name to v.locale.displayName }) }
            }
        } finally { probe.shutdown() }
    }

    val colors = lightColorScheme(primary = Color(0xFF235B50), secondary = Color(0xFF596C62),
        background = Color(0xFFF5F7F3), surface = Color.White, primaryContainer = Color(0xFFD7EEE2))
    MaterialTheme(colorScheme = colors) {
        Scaffold(containerColor = colors.background) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                Column(Modifier.padding(horizontal = 24.dp, vertical = 20.dp)) {
                    Text("이어폰 도우미", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Text("알림은 이어폰으로, 음악은 자연스럽게", style = MaterialTheme.typography.bodyMedium, color = colors.secondary)
                }
                PrimaryTabRow(selectedTabIndex = tab) {
                    listOf("홈", "알림", "소리", "검증").forEachIndexed { i, text ->
                        Tab(selected = tab == i, onClick = { tab = i }, text = { Text(text) })
                    }
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    if (uiMessage.isNotBlank()) item {
                        InfoCard("안내", uiMessage) { TextButton(onClick = { uiMessage = "" }) { Text("확인") } }
                    }
                    when (tab) {
                        0 -> {
                            item {
                                Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF235B50)), shape = RoundedCornerShape(24.dp)) {
                                    Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                        Text(when { status.speaking -> "알림을 읽고 있어요"; status.serviceRunning && status.routeReady -> "이어폰으로 들을 준비 완료"; else -> "이어폰에서만 읽어드려요" },
                                            color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                                        Text(status.output, color = Color(0xFFCEE4D8))
                                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                            Text("알림 읽기", color = Color.White, modifier = Modifier.padding(top = 12.dp))
                                            Switch(checked = settings.enabled, onCheckedChange = ::toggle)
                                        }
                                        Text(status.message, color = Color(0xFFCEE4D8), style = MaterialTheme.typography.bodySmall)
                                    }
                                }
                            }
                            status.unknownDevice?.let { device -> item {
                                InfoCard("이어폰인지 확인해 주세요", "이 장치의 종류를 확인할 수 없습니다. 실제 무선이어폰인 경우에만 승인하세요.") {
                                    Button(onClick = { change { it.copy(approvedDevices = it.approvedDevices + device) } }) { Text("이 장치를 이어폰으로 사용") }
                                }
                            } }
                            if (settings.pausedUntil > System.currentTimeMillis()) item {
                                InfoCard("잠시 쉬는 중", "쉬는 동안 도착한 알림은 나중에 읽지 않습니다.") {
                                    Button(onClick = { change { it.copy(pausedUntil = 0) } }) { Text("지금 다시 시작") }
                                }
                            }
                            item {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { ReadingService.command(context, ReadingService.CANCEL) }, enabled = status.speaking) { Text("낭독 중단") }
                                    OutlinedButton(onClick = { ReadingService.command(context, ReadingService.PAUSE) }, enabled = status.serviceRunning) { Text("30분 쉬기") }
                                }
                            }
                            item { InfoCard("처음 사용하기", "알림 접근과 기기 권한을 켜고, 알림 탭에서 읽어줄 앱을 선택하세요.") {
                                PermissionRow("블루투스·앱 알림", permissions.bluetooth && permissions.notifications) {
                                    permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.POST_NOTIFICATIONS))
                                }
                                PermissionRow("알림 접근", permissions.listener) {
                                    open(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                                        ComponentName(context, NotificationReader::class.java).flattenToString()))
                                }
                                PermissionRow("오프라인 한국어 음성", status.offlineVoiceReady) { open(Intent("com.android.settings.TTS_SETTINGS")) }
                                PermissionRow("배터리 제한 없음", permissions.battery) {
                                    open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:${context.packageName}".toUri()))
                                }
                            } }
                            item { InfoCard("선택한 앱 ${settings.apps.size}개", "현재 알림을 실제로 삭제하면 낭독도 중단됩니다. 배너만 위로 숨기는 동작은 아직 검증 전입니다.") {
                                Button(onClick = { tab = 1 }) { Text("읽어줄 앱 선택") }
                                OutlinedButton(onClick = { runCatching { ReadingService.command(context, ReadingService.TEST) }.onFailure { uiMessage = "블루투스 권한과 출력 장치를 확인하세요." } },
                                    enabled = permissions.bluetooth && permissions.notifications && status.routeReady) { Text("낭독 미리듣기") }
                            } }
                            item { Text("빠른 설정 편집에서 ‘알림 읽기’ 타일을 추가할 수 있습니다.", style = MaterialTheme.typography.bodySmall) }
                        }
                        1 -> {
                            item { InfoCard("읽는 방식", "선택한 앱의 새 알림만 읽습니다.") {
                                ToggleRow("발신자만 읽기", settings.titleOnly) { v -> change { it.copy(titleOnly = v) } }
                                ToggleRow("화면이 꺼졌을 때만", settings.screenOffOnly) { v -> change { it.copy(screenOffOnly = v) } }
                                ToggleRow("조용한 시간", settings.quietEnabled) { v -> change { it.copy(quietEnabled = v) } }
                                if (settings.quietEnabled) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    OutlinedButton(onClick = { TimePickerDialog(context, { _, h, m -> change { it.copy(quietStart = h * 60 + m) } }, settings.quietStart / 60, settings.quietStart % 60, true).show() }) { Text("시작 ${clock(settings.quietStart)}") }
                                    OutlinedButton(onClick = { TimePickerDialog(context, { _, h, m -> change { it.copy(quietEnd = h * 60 + m) } }, settings.quietEnd / 60, settings.quietEnd % 60, true).show() }) { Text("종료 ${clock(settings.quietEnd)}") }
                                }
                                SettingSlider("본문 길이", settings.maxChars.toFloat(), 20f..1000f, "${settings.maxChars}자") { v -> change { it.copy(maxChars = v.roundToInt()) } }
                            } }
                            item { OutlinedTextField(query, { query = it }, modifier = Modifier.fillMaxWidth(), label = { Text("앱 검색") }, singleLine = true) }
                            items(apps.filter { query.isBlank() || it.label.contains(query, true) }, key = { it.packageName }) { entry ->
                                Card { Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Column(Modifier.weight(1f).padding(top = 8.dp)) {
                                        Text(entry.label, fontWeight = FontWeight.Medium)
                                        if (entry.recommended) Text("추천", color = colors.primary, style = MaterialTheme.typography.labelSmall)
                                    }
                                    Checkbox(entry.packageName in settings.apps, onCheckedChange = { selected -> change { s -> s.copy(apps = if (selected) s.apps + entry.packageName else s.apps - entry.packageName) } })
                                } }
                            }
                        }
                        2 -> {
                            item { InfoCard("낭독 소리", "낭독 중 음악·영상의 음량을 낮추도록 요청합니다. 다른 앱에 따라 잠시 멈출 수도 있습니다.") {
                                SettingSlider("읽기 속도", settings.rate, .5f..2f, "%.1f배".format(settings.rate)) { v -> change { it.copy(rate = v) } }
                                SettingSlider("낭독 음량", settings.speechVolume, 0f..1f, "${(settings.speechVolume * 100).roundToInt()}%") { v -> change { it.copy(speechVolume = v) } }
                                var voiceMenu by remember { mutableStateOf(false) }
                                Box {
                                    OutlinedButton(onClick = { voiceMenu = true }) { Text(if (settings.voice.isBlank()) "목소리: 기본 한국어" else "목소리: 선택한 한국어 음성") }
                                    DropdownMenu(voiceMenu, onDismissRequest = { voiceMenu = false }) {
                                        DropdownMenuItem(text = { Text("기본 한국어") }, onClick = { change { it.copy(voice = "") }; voiceMenu = false })
                                        status.voices.forEachIndexed { i, voice -> DropdownMenuItem(text = { Text("한국어 음성 ${i + 1}") }, onClick = { change { it.copy(voice = voice.first) }; voiceMenu = false }) }
                                    }
                                }
                                if (!status.offlineVoiceReady) Text("오프라인 한국어 음성 설치가 필요합니다.", color = colors.error)
                            } }
                            item { InfoCard("연결할 때 미디어 음량", "기본은 기존 음량 유지입니다. 이 옵션을 켜면 이어폰 연결 때 한 번만 지정 음량을 적용합니다.") {
                                ToggleRow("연결 시 음량 적용", settings.connectionVolume >= 0) { v -> change { it.copy(connectionVolume = if (v) 40 else -1) } }
                                if (settings.connectionVolume >= 0) SettingSlider("연결 음량", settings.connectionVolume.toFloat(), 0f..100f, "${settings.connectionVolume}%") { v -> change { it.copy(connectionVolume = v.roundToInt()) } }
                            } }
                            item { InfoCard("소리 확인", "좌우 테스트는 왼쪽, 오른쪽 순서로 낮은 테스트음을 냅니다.") {
                                OutlinedButton(onClick = { runCatching { ReadingService.command(context, ReadingService.CHANNELS) }.onFailure { uiMessage = "기기 권한을 확인하세요." } },
                                    enabled = permissions.bluetooth && permissions.notifications && status.routeReady) { Text("좌우 소리 테스트") }
                                OutlinedButton(onClick = {
                                    val eq = Intent(AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL)
                                        .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, context.packageName)
                                        .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
                                    if (eq.resolveActivity(context.packageManager) != null) open(eq)
                                    else open(Intent(Settings.ACTION_SOUND_SETTINGS))
                                }) { Text("휴대폰 음질 설정") }
                                TextButton(onClick = { change { it.copy(approvedDevices = emptySet()) } }) { Text("이어폰 승인 목록 초기화") }
                            } }
                        }
                        3 -> {
                            item { InfoCard("실기기 검증 전 · 미리보기 버전", "기능을 구현했지만 S26·QCY에서 실제 출력과 덕킹, 장시간 대기는 아직 확인하지 못했습니다. 스피커 출력 전환도 민감하지 않은 테스트 알림으로 먼저 확인하세요.") }
                            item { InfoCard("알림 치우기", "알림 자체 삭제에 따른 중단은 구현되어 있습니다. 배너만 숨기는 동작은 수동 숨김과 자동 사라짐을 구분할 근거가 없어 미완료입니다.") }
                            item { InfoCard("배너 숨김 검증 도우미", "선택 기능입니다. 시스템 알림 화면의 이벤트 종류와 횟수만 관찰합니다. 본문을 저장하지 않고, 터치 동작을 바꾸지 않습니다. 배너 숨김 중단 기능은 활성화되지 않습니다.") {
                                ToggleRow("화면 변화 진단", settings.bannerDiagnostics) { v -> change { it.copy(bannerDiagnostics = v) } }
                                if (settings.bannerDiagnostics) {
                                    OutlinedButton(onClick = { open(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("접근성 설정 열기") }
                                    Text("서비스: ${if (status.bannerConnected) "연결됨" else "꺼짐"} · 이벤트 ${status.bannerEventCount}개")
                                }
                            } }
                            item { InfoCard("테스트 순서", "① 이어폰으로 유튜브 재생\n② 테스트 메시지 받기\n③ 옆으로 삭제 후 낭독·영상 음량 확인\n④ 배너 자동 사라짐에서는 낭독 유지 확인\n⑤ 낭독 중 이어폰 해제·출력 전환 확인\n⑥ 화면 꺼짐·재부팅 후 다시 확인") }
                            item { Text("버전 ${BuildConfig.VERSION_NAME} · 서버 전송 없음", style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                }
            }
        }
    }
}

private fun clock(minute: Int) = "%02d:%02d".format(minute / 60, minute % 60)

@Composable
private fun InfoCard(title: String, body: String, content: @Composable ColumnScope.() -> Unit = {}) {
    Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = Color.White)) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodyMedium, color = Color(0xFF52645B))
            content()
        }
    }
}

@Composable
private fun ToggleRow(label: String, value: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f).padding(top = 12.dp))
        Switch(value, onCheckedChange = change)
    }
}

@Composable
private fun PermissionRow(label: String, ready: Boolean, open: () -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.weight(1f).padding(top = 10.dp))
        TextButton(onClick = open) { Text(if (ready) "확인됨" else "설정하기") }
    }
}

@Composable
private fun SettingSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, display: String, change: (Float) -> Unit) {
    var local by remember(value) { mutableFloatStateOf(value) }
    Text("$label · $display", style = MaterialTheme.typography.labelLarge)
    Slider(local, onValueChange = { local = it }, valueRange = range, onValueChangeFinished = { change(local) })
}
