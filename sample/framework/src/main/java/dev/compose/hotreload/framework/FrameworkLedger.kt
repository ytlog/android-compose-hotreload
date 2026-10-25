package dev.compose.hotreload.framework
import androidx.compose.runtime.mutableStateListOf
import java.util.UUID

// A separate owner records side effects; editing business code must not recreate this ledger.
object FrameworkLedger {
    val events = mutableStateListOf<String>()
    fun record(event: String) {
        events.add(event)
        android.util.Log.i("FrameworkLedger", event)
    }
}
fun token(): String = UUID.randomUUID().toString().take(8)
