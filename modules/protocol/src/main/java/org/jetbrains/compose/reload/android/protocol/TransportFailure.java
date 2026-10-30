package org.jetbrains.compose.reload.android.protocol;

import java.io.EOFException;
import java.io.IOException;
import java.net.SocketTimeoutException;

/** Stable transport diagnostics. Never contains a message payload or device selector. */
public final class TransportFailure extends IOException {
    public enum Stage { HANDSHAKE, READ_FRAME, WRITE_FRAME, RELOAD, ENQUEUE, CLOSE }
    public enum Reason { TIMEOUT, TRUNCATED_FRAME, IO_FAILURE, PEER_CLOSED, QUEUE_FULL, BYTE_BUDGET, MESSAGE_REJECTED, LOCAL_CLOSE }
    public final Stage stage;
    public final Reason reason;
    public final String requestId;
    public final String detail;

    public TransportFailure(Stage stage, Reason reason, String requestId, String detail, Throwable cause) {
        super("Transport stage=" + stage + " reason=" + reason +
            (requestId == null ? "" : " requestId=" + bounded(requestId, 128)) + " (" + bounded(detail, 512) + ")", cause);
        this.stage = stage;
        this.reason = reason;
        this.requestId = bounded(requestId, 128);
        this.detail = bounded(detail, 512);
    }

    private static String bounded(String text, int limit) {
        if (text == null) return null;
        String prefix = text.length() <= limit ? text : text.substring(0, limit) + "...";
        return prefix.replace('\n', ' ').replace('\r', ' ');
    }

    public static TransportFailure io(Stage stage, String requestId, IOException cause) {
        Reason reason = cause instanceof SocketTimeoutException ? Reason.TIMEOUT
            : cause instanceof EOFException ? Reason.TRUNCATED_FRAME : Reason.IO_FAILURE;
        return new TransportFailure(stage, reason, requestId, String.valueOf(cause.getMessage()), cause);
    }

    public TransportFailure withRequestId(String id) {
        return new TransportFailure(stage, reason, id, detail, getCause());
    }
}
