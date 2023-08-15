// TARGET_BACKEND: JVM
// WITH_REFLECT
// WITH_COROUTINES

import helpers.EmptyContinuation
import kotlin.coroutines.Continuation
import kotlin.coroutines.startCoroutine
import kotlin.coroutines.suspendCoroutine
import kotlin.reflect.KFunction
import kotlin.reflect.full.callSuspendBy

var c: Continuation<Z>? = null

suspend fun suspendMe(): Z =
    suspendCoroutine { c = it }

fun builder(c: suspend () -> Unit) {
    c.startCoroutine(EmptyContinuation())
}

fun box(): String {
    builder {
        val ref: KFunction<*> = ::suspendMe
        ref.callSuspendBy(emptyMap())
        c!!.resumeWith(Result.success(Z("OK")))
    }
    return "OK"
}

@JvmInline
value class Z(val value: String) {
    override fun toString(): String = value
}
