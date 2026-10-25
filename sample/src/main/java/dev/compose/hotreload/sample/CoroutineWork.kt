package dev.compose.hotreload.sample

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class CoroutineWork {
    suspend fun named(ledger: CoroutineLedger, id: String): String {
        val captured = "before-v1"
        val reply = ledger.await(id)
        // NEXT_SUSPENSION
        ledger.event("resume:$id")
        return "$captured/$reply/after-v1"
    }
    fun lambda(scope: CoroutineScope, ledger: CoroutineLedger, id: String, done: (String) -> Unit): Job = scope.launch {
        val captured = "lambda-before-v1"
        val reply = ledger.await(id)
        done("$captured/$reply/lambda-after-v1")
    }
    suspend fun nested(ledger: CoroutineLedger, id: String): String {
        val value = child(ledger, id)
        return "outer-v1/$value"
    }
    private suspend fun child(ledger: CoroutineLedger, id: String): String {
        val value = ledger.await(id)
        return "child-v1/$value"
    }
    suspend fun guarded(ledger: CoroutineLedger, id: String): String {
        try {
            val value = ledger.await(id)
            return "guard-v1/$value"
        } finally {
            ledger.event("finally-v1:$id")
        }
    }
}
