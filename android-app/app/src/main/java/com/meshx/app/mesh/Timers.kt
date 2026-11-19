package com.meshx.app.mesh

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The only notion of time the mesh code uses. Everything in the mesh package runs on one
 * thread and is driven by timers and events, the same way firmware runs its main loop.
 * Tests replace this with a fake clock, so they never have to wait.
 */
interface Timers {
    /** Milliseconds from an arbitrary start, never goes backwards. */
    fun now(): Long

    /** Runs the action once, after the delay. */
    fun after(delayMs: Long, action: () -> Unit): TimerHandle
}

fun interface TimerHandle {
    fun cancel()
}

/** Timers for the real app. The scope must run on the main thread. */
class CoroutineTimers(private val scope: CoroutineScope) : Timers {
    override fun now(): Long = System.nanoTime() / 1_000_000

    override fun after(delayMs: Long, action: () -> Unit): TimerHandle {
        val job = scope.launch {
            delay(delayMs)
            action()
        }
        return TimerHandle { job.cancel() }
    }
}
