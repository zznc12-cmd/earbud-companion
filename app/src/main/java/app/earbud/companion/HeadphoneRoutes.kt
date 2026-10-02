package app.earbud.companion

import android.Manifest
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.core.content.ContextCompat
import java.security.MessageDigest

data class RouteState(val device: AudioDeviceInfo? = null, val label: String,
                      val unknownId: String? = null) {
    val ready: Boolean get() = device != null
}

class HeadphoneRoutes(private val context: Context) {
    val audio = context.getSystemService(AudioManager::class.java)
    val attributes: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()

    fun identity(d: AudioDeviceInfo): String = MessageDigest.getInstance("SHA-256")
        .digest((d.address + "|" + d.productName).toByteArray()).joinToString("") { "%02x".format(it) }

    private enum class Kind { HEADPHONE, OTHER, UNKNOWN }
    private val kinds = mutableMapOf<String, Kind>()
    private fun kind(d: AudioDeviceInfo): Kind {
        if (d.type == AudioDeviceInfo.TYPE_BLE_HEADSET) return Kind.HEADPHONE
        if (d.type != AudioDeviceInfo.TYPE_BLUETOOTH_A2DP) return Kind.OTHER
        val identity = "${d.id}|${d.address}|${d.productName}"
        kinds[identity]?.let { return it }
        val result = try {
            val bonded = context.getSystemService(BluetoothManager::class.java).adapter?.bondedDevices
            val bt = bonded?.firstOrNull { it.address.equals(d.address, ignoreCase = true) }
                ?: bonded?.filter { it.name == d.productName.toString() }?.singleOrNull()
            when (bt?.bluetoothClass?.deviceClass) {
                BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
                BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET -> Kind.HEADPHONE
                BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO,
                BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
                BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO -> Kind.OTHER
                else -> Kind.UNKNOWN
            }
        } catch (_: SecurityException) { Kind.UNKNOWN }
        kinds[identity] = result
        return result
    }

    fun current(settings: ReadSettings): RouteState {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)
            return RouteState(label = "블루투스 권한이 필요합니다")
        return try {
            val anticipated = audio.getAudioDevicesForAttributes(attributes)
            if (anticipated.size != 1) return RouteState(label = "출력 경로를 하나의 이어폰으로 선택하세요")
            val device = anticipated.single()
            when (kind(device)) {
                Kind.HEADPHONE -> RouteState(device, device.productName.toString())
                Kind.OTHER -> RouteState(label = "${device.productName} · 이어폰 출력 아님")
                Kind.UNKNOWN -> {
                    val id = identity(device)
                    if (id in settings.approvedDevices) RouteState(device, device.productName.toString())
                    else RouteState(label = "${device.productName} · 이어폰 확인 필요", unknownId = id)
                }
            }
        } catch (_: Exception) { RouteState(label = "출력 경로 확인 실패 · 낭독 보류") }
    }

    fun stillSafe(expected: AudioDeviceInfo, settings: ReadSettings): Boolean =
        current(settings).device?.let { it.id == expected.id && identity(it) == identity(expected) } == true
}
