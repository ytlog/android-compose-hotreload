package dev.compose.hotreload.framework

import android.app.Activity
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** An opaque real Activity stops the parent while its retained source receives events. */
class ReactivePauseActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val state = checkNotNull(ReactiveAudit.active)
        val identity = token()
        val status = TextView(this)
        fun refresh() {
            status.text = "Paused: $identity|${state.identity}|${state.source.value}|${state.audit()}|${state.deliveries.joinToString(";")}"
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(status)
            addView(Button(this@ReactivePauseActivity).apply {
                text = "Inspect paused"
                setOnClickListener { refresh() }
            })
            addView(Button(this@ReactivePauseActivity).apply {
                text = "Emit paused"
                setOnClickListener { state.source.value++; refresh() }
            })
            addView(Button(this@ReactivePauseActivity).apply {
                text = "Resume collector"
                setOnClickListener { finish() }
            })
        })
        refresh()
    }
}
