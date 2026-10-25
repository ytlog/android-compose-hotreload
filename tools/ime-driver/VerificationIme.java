package dev.compose.hotreload.ime;

import android.content.*;
import android.inputmethodservice.InputMethodService;
import android.util.Base64;
import android.view.View;
import android.view.inputmethod.*;
import android.widget.TextView;
import java.nio.charset.StandardCharsets;

/** Deterministic Chinese composition through the actual system IME connection, not app setters. */
public final class VerificationIme extends InputMethodService {
    private int starts, restarts, finishes;
    private final BroadcastReceiver commands = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            EditorInfo editor = getCurrentInputEditorInfo();
            InputConnection connection = getCurrentInputConnection();
            if (connection == null || editor == null || !"dev.compose.hotreload.framework".equals(editor.packageName)) {
                setResultCode(2); setResultData("wrong-editor"); return;
            }
            String command = intent.getStringExtra("command");
            String payload = intent.getStringExtra("payload");
            String text = payload == null ? "" : new String(Base64.decode(payload, Base64.NO_WRAP), StandardCharsets.UTF_8);
            boolean ok;
            switch (command) {
                case "compose": ok = connection.setComposingText(text, 1); break;
                case "commit": ok = connection.commitText(text, 1); break;
                case "select": ok = connection.setSelection(intent.getIntExtra("start", -1), intent.getIntExtra("end", -1)); break;
                case "finish": ok = connection.finishComposingText(); break;
                case "status": ok = true; break;
                default: setResultCode(3); setResultData("unknown-command"); return;
            }
            setResultCode(ok ? 1 : 4);
            setResultData("starts=" + starts + ";restarts=" + restarts + ";finishes=" + finishes);
        }
    };
    @Override public void onCreate() {
        super.onCreate();
        // Only privileged shell/system senders can drive this test-only IME.
        registerReceiver(commands, new IntentFilter("dev.compose.hotreload.ime.COMMAND"),
            "android.permission.DUMP", null, Context.RECEIVER_EXPORTED);
    }
    @Override public void onDestroy() { unregisterReceiver(commands); super.onDestroy(); }
    @Override public void onStartInput(EditorInfo info, boolean restarting) {
        super.onStartInput(info, restarting); if (restarting) restarts++; else starts++;
    }
    @Override public void onFinishInput() { finishes++; super.onFinishInput(); }
    @Override public boolean onEvaluateFullscreenMode() { return false; }
    @Override public View onCreateInputView() {
        TextView view = new TextView(this); view.setText("Verification IME"); view.setTextSize(12);
        view.setPadding(8, 8, 8, 8); return view;
    }
}
