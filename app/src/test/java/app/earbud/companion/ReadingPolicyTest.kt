package app.earbud.companion

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalTime

class ReadingPolicyTest {
    private val settings = ReadSettings(enabled = true, apps = setOf("chat"))
    private fun notice(key: String = "a", body: String = "안녕하세요") = Notice(key, "chat", "보낸 사람", body, 1000)
    private fun allowed(n: Notice = notice(), s: ReadSettings = settings, now: Long = 2000,
                        screen: Boolean = false, call: Boolean = false, dnd: Boolean = false) =
        ReadingPolicy.allowed(n, s, now, LocalTime.of(12, 0), screen, call, dnd)

    @Test fun normalMessageAndBodyLimit() {
        assertTrue(allowed())
        assertEquals("보낸 사람", ReadingPolicy.speech(notice(), settings.copy(titleOnly = true)))
        assertEquals(207, ReadingPolicy.speech(notice(body = "가".repeat(250)), settings).length)
    }
    @Test fun skippedMessagesAreNotReadLater() {
        assertFalse(allowed(now = 32000))
        assertFalse(allowed(n = notice().copy(packageName = "other")))
        assertFalse(allowed(n = notice().copy(ongoing = true)))
        assertFalse(allowed(n = notice().copy(groupSummary = true)))
        assertFalse(allowed(s = settings.copy(pausedUntil = 4000)))
        assertFalse(allowed(s = settings.copy(screenOffOnly = true), screen = true))
        assertFalse(allowed(call = true)); assertFalse(allowed(dnd = true))
    }
    @Test fun quietHoursCrossMidnightAndBoundary() {
        val s = settings.copy(quietEnabled = true)
        assertTrue(ReadingPolicy.quietNow(s, LocalTime.of(22, 0)))
        assertTrue(ReadingPolicy.quietNow(s, LocalTime.of(6, 59)))
        assertFalse(ReadingPolicy.quietNow(s, LocalTime.of(7, 0)))
        assertFalse(ReadingPolicy.quietNow(s, LocalTime.of(21, 59)))
        assertTrue(ReadingPolicy.quietNow(s.copy(quietStart = 1, quietEnd = 1), LocalTime.NOON))
    }
    @Test fun duplicateUpdateDoesNotSpeakTwice() {
        val q = NoticeQueue()
        assertTrue(q.offer(notice())); assertFalse(q.offer(notice()))
        assertTrue(q.offer(notice(body = "새 메시지"))); assertEquals(1, q.size)
        assertEquals("새 메시지", q.next()!!.body)
    }
    @Test fun deletingCurrentInvalidatesLateCompletionWithoutDroppingOther() {
        val q = NoticeQueue()
        q.offer(notice()); val current = q.next()!!
        q.offer(notice("b"))
        assertTrue(q.remove("a")); assertFalse(q.isCurrent(current))
        q.finish(current)
        assertEquals("b", q.next()!!.key)
    }
    @Test fun deletingDifferentOrQueuedNoticeDoesNotCancelCurrent() {
        val q = NoticeQueue(); q.offer(notice()); val current = q.next()!!
        q.offer(notice("b")); assertFalse(q.remove("b"))
        assertTrue(q.isCurrent(current)); assertEquals(0, q.size)
    }
    @Test fun clearPreventsLateSynthesisAndOldBacklog() {
        val q = NoticeQueue(); q.offer(notice()); val current = q.next()!!
        q.offer(notice("b")); q.clear()
        assertFalse(q.isCurrent(current)); assertNull(q.next())
    }
    @Test fun slowSpeechDoesNotExpireAfterItHasStarted() {
        assertTrue(ReadingPolicy.allowed(notice(), settings, 50_000, LocalTime.NOON,
            screenOn = false, inCall = false, dnd = false, requireFresh = false))
    }
    @Test fun dismissAllLeavesNoUtteranceToResume() {
        val q = NoticeQueue(); q.offer(notice()); val current = q.next()!!
        q.offer(notice("b")); q.offer(notice("c"))
        assertTrue(q.remove("a")); q.remove("b"); q.remove("c")
        assertFalse(q.isCurrent(current)); assertNull(q.next())
    }
    @Test fun cancelledOldInstanceCannotFinishANewInstanceWithSameKey() {
        val q = NoticeQueue(); q.offer(notice()); val old = q.next()!!
        q.remove("a"); q.finish(old); q.offer(notice(body = "새로 도착"))
        val new = q.next()!!; q.finish(old)
        assertFalse(q.isCurrent(old)); assertTrue(q.isCurrent(new))
    }
    @Test fun queueIsBoundedUnderNotificationFlood() {
        val q = NoticeQueue(); repeat(100) { q.offer(notice(it.toString())) }
        assertEquals(20, q.size); assertEquals("80", q.next()!!.key)
    }

    @Test fun ownTestNotificationUsesAllReadingConditionsExceptAppSelection() {
        val n = notice().copy(packageName = "app.earbud.companion", isTest = true)
        val s = settings.copy(apps = emptySet())
        assertTrue(allowed(n = n, s = s))
        assertFalse(allowed(n = n, s = s.copy(enabled = false)))
        assertFalse(allowed(n = n, s = s, call = true))
        assertFalse(allowed(n = n, s = s, dnd = true))
        assertFalse(allowed(n = n, s = s.copy(screenOffOnly = true), screen = true))
        assertFalse(allowed(n = n, s = s.copy(pausedUntil = 4000)))
    }

    @Test fun skippedTestExplainsWhyAndStillUsesTheConfiguredBodyLength() {
        val n = notice(body = "가".repeat(150)).copy(isTest = true)
        val s = settings.copy(maxChars = 60)
        assertEquals(67, ReadingPolicy.speech(n, s).length)
        assertEquals("방해금지가 켜져 있습니다", ReadingPolicy.skipReason(n, s, 2000, LocalTime.NOON, false, false, true))
    }

    @Test fun genuinelyNewIdenticalNotificationAfterRemovalIsReadAgain() {
        val q = NoticeQueue()
        q.offer(notice())
        val old = q.next()!!
        assertTrue(q.remove("a", forgetContent = true))
        assertTrue(q.offer(notice()))
        val new = q.next()!!
        q.finish(old)
        assertFalse(q.isCurrent(old))
        assertTrue(q.isCurrent(new))
        assertFalse(q.offer(notice())) // Same-content updates are still deduplicated.
    }
}
