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
    var registrations by mutableIntStateOf(0)
        private set
    var controlCalls by mutableIntStateOf(0)
        private set
    var controlResult by mutableStateOf("No control")
        private set
    private val registered = registerEditCallback(this)
    private val registrationAnchor = registered
    private var control: (() -> String)? = null
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

    fun recordRegistration() {
        registrations++
    }

    /** A separate control closure; the original listener and its anchor are never replaced. */
    fun makeControl() {
        check(control == null)
        control = registerEditCallback(this)
    }

    fun invokeControl() {
        controlCalls++
        controlResult = runCatching { checkNotNull(control)() }
            .getOrElse { "Callback error: ${it.javaClass.simpleName}" }
    }

    fun collectGarbage() {
        System.gc()
        System.runFinalization()
        collections++
    }
}
