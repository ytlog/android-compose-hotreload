package dev.compose.hotreload.sample

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import java.util.UUID

/** Unedited holder: anchors the actual provider-owned ViewModel and all pre-update objects. */
class PropertyState(val model: PropertyViewModel) : ViewModel() {
    val token = UUID.randomUUID().toString().take(8)
    val business = PropertyBusiness()
    val delegates = PropertyDelegates()
    val data = PropertyData()
    private val admissionGuard: Any = Class.forName("dev.compose.hotreload.sample.StateAdmissionGuard").getDeclaredConstructor().newInstance()
    private val anchors: List<Any> = listOf(model, business, delegates, data)
    var draft by mutableStateOf("")
    var calls by mutableIntStateOf(0)
    var collections by mutableIntStateOf(0)
    var report by mutableStateOf(listOf<String>())
    var seeded by mutableStateOf(false)
    private var expectedCounts = PropertyLedger.counts()
    val retained: Boolean get() = anchors[0] === model && anchors[1] === business &&
        anchors[2] === delegates && anchors[3] === data
    fun seed() {
        check(!seeded)
        model.seedFrom(draft)
        business.seedFrom(draft)
        delegates.seedValue()
        data.title = draft
        data.amount = 41
        seeded = true
    }
    fun inspect() {
        calls++
        try {
            check(retained)
            check(PropertyLedger.counts() == expectedCounts) { "Initialization replayed between calls" }
            report = inspectProperties(model, business, delegates, data)
            expectedCounts = expectedCounts.zip(listOf(1, 1, 2, 1, 1)) { old, delta -> old + delta }
            check(PropertyLedger.counts() == expectedCounts) { "Unexpected constructor/initializer count" }
        } catch (failure: Throwable) {
            report = listOf("Probe failure: ${failure.javaClass.simpleName}: ${failure.message}")
        }
    }
    fun migrate() { delegates.migrate() }
    fun collectGarbage() {
        System.gc()
        System.runFinalization()
        collections++
    }
}
