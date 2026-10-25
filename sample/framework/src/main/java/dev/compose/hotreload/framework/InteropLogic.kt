package dev.compose.hotreload.framework

import android.app.Activity
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultCallback

fun nativeLabel(): String = "Native label v1"
fun nativeUpdateNote(): String = "update-v1"
fun nativeBusiness(text: String): String = "native-business-v1:$text"

// These objects are created once. Android's View/registry retains them across reloads.
fun nativeClick(state: InteropState): View.OnClickListener = View.OnClickListener {
    state.clicks++
    state.nativeProbe = "click-v1:${nativeBusiness(state.editor.text.toString())}"
}

fun nativeWatcher(state: InteropState): TextWatcher = object : TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: Editable?) {
        state.watches++
        state.watchProbe = "watch-v1:$s"
    }
}

fun resultBusiness(text: String): String = "result-business-v1:$text"
fun retainedResult(state: InteropState): ActivityResultCallback<ActivityResult> = ActivityResultCallback {
    state.results++
    val value = if (it.resultCode == Activity.RESULT_OK) it.data?.getStringExtra("value") else "cancel"
    state.resultProbe = "result-v1:${resultBusiness(value ?: "missing") }"
}
