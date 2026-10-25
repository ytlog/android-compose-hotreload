package dev.compose.hotreload.sample

import java.lang.reflect.InvocationHandler
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

class RepositoryHandler(val target: EditRepository) : InvocationHandler {
    override fun invoke(proxy: Any, method: Method, args: Array<out Any?>?): Any? {
        if (method.declaringClass == Any::class.java) return when (method.name) {
            "equals" -> proxy === args?.get(0)
            "hashCode" -> System.identityHashCode(proxy)
            "toString" -> "RepositoryProxy"
            else -> error(method.name)
        }
        RepositoryLedger.proxyCalls++
        try {
            val result = method.invoke(target, *(args ?: emptyArray()))
            return if (result is String) "handler-v1/$result" else result
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }
}
