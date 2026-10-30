package org.jetbrains.compose.reload.android.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.EOFException;

/** Runs in a heap smaller than an advertised valid frame to catch eager full-payload allocation. */
public final class ProtocolStreamingProbe {
    public static void main(String[] args) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream header = new DataOutputStream(bytes);
        header.writeInt(Protocol.RELOAD);
        header.writeInt(Protocol.MAX_FRAME_SIZE);
        try {
            Protocol.read(new ByteArrayInputStream(bytes.toByteArray()));
            throw new AssertionError("Missing body must be truncated");
        } catch (EOFException expected) {
            System.out.println("PASS: no full-frame allocation");
        }
    }
}
