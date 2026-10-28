package dev.compose.hotreload.tools;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/** Shell-UID test input, preserving one DOWN time across an entire real system gesture. */
public final class GestureDriver {
    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("android.hardware.input.InputManagerGlobal");
        Object manager = type.getMethod("getInstance").invoke(null);
        Method inject = type.getMethod("injectInputEvent", InputEvent.class, int.class);
        BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
        long downTime = 0;
        boolean active = false;
        float x = 0, y = 0;
        System.out.println("READY"); System.out.flush();
        try {
            for (String line; (line = input.readLine()) != null;) {
                if (line.equals("QUIT")) break;
                String[] parts = line.split(" ");
                int action;
                switch (parts[0]) {
                    case "DOWN": if (active) throw new IllegalStateException("Already down");
                        action = MotionEvent.ACTION_DOWN; downTime = SystemClock.uptimeMillis(); active = true; break;
                    case "MOVE": action = MotionEvent.ACTION_MOVE; break;
                    case "UP": action = MotionEvent.ACTION_UP; break;
                    case "CANCEL": action = MotionEvent.ACTION_CANCEL; break;
                    default: throw new IllegalArgumentException("Unknown action");
                }
                if (!active) throw new IllegalStateException("No active gesture");
                x = Float.parseFloat(parts[1]); y = Float.parseFloat(parts[2]);
                MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0);
                event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                try { if (!Boolean.TRUE.equals(inject.invoke(manager, event, 2))) throw new IllegalStateException("Input rejected"); }
                finally { event.recycle(); }
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) active = false;
                System.out.println("OK"); System.out.flush();
            }
        } finally {
            if (active) {
                MotionEvent event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, x, y, 0);
                event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
                try { inject.invoke(manager, event, 2); } finally { event.recycle(); }
            }
        }
    }
}
