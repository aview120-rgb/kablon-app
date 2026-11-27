package com.autoagent.app.access

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

class SerialActionQueueTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun neverRunsTwoActionsAtOnce() = runBlocking {
        val q = SerialActionQueue(scope, gapMs = 0)
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val results = (1..8).map { i ->
            async(Dispatchers.Default) {
                q.run("a$i") {
                    maxActive.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                    delay(20)
                    active.decrementAndGet()
                    i
                }
            }
        }.awaitAll()
        assertEquals(1, maxActive.get())
        assertEquals((1..8).map { SerialActionQueue.Result.Done(it) }, results)
        assertEquals(8L, q.state.value.completed)
    }

    @Test
    fun higherPriorityGoesFirstThenFifo() = runBlocking {
        val q = SerialActionQueue(scope, gapMs = 0)
        val order = Collections.synchronizedList(mutableListOf<String>())
        val blocker = async { q.run("blocker") { delay(150); order += "blocker" } }
        delay(30)
        val jobs = listOf(
            async { q.run("low", SerialActionQueue.Priority.LOW) { order += "low" } },
            async { q.run("n1") { order += "n1" } },
            async { q.run("high", SerialActionQueue.Priority.HIGH) { order += "high" } },
            async { q.run("n2") { order += "n2" } },
        )
        delay(30)
        assertEquals("blocker", q.state.value.running)
        assertEquals(listOf("high", "n1", "n2", "low"), q.state.value.waiting)
        (jobs + blocker).awaitAll()
        assertEquals(listOf("blocker", "high", "n1", "n2", "low"), order)
    }

    @Test
    fun timeoutAndFailureDoNotBlockTheQueue() = runBlocking {
        val q = SerialActionQueue(scope, gapMs = 0)
        val slow = q.run("slow", timeoutMs = 50) { delay(5_000) }
        assertTrue(slow is SerialActionQueue.Result.TimedOut)
        val failed = q.run<Int>("boom") { error("שגיאה") }
        assertTrue(failed is SerialActionQueue.Result.Failed)
        assertEquals(SerialActionQueue.Result.Done(42), q.run("ok") { 42 })
    }

    @Test
    fun waitsWhileBusyThenGivesUp() = runBlocking {
        var busy: String? = "שיחה פעילה"
        val q = SerialActionQueue(scope, gapMs = 0, busyReason = { busy }, busyRetryMs = 10, maxBusyWaitMs = 1_000)
        val pending = async { q.run("send") { "sent" } }
        delay(50)
        assertEquals("שיחה פעילה", q.state.value.waitReason)
        busy = null
        assertEquals(SerialActionQueue.Result.Done("sent"), pending.await())

        busy = "עדיין תפוס"
        val q2 = SerialActionQueue(scope, gapMs = 0, busyReason = { busy }, busyRetryMs = 10, maxBusyWaitMs = 30)
        assertEquals(SerialActionQueue.Result.Busy("עדיין תפוס"), q2.run("x") { 1 })
    }
}
