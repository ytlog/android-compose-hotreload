package dev.compose.hotreload.framework
import androidx.compose.runtime.*
import androidx.compose.ui.text.input.TextFieldValue
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

@Stable
@HiltViewModel
class FrameworkModel @Inject constructor(
    val repository: DraftRepository,
    val database: FrameworkDatabase,
    private val formatterProvider: Provider<Formatter>,
    private val saved: SavedStateHandle,
) : ViewModel() {
    val identity = token()
    val id: Int = checkNotNull(saved.get<Int>("id"))
    val formatter = formatterProvider.get()
    val formatterAnchor = formatter
    val repositoryAnchor = repository
    val daoAnchor = repository.dao
    val databaseAnchor = database
    val guard: Any = Class.forName("dev.compose.hotreload.framework.AdmissionGuard").getDeclaredConstructor().newInstance()
    var field by mutableStateOf(TextFieldValue(saved.get<String>("draft") ?: ""))
    var focused by mutableStateOf(false)
    var probe by mutableStateOf("none")
    var saves by mutableIntStateOf(0)
    var calls by mutableIntStateOf(0)
    var queries by mutableIntStateOf(0)
    var freshCalls by mutableIntStateOf(0)
    val callback = registeredAction(repository) { field.text }
    val callbackAnchor = callback
    val observed: Flow<String?> = repository.dao.observe(id)
        .onStart { FrameworkLedger.record("subscribe:$identity") }
        .onEach { FrameworkLedger.record("row:$identity:${it ?: "empty"}") }
        .onCompletion { FrameworkLedger.record("unsubscribe:$identity") }
    init { FrameworkLedger.record("create:$identity:$id") }
    fun edit(value: TextFieldValue) { field = value; saved["draft"] = value.text }
    fun call() { calls++; probe = callback() }
    fun business() { probe = repository.evaluate(field.text) }
    fun binding() { freshCalls++; probe = "${formatter.format(field.text)}|${formatterProvider.get().format(field.text)}" }
    fun save() { viewModelScope.launch { repository.dao.put(Draft(id, field.text)); saves++; probe = "saved:$saves" } }
    fun query() { viewModelScope.launch { queries++; probe = "old:${repository.dao.read(id)}|fresh:${database.drafts().read(id)}" } }
    fun anchors() = callback === callbackAnchor && formatter === formatterAnchor &&
        repository === repositoryAnchor && repository.dao === daoAnchor && database === databaseAnchor
    override fun onCleared() { FrameworkLedger.record("clear:$identity") }
}
