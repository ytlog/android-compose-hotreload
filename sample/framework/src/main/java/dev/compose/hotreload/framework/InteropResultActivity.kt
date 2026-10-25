package dev.compose.hotreload.framework

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/** A real, same-package child Activity; results travel through Android, never dispatchResult. */
class InteropResultActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        InteropAudit.children++
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(context).apply { text = "Result pending: ${token()}" })
            addView(Button(context).apply {
                text = "Return OK"
                setOnClickListener {
                    setResult(Activity.RESULT_OK, Intent().putExtra("value", "payload"))
                    finish()
                }
            })
            addView(Button(context).apply {
                text = "Return cancel"
                setOnClickListener { setResult(Activity.RESULT_CANCELED); finish() }
            })
        })
    }

    override fun onDestroy() {
        InteropAudit.destroyed++
        super.onDestroy()
    }
}
