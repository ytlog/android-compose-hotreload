package dev.compose.hotreload.sample

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import java.util.UUID

class StateEditItem(val id: String) {
    val identity = UUID.randomUUID().toString().take(8)
}

class StateEditModel : ViewModel() {
    val identity = UUID.randomUUID().toString().take(8)
    var draft by mutableStateOf("")
    var selected by mutableStateOf("None")
    var collections by mutableIntStateOf(0)
    var calls by mutableIntStateOf(0)
    var result by mutableStateOf("No refactor")
    var resultType by mutableStateOf("None")
    private val objects = (('A'..'I').map(Char::toString) + "X").associateWith(::StateEditItem)
    private val service = RefactorService()
    private val serviceAnchor = service
    // Resolve by name so a rejected modifier edit cannot recompile the UI through a type dependency.
    // The actual live instance makes ART reject the whole batch; the guard is never reconstructed.
    private val admissionGuard: Any = Class.forName("dev.compose.hotreload.sample.StateAdmissionGuard")
        .getDeclaredConstructor().newInstance()
    val serviceIdentity: String get() = service.identity()
    val serviceRetained: Boolean get() = service === serviceAnchor
    fun items(): List<StateEditItem> = visibleItemIds().map { objects.getValue(it) }
    fun selection(): String = if (selected == "None") "None" else "$selected:${objects.getValue(selected).identity}"
    fun invokeRefactor() {
        calls++
        runCatching {
            val value = refactorText(service, draft)
            result = value.toString()
            resultType = value.javaClass.simpleName
        }.onFailure { result = "Refactor error: ${it.javaClass.simpleName}" }
    }
    fun collectGarbage() {
        System.gc()
        System.runFinalization()
        collections++
    }
}
