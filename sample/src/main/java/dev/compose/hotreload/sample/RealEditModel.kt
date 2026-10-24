package dev.compose.hotreload.sample

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import java.util.UUID

class RealEditModel : ViewModel() {
    val identity = UUID.randomUUID().toString().take(8)
    var input by mutableStateOf("")
    var selected by mutableStateOf("List")
    var result by mutableStateOf("No action")
    var callbackResult by mutableStateOf("No callback")
    var operations by mutableIntStateOf(0)
    var collections by mutableIntStateOf(0)
    var callbackCalls by mutableIntStateOf(0)
    private var registered by mutableStateOf(registerEditCallback(this))
    private var registrationAnchor = registered
    val callbackRetained: Boolean get() = registered === registrationAnchor
    val callbackIdentity: Int get() = System.identityHashCode(registered)

    fun applyBusinessLogic() {
        operations++
        result = processEdit(input)
    }

    fun invokeRegistered() {
        callbackCalls++
        callbackResult = runCatching { registered() }.getOrElse { "Callback error: ${it.javaClass.simpleName}" }
    }

    fun registerAgain() {
        registered = registerEditCallback(this)
        registrationAnchor = registered
    }

    fun collectGarbage() {
        System.gc()
        System.runFinalization()
        collections++
    }
}
