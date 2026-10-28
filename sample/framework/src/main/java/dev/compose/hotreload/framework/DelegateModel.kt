package dev.compose.hotreload.framework

import androidx.compose.runtime.*

object DelegateAudit { var created by mutableIntStateOf(0); var pages by mutableIntStateOf(0); var disposed by mutableIntStateOf(0) }
class DelegateModel {
    val identity = token()
    var original by mutableStateOf("original")
    var clicks by mutableIntStateOf(0)
    init { DelegateAudit.created++ }
    // ADDED_STATE
    fun added(): String = "absent"
    fun changeAdded() {}
    fun originalDelegate(): Int = System.identityHashCode(javaClass.getDeclaredField("original\$delegate").apply { isAccessible = true }.get(this))
}
