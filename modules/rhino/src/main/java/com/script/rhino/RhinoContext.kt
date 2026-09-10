package com.script.rhino

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import java.util.concurrent.TimeoutException
import kotlin.coroutines.CoroutineContext

class RhinoContext(factory: ContextFactory) : Context(factory) {

    var coroutineContext: CoroutineContext? = null
    var allowScriptRun = false
    var recursiveCount = 0
    private var deadlineMillis = Long.MAX_VALUE

    fun beginScriptRun(timeoutMillis: Long = DEFAULT_SCRIPT_TIMEOUT_MILLIS) {
        if (recursiveCount == 0) {
            deadlineMillis = System.currentTimeMillis() + timeoutMillis
        }
    }

    fun endScriptRun() {
        if (recursiveCount == 0) {
            deadlineMillis = Long.MAX_VALUE
        }
    }

    @Throws(RhinoInterruptError::class)
    fun ensureActive() {
        try {
            coroutineContext?.ensureActive()
            if (System.currentTimeMillis() > deadlineMillis) {
                throw TimeoutException("JavaScript执行超过${DEFAULT_SCRIPT_TIMEOUT_MILLIS / 1000}秒，已停止")
            }
        } catch (e: CancellationException) {
            throw RhinoInterruptError(e)
        } catch (e: TimeoutException) {
            throw RhinoInterruptError(e)
        }
    }

    @Throws(RhinoRecursionError::class)
    fun checkRecursive() {
        if (recursiveCount >= 10) {
            throw RhinoRecursionError()
        }
    }

    companion object {
        const val DEFAULT_SCRIPT_TIMEOUT_MILLIS = 30_000L
    }

}
