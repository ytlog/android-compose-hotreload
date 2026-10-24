package dev.compose.hotreload.sample

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.lifecycle.ViewModelProvider
import java.util.UUID

/** Keep the owner and unrelated remember scope outside the files edited by the device runner. */
class RealEditActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val model = ViewModelProvider(this)[RealEditModel::class.java]
        setContent {
            MaterialTheme {
                val sentinel = remember { UUID.randomUUID().toString().take(8) }
                Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Text("Remember: $sentinel")
                    RealEditScreen(model)
                }
            }
        }
    }
}
