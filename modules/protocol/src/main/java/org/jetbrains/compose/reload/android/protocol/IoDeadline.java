package org.jetbrains.compose.reload.android.protocol;

import java.io.Closeable;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Absolute I/O deadline, shared by the host TCP and device local-socket adapters. */
public final class IoDeadline {
    private static final ScheduledThreadPoolExecutor TIMER = new ScheduledThreadPoolExecutor(1, action -> {
        Thread thread = new Thread(action, "Compose Hot Reload: I/O deadline");
        thread.setDaemon(true);
        return thread;
    });
    static { TIMER.setRemoveOnCancelPolicy(true); }
    private IoDeadline() {}

    public interface Action<T> { T run() throws IOException; }

    public static <T> T run(Closeable connection, long timeoutMillis, String description, Action<T> action)
        throws IOException {
        if (timeoutMillis <= 0) throw new IllegalArgumentException("timeoutMillis must be positive");
        State state = new State();
        ScheduledFuture<?> timer = TIMER.schedule(() -> {
            synchronized (state) {
                if (state.finished) return;
                state.expired = true;
            }
            try { connection.close(); } catch (IOException ignored) { }
        }, timeoutMillis, TimeUnit.MILLISECONDS);
        try {
            T result = action.run();
            synchronized (state) {
                state.finished = true;
                if (state.expired) throw new SocketTimeoutException(description + " timed out");
            }
            return result;
        } catch (IOException error) {
            synchronized (state) {
                if (state.expired && !(error instanceof SocketTimeoutException)) {
                    SocketTimeoutException timeout = new SocketTimeoutException(description + " timed out");
                    timeout.initCause(error);
                    throw timeout;
                }
            }
            throw error;
        } finally {
            synchronized (state) { state.finished = true; }
            timer.cancel(false);
        }
    }

    private static final class State {
        boolean finished;
        boolean expired;
    }
}
