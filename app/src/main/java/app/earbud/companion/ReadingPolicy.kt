package app.earbud.companion

import java.security.MessageDigest
import java.time.LocalTime

data class ReadSettings(
    val enabled: Boolean = false,
    val apps: Set<String> = emptySet(),
    val titleOnly: Boolean = false,
    val screenOffOnly: Boolean = false,
    val quietEnabled: Boolean = false,
    val quietStart: Int = 22 * 60,
    val quietEnd: Int = 7 * 60,
    val maxChars: Int = 200,
    val rate: Float = 1f,
    val speechVolume: Float = 0.8f,
    val voice: String = "",
    val pausedUntil: Long = 0,
    val connectionVolume: Int = -1,
    val approvedDevices: Set<String> = emptySet(),
    val bannerDiagnostics: Boolean = false,
)

data class Notice(
    val key: String,
    val packageName: String,
    val title: String,
    val body: String,
    val postedAt: Long,
    val ongoing: Boolean = false,
    val groupSummary: Boolean = false,
    val isTest: Boolean = false,
)

object ReadingPolicy {
    fun quietNow(settings: ReadSettings, time: LocalTime): Boolean {
        if (!settings.quietEnabled) return false
        val minute = time.hour * 60 + time.minute
        val start = settings.quietStart
        val end = settings.quietEnd
        return when {
            start == end -> true
            start < end -> minute in start until end
            else -> minute >= start || minute < end
        }
    }

    fun allowed(n: Notice, s: ReadSettings, now: Long, time: LocalTime,
                screenOn: Boolean, inCall: Boolean, dnd: Boolean, requireFresh: Boolean = true): Boolean =
        skipReason(n, s, now, time, screenOn, inCall, dnd, requireFresh) == null

    fun skipReason(n: Notice, s: ReadSettings, now: Long, time: LocalTime,
                   screenOn: Boolean, inCall: Boolean, dnd: Boolean, requireFresh: Boolean = true): String? = when {
        !s.enabled -> "알림 읽기가 꺼져 있습니다"
        now < s.pausedUntil -> "30분 쉬기 중입니다"
        !n.isTest && n.packageName !in s.apps -> "읽어줄 앱으로 선택하지 않았습니다"
        n.ongoing -> "지속 알림은 읽지 않습니다"
        n.groupSummary -> "그룹 요약은 읽지 않습니다"
        s.screenOffOnly && screenOn -> "화면이 꺼졌을 때만 읽도록 설정되어 있습니다"
        inCall -> "통화 또는 다른 음성 작업 중입니다"
        dnd -> "방해금지가 켜져 있습니다"
        quietNow(s, time) -> "조용한 시간입니다"
        requireFresh && now - n.postedAt !in 0..30_000 -> "오래된 알림은 읽지 않습니다"
        speech(n, s).isBlank() -> "읽을 미리보기 내용이 없습니다"
        else -> null
    }

    fun speech(n: Notice, s: ReadSettings): String {
        val title = n.title.trim()
        val body = if (s.titleOnly) "" else n.body.trim().take(s.maxChars.coerceIn(20, 1000))
        return listOf(title, body).filter { it.isNotBlank() }.distinct().joinToString(". ")
    }
}

/** Main-thread confined. Contains no Android objects; tombstones also protect late synthesis. */
class NoticeQueue {
    private val pending = LinkedHashMap<String, Notice>()
    private val seen = LinkedHashMap<String, String>()
    private val invalid = LinkedHashSet<String>()
    private var current: Notice? = null

    fun offer(n: Notice): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((n.title + "\u0000" + n.body).toByteArray()).joinToString("") { "%02x".format(it) }
        if (seen[n.key] == digest) return false
        seen[n.key] = digest
        while (seen.size > 256) seen.remove(seen.keys.first())
        invalid.remove(n.key)
        pending[n.key] = n
        while (pending.size > 20) pending.remove(pending.keys.first())
        return true
    }

    fun next(): Notice? {
        val n = pending.values.firstOrNull() ?: return null
        pending.remove(n.key)
        current = n
        return n
    }

    fun remove(key: String, forgetContent: Boolean = false): Boolean {
        pending.remove(key)
        // Notification IDs are reused by messaging apps. A genuinely removed notification
        // must not suppress the next new notification just because its text is identical.
        if (forgetContent) seen.remove(key)
        invalid.add(key)
        while (invalid.size > 256) invalid.remove(invalid.first())
        return current?.key == key
    }

    fun isCurrent(n: Notice): Boolean = current === n && n.key !in invalid
    fun finish(n: Notice) { if (current === n) current = null }
    fun clear() { pending.clear(); current = null }
    val size: Int get() = pending.size
}
