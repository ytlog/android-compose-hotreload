package org.jetbrains.compose.reload.android.protocol;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.function.Consumer;

/** Device-side session. UI callers only enqueue; a bounded writer owns the output stream. */
public final class RuntimeSession implements Closeable {
    private final PushbackInputStream input;
    private final OutputStream output;
    private final Closeable transport;
    private final long frameTimeoutMillis;
    private final long writeTimeoutMillis;
    private final ArrayBlockingQueue<Protocol.Message> outgoing;
    private final Object state = new Object();
    private boolean closed;
    private final Thread writer;
    private final Consumer<Exception> onWriteFailure;

    public static RuntimeSession accept(InputStream input, OutputStream output, Closeable transport,
        Protocol.Hello hello, Consumer<Exception> onWriteFailure) throws IOException {
        return accept(input, output, transport, hello, 5_000, 120_000, 10_000, 256, onWriteFailure);
    }

    // Package-private limits let real-socket tests exercise failures without waiting production deadlines.
    static RuntimeSession accept(InputStream input, OutputStream output, Closeable transport,
        Protocol.Hello hello, long handshakeTimeoutMillis, long frameTimeoutMillis,
        long writeTimeoutMillis, int queueCapacity) throws IOException {
        return accept(input, output, transport, hello, handshakeTimeoutMillis, frameTimeoutMillis,
            writeTimeoutMillis, queueCapacity, error -> { });
    }

    private static RuntimeSession accept(InputStream input, OutputStream output, Closeable transport,
        Protocol.Hello hello, long handshakeTimeoutMillis, long frameTimeoutMillis,
        long writeTimeoutMillis, int queueCapacity, Consumer<Exception> onWriteFailure) throws IOException {
        if (frameTimeoutMillis <= 0 || writeTimeoutMillis <= 0 || queueCapacity <= 0) {
            throw new IllegalArgumentException("Session limits must be positive");
        }
        PushbackInputStream bufferedInput = new PushbackInputStream(new BufferedInputStream(input), 1);
        OutputStream bufferedOutput = new BufferedOutputStream(output);
        try {
            IoDeadline.run(transport, handshakeTimeoutMillis, "Runtime handshake", () -> {
                Protocol.readHandshake(bufferedInput);
                Protocol.writeHandshake(bufferedOutput);
                Protocol.write(bufferedOutput, hello);
                return null;
            });
            return new RuntimeSession(bufferedInput, bufferedOutput, transport,
                frameTimeoutMillis, writeTimeoutMillis, queueCapacity, onWriteFailure);
        } catch (IOException | RuntimeException error) {
            try { transport.close(); } catch (IOException ignored) { }
            throw error;
        }
    }

    private RuntimeSession(PushbackInputStream input, OutputStream output, Closeable transport,
        long frameTimeoutMillis, long writeTimeoutMillis, int queueCapacity, Consumer<Exception> onWriteFailure) {
        this.onWriteFailure = onWriteFailure;
        this.input = input;
        this.output = output;
        this.transport = transport;
        this.frameTimeoutMillis = frameTimeoutMillis;
        this.writeTimeoutMillis = writeTimeoutMillis;
        this.outgoing = new ArrayBlockingQueue<>(queueCapacity);
        writer = new Thread(this::writeMessages, "Compose Hot Reload: Device Writer");
        writer.setDaemon(true);
        writer.start();
    }

    /** Idle is unlimited; once a frame starts, partial/trickled input has one absolute deadline. */
    public Protocol.Message read() throws IOException {
        try {
            int first = input.read();
            if (first < 0) { close(); return null; }
            input.unread(first);
            return IoDeadline.run(this, frameTimeoutMillis, "Runtime frame", () -> Protocol.read(input));
        } catch (IOException error) {
            close();
            throw error;
        }
    }

    /** Never wait for the peer. Overflow disconnects rather than silently losing an acknowledgement. */
    public boolean send(Protocol.Message message) {
        synchronized (state) {
            if (closed) return false;
            if (outgoing.offer(message)) return true;
        }
        close();
        return false;
    }

    public boolean isClosed() { synchronized (state) { return closed; } }

    private void writeMessages() {
        try {
            while (!isClosed()) {
                Protocol.Message message = outgoing.take();
                if (isClosed()) break;
                IoDeadline.run(this, writeTimeoutMillis, "Runtime write", () -> {
                    Protocol.write(output, message);
                    return null;
                });
            }
        } catch (IOException | RuntimeException error) {
            // Reporting is diagnostic only; it must not prevent disconnecting the transport.
            try { onWriteFailure.accept(error); } catch (RuntimeException ignored) { }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally { close(); }
    }

    @Override public void close() {
        synchronized (state) {
            if (closed) return;
            closed = true;
            outgoing.clear();
        }
        writer.interrupt();
        try { transport.close(); } catch (IOException ignored) { }
    }
}
