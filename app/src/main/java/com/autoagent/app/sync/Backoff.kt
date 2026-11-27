package com.autoagent.app.sync

import kotlin.random.Random

/** Exponential backoff with jitter for reconnects: 1 s, 2 s, 4 s … capped at [maxMs]. */
class Backoff(
    private val baseMs: Long = 1_000,
    private val maxMs: Long = 60_000,
    private val factor: Double = 2.0,
    private val jitter: Double = 0.2,
    private val random: Random = Random.Default,
) {
    var attempt: Int = 0
        private set

    fun next(): Long {
        val raw = baseMs * Math.pow(factor, attempt.toDouble())
        attempt++
        val capped = raw.coerceAtMost(maxMs.toDouble())
        val spread = capped * jitter
        return (capped - spread + random.nextDouble() * 2 * spread).toLong().coerceIn(baseMs / 2, maxMs)
    }

    fun reset() {
        attempt = 0
    }
}
