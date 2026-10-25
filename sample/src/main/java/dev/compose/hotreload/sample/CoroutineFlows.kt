package dev.compose.hotreload.sample

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map

class CoroutineFlows {
    fun cold(ledger: CoroutineLedger, id: String): Flow<String> {
        val captured = "factory-v1"
        return flow {
            ledger.event("cold-start:$id")
            val reply = ledger.await(id)
            emit("$captured/$reply/cold-v1")
        }
    }
    suspend fun collect(source: Flow<String>, ledger: CoroutineLedger, id: String, emit: (String) -> Unit) {
        ledger.event("subscribe:$id")
        try {
            source.map { "map-v1/$it" }.collect { emit("collect-v1/$it") }
        } finally {
            ledger.event("unsubscribe:$id")
        }
    }
    suspend fun gated(source: Flow<String>, ledger: CoroutineLedger, id: String, emit: (String) -> Unit) {
        ledger.event("subscribe:$id")
        try {
            source.map {
                val reply = ledger.await("$id-$it")
                "gated-v1/$it/$reply"
            }.collect { emit(it) }
        } finally {
            ledger.event("unsubscribe:$id")
        }
    }
}
