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
import static org.jetbrains.compose.reload.android.protocol.TransportFailure.Stage.*;
import static org.jetbrains.compose.reload.android.protocol.TransportFailure.Reason.*;

/** Device-side session. UI callers enqueue preflighted messages; a bounded writer owns the stream. */
public final class RuntimeSession implements Closeable {
    public static final int MAX_QUEUED_BYTES = 4 * 1024 * 1024;
    private final PushbackInputStream input;
    private final OutputStream output;
    private final Closeable transport;
    private final long frameTimeoutMillis;
    private final long writeTimeoutMillis;
    private final long maxQueuedBytes;
    private final ArrayBlockingQueue<Protocol.PreparedMessage> outgoing;
    private final Object state = new Object();
    private boolean closed;
    // Includes the writer's in-flight frame until its write completes.
    private long queuedBytes;
    private TransportFailure failure;
    private final Thread writer;
    private final Consumer<Exception> onFailure;

    public static RuntimeSession accept(InputStream input, OutputStream output, Closeable transport,
        Protocol.Hello hello, Consumer<Exception> onFailure) throws IOException {
        return accept(input, output, transport, hello, 5_000, 120_000, 10_000, 256, MAX_QUEUED_BYTES, onFailure);
    }

    static RuntimeSession accept(InputStream input, OutputStream output, Closeable transport,
        Protocol.Hello hello, long handshakeTimeoutMillis, long frameTimeoutMillis,
        long writeTimeoutMillis, int queueCapacity) throws IOException {
        return accept(input, output, transport, hello, handshakeTimeoutMillis, frameTimeoutMillis,
            writeTimeoutMillis, queueCapacity, MAX_QUEUED_BYTES, error -> { });
    }

    static RuntimeSession accept(InputStream input, OutputStream output, Closeable transport,
        Protocol.Hello hello, long handshakeTimeoutMillis, long frameTimeoutMillis,
        long writeTimeoutMillis, int queueCapacity, long maxQueuedBytes, Consumer<Exception> onFailure) throws IOException {
        if (frameTimeoutMillis <= 0 || writeTimeoutMillis <= 0 || queueCapacity <= 0 || maxQueuedBytes <= 0) {
            throw new IllegalArgumentException("Session limits must be positive");
        }
        PushbackInputStream bufferedInput = new PushbackInputStream(new BufferedInputStream(input), 1);
        OutputStream bufferedOutput = new BufferedOutputStream(output);
        try {
            IoDeadline.run(transport, handshakeTimeoutMillis, "Runtime handshake deadline=" + handshakeTimeoutMillis + "ms", () -> {
                Protocol.readHandshake(bufferedInput);
                Protocol.writeHandshake(bufferedOutput);
                Protocol.write(bufferedOutput, hello);
                return null;
            });
            return new RuntimeSession(bufferedInput, bufferedOutput, transport,
                frameTimeoutMillis, writeTimeoutMillis, queueCapacity, maxQueuedBytes, onFailure);
        } catch (IOException error) {
            try { transport.close(); } catch (IOException ignored) { }
            throw TransportFailure.io(HANDSHAKE, null, error);
        } catch (RuntimeException error) {
            try { transport.close(); } catch (IOException ignored) { }
            throw error;
        }
    }

    private RuntimeSession(PushbackInputStream input, OutputStream output, Closeable transport,
        long frameTimeoutMillis, long writeTimeoutMillis, int queueCapacity, long maxQueuedBytes, Consumer<Exception> onFailure) {
        this.onFailure = onFailure;
        this.input = input;
        this.output = output;
        this.transport = transport;
        this.frameTimeoutMillis = frameTimeoutMillis;
        this.writeTimeoutMillis = writeTimeoutMillis;
        this.maxQueuedBytes = maxQueuedBytes;
        this.outgoing = new ArrayBlockingQueue<>(queueCapacity);
        writer = new Thread(this::writeMessages, "Compose Hot Reload: Device Writer");
        writer.setDaemon(true);
        writer.start();
    }

    /** Idle is unlimited; once a frame starts, partial/trickled input has one absolute deadline. */
    public Protocol.Message read() throws IOException {
        try {
            int first = input.read();
            if (first < 0) { close(new TransportFailure(READ_FRAME, PEER_CLOSED, null, "EOF at frame boundary", null)); return null; }
            input.unread(first);
            return IoDeadline.run(() -> close(timeout(READ_FRAME, null, frameTimeoutMillis)), frameTimeoutMillis,
                "Runtime frame deadline=" + frameTimeoutMillis + "ms", () -> Protocol.read(input));
        } catch (IOException error) {
            close(TransportFailure.io(READ_FRAME, null, error));
            throw error;
        }
    }

    /** Never wait for the peer. Bounds include both frame count and encoded bytes in flight. */
    public boolean send(Protocol.Message message) {
        if (isClosed()) return false;
        Protocol.PreparedMessage prepared;
        try { prepared = Protocol.prepare(message); }
        catch (IOException error) {
            close(new TransportFailure(ENQUEUE, MESSAGE_REJECTED, Protocol.requestId(message), error.getMessage(), error));
            return false;
        }
        TransportFailure rejected;
        synchronized (state) {
            if (closed) return false;
            if (prepared.wireSize() > maxQueuedBytes - queuedBytes) {
                rejected = new TransportFailure(ENQUEUE, BYTE_BUDGET, prepared.requestId(),
                    "retainedBytes=" + queuedBytes + " frameBytes=" + prepared.wireSize() + " limit=" + maxQueuedBytes, null);
            } else if (outgoing.offer(prepared)) {
                queuedBytes += prepared.wireSize();
                return true;
            } else {
                rejected = new TransportFailure(ENQUEUE, QUEUE_FULL, prepared.requestId(),
                    "queuedFrames=" + outgoing.size() + " retainedBytes=" + queuedBytes, null);
            }
        }
        close(rejected);
        return false;
    }

    public boolean isClosed() { synchronized (state) { return closed; } }
    public TransportFailure failure() { synchronized (state) { return failure; } }

    private static TransportFailure timeout(TransportFailure.Stage stage, String id, long millis) {
        return new TransportFailure(stage, TIMEOUT, id, "deadline=" + millis + "ms", null);
    }

    private void writeMessages() {
        Protocol.PreparedMessage message = null;
        try {
            while (!isClosed()) {
                message = outgoing.take();
                if (isClosed()) break;
                Protocol.PreparedMessage current = message;
                IoDeadline.run(() -> close(timeout(WRITE_FRAME, current.requestId(), writeTimeoutMillis)),
                    writeTimeoutMillis, "Runtime write deadline=" + writeTimeoutMillis + "ms", () -> {
                        current.writeTo(output);
                        return null;
                    });
                synchronized (state) { if (!closed) queuedBytes -= current.wireSize(); }
            }
        } catch (IOException error) {
            close(TransportFailure.io(WRITE_FRAME, message == null ? null : message.requestId(), error));
        } catch (RuntimeException error) {
            close(new TransportFailure(WRITE_FRAME, IO_FAILURE, message == null ? null : message.requestId(),
                error.getClass().getSimpleName(), error));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally { close(); }
    }

    @Override public void close() { close(null); }

    private void close(TransportFailure reason) {
        synchronized (state) {
            if (closed) return;
            closed = true;
            failure = reason;
            outgoing.clear();
            queuedBytes = 0;
        }
        writer.interrupt();
        try { transport.close(); } catch (IOException ignored) { }
        if (reason != null) {
            try { onFailure.accept(reason); } catch (RuntimeException ignored) { }
        }
    }
}
