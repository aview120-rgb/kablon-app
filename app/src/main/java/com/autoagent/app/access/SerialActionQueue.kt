package com.autoagent.app.access

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs screen actions strictly one at a time, so two automations never fight over the screen.
 * Higher [Priority] first, FIFO within a priority. Each action has a timeout and a cool-down gap after it,
 * and an action can be postponed while the screen is "busy" (e.g. a phone call, or the user is typing).
 * Pure Kotlin (no Android), covered by unit tests.
 */
class SerialActionQueue(
    private val scope: CoroutineScope,
    private val gapMs: Long = 1_200,
    /** Returns a reason when actions must wait (checked before each action), or null when the screen is free. */
    private val busyReason: () -> String? = { null },
    private val busyRetryMs: Long = 2_000,
    private val maxBusyWaitMs: Long = 120_000,
) {
    enum class Priority { LOW, NORMAL, HIGH }

    data class Snapshot(val running: String? = null, val waiting: List<String> = emptyList(), val waitReason: String? = null, val completed: Long = 0)

    sealed interface Result<out T> {
        data class Done<T>(val value: T) : Result<T>
        data class TimedOut(val label: String) : Result<Nothing>
        data class Busy(val reason: String) : Result<Nothing>
        data class Failed(val error: Throwable) : Result<Nothing>
    }

    private class Entry(
        val seq: Long,
        val label: String,
        val priority: Priority,
        val timeoutMs: Long,
        val block: suspend () -> Any?,
        val done: CompletableDeferred<Result<Any?>>,
    )

    private val seq = AtomicLong()
    private val lock = Mutex()
    private val pending = PriorityQueue<Entry>(compareByDescending<Entry> { it.priority.ordinal }.thenBy { it.seq })
    private var worker: Job? = null
    private var completed = 0L
    private val _state = MutableStateFlow(Snapshot())
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    /** Queues [block] and suspends until it ran (or timed out / failed). Never runs two blocks at once. */
    @Suppress("UNCHECKED_CAST")
    suspend fun <T> run(label: String, priority: Priority = Priority.NORMAL, timeoutMs: Long = 60_000, block: suspend () -> T): Result<T> {
        val entry = Entry(seq.incrementAndGet(), label, priority, timeoutMs, block, CompletableDeferred())
        lock.withLock {
            pending.add(entry)
            publish(running = _state.value.running)
            if (worker?.isActive != true) worker = scope.launch { drain() }
        }
        return entry.done.await() as Result<T>
    }

    private suspend fun drain() {
        while (true) {
            val next = lock.withLock {
                val e = pending.poll()
                if (e == null) worker = null
                publish(running = e?.label)
                e
            } ?: return
            next.done.complete(execute(next))
            lock.withLock {
                completed++
                publish(running = null)
            }
            delay(gapMs)
        }
    }

    private suspend fun execute(e: Entry): Result<Any?> {
        var waited = 0L
        while (true) {
            val reason = busyReason() ?: break
            if (waited >= maxBusyWaitMs) return Result.Busy(reason)
            _state.value = _state.value.copy(waitReason = reason)
            delay(busyRetryMs)
            waited += busyRetryMs
        }
        _state.value = _state.value.copy(waitReason = null)
        return try {
            val v = withTimeoutOrNull(e.timeoutMs) { Box(e.block()) }
            if (v == null) Result.TimedOut(e.label) else Result.Done(v.value)
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            Result.Failed(t)
        }
    }

    private class Box(val value: Any?)

    private fun publish(running: String?) {
        _state.value = Snapshot(
            running = running,
            waiting = pending.sortedWith(compareByDescending<Entry> { it.priority.ordinal }.thenBy { it.seq }).map { it.label },
            waitReason = _state.value.waitReason,
            completed = completed,
        )
    }
}
