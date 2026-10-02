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
        s.enabled && now >= s.pausedUntil && n.packageName in s.apps &&
            !n.ongoing && !n.groupSummary && (!s.screenOffOnly || !screenOn) &&
            !inCall && !dnd && !quietNow(s, time) &&
            (!requireFresh || now - n.postedAt in 0..30_000) && speech(n, s).isNotBlank()

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

    fun remove(key: String): Boolean {
        pending.remove(key)
        invalid.add(key)
        while (invalid.size > 256) invalid.remove(invalid.first())
        return current?.key == key
    }

    fun isCurrent(n: Notice): Boolean = current === n && n.key !in invalid
    fun finish(n: Notice) { if (current === n) current = null }
    fun clear() { pending.clear(); current = null }
    val size: Int get() = pending.size
}
