package org.jetbrains.compose.reload.android.protocol;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Bounded, length-prefixed binary frames exchanged between the host bridge and the device runtime.
 * <p>
 * The host bridge takes the role of the upstream 'hot-reload-agent' towards the orchestration
 * (it cannot run on ART: the upstream orchestration client requires {@code java.lang.ProcessHandle}).
 * The device runtime only executes what the JVM agent would execute in-process:
 * class redefinition, statics re-initialization and Compose invalidation on the UI thread.
 */
public final class Protocol {
    private Protocol() {
    }

    public static final int MAGIC = 0x43485241; // 'CHRA'
    public static final int VERSION = 3;
    public static final int MAX_FRAME_SIZE = 64 * 1024 * 1024;
    public static final int MAX_EVENT_SIZE = 1024 * 1024;
    public static final int MAX_CONTROL_SIZE = 64 * 1024;
    public static final int MAX_COLLECTION_SIZE = 65_536;

    /* Host -> Device */
    static final int RELOAD = 1;
    static final int RESET_COMPOSITION = 2;
    static final int SHUTDOWN = 3;

    /* Device -> Host */
    static final int HELLO = 100;
    static final int RELOAD_RESULT = 101;
    static final int UI_RENDERED = 102;
    static final int UI_EXCEPTION = 103;

    public static abstract class Message {
        abstract int type();

        abstract void writePayload(DataOutputStream out) throws IOException;
    }

    /** Sent by the device once the connection is accepted. {@code runtimeError} is null if the runtime is usable. */
    public static final class Hello extends Message {
        public final int protocolVersion;
        public final int pid;
        public final String packageName;
        public final String runtimeError;

        public Hello(int protocolVersion, int pid, String packageName, String runtimeError) {
            this.protocolVersion = protocolVersion;
            this.pid = pid;
            this.packageName = packageName;
            this.runtimeError = runtimeError;
        }

        @Override int type() { return HELLO; }

        @Override void writePayload(DataOutputStream out) throws IOException {
            out.writeInt(protocolVersion);
            out.writeInt(pid);
            writeString(out, packageName);
            writeNullableString(out, runtimeError);
        }

        static Hello read(DataInputStream in) throws IOException {
            return new Hello(in.readInt(), in.readInt(), readString(in), readNullableString(in));
        }
    }

    /** A class definition in DEX format. One class per DEX ('DexFilePerClass'). */
    public static final class ClassDefinition {
        public final String className;
        public final byte[] dex;
        /**
         * The class is only transformed to re-initialize its statics (upstream 'reinitializeStaticsIfNecessary').
         * Redefined only if loaded and not transformed already.
         */
        public final boolean staticsOnly;

        public ClassDefinition(String className, byte[] dex, boolean staticsOnly) {
            this.className = className;
            this.dex = dex;
            this.staticsOnly = staticsOnly;
        }
    }

    /** Equivalent of the upstream agent's reload of one 'ReloadClassesRequest' */
    public static final class Reload extends Message {
        public final String reloadRequestId;
        public final List<ClassDefinition> definitions;
        /** Classes whose statics shall be re-initialized, in execution order */
        public final List<String> reinitializeStatics;
        public final int[] invalidatedGroupKeys;
        /** Unchanged scope trees whose dependencies changed: recompose without discarding slots. */
        public final int[] recomposedGroupKeys;
        /** Optional APK containing resources and assets, without executable code. */
        public final byte[] resourceApk;

        public Reload(
            String reloadRequestId, List<ClassDefinition> definitions,
            List<String> reinitializeStatics, int[] invalidatedGroupKeys
        ) {
            this(reloadRequestId, definitions, reinitializeStatics, invalidatedGroupKeys, null);
        }

        public Reload(
            String reloadRequestId, List<ClassDefinition> definitions,
            List<String> reinitializeStatics, int[] invalidatedGroupKeys, byte[] resourceApk
        ) {
            this(reloadRequestId, definitions, reinitializeStatics, invalidatedGroupKeys, resourceApk, new int[0]);
        }

        public Reload(
            String reloadRequestId, List<ClassDefinition> definitions,
            List<String> reinitializeStatics, int[] invalidatedGroupKeys, byte[] resourceApk, int[] recomposedGroupKeys
        ) {
            this.reloadRequestId = reloadRequestId;
            this.definitions = definitions;
            this.reinitializeStatics = reinitializeStatics;
            this.invalidatedGroupKeys = invalidatedGroupKeys;
            this.recomposedGroupKeys = recomposedGroupKeys;
            this.resourceApk = resourceApk;
        }

        @Override int type() { return RELOAD; }

        @Override void writePayload(DataOutputStream out) throws IOException {
            writeString(out, reloadRequestId);
            writeCount(out, definitions.size());
            for (ClassDefinition definition : definitions) {
                writeString(out, definition.className);
                writeBytes(out, definition.dex);
                out.writeBoolean(definition.staticsOnly);
            }
            writeStrings(out, reinitializeStatics);
            writeCount(out, invalidatedGroupKeys.length);
            for (int key : invalidatedGroupKeys) out.writeInt(key);
            out.writeBoolean(resourceApk != null);
            if (resourceApk != null) writeBytes(out, resourceApk);
            writeCount(out, recomposedGroupKeys.length);
            for (int key : recomposedGroupKeys) out.writeInt(key);
        }

        static Reload read(DataInputStream in) throws IOException {
            String id = readString(in);
            int count = readCount(in, 9);
            List<ClassDefinition> definitions = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                definitions.add(new ClassDefinition(readString(in), readBytes(in), in.readBoolean()));
            }
            List<String> reinitialize = readStrings(in);
            int[] groups = new int[readCount(in, 4)];
            for (int i = 0; i < groups.length; i++) groups[i] = in.readInt();
            byte[] resourceApk = in.readBoolean() ? readBytes(in) : null;
            int[] recompose = new int[readCount(in, 4)];
            for (int i = 0; i < recompose.length; i++) recompose[i] = in.readInt();
            return new Reload(id, definitions, reinitialize, groups, resourceApk, recompose);
        }
    }

    /** Relays upstream 'CleanCompositionRequest' and 'RetryFailedCompositionRequest' */
    public static final class ResetComposition extends Message {
        /** upstream 'compose.reload.compositionHardResetEnabled' */
        public final boolean hardReset;

        public ResetComposition(boolean hardReset) {
            this.hardReset = hardReset;
        }

        @Override int type() { return RESET_COMPOSITION; }

        @Override void writePayload(DataOutputStream out) throws IOException {
            out.writeBoolean(hardReset);
        }
    }

    /** Relays an applicable upstream 'ShutdownRequest' */
    public static final class Shutdown extends Message {
        public final String reason;

        public Shutdown(String reason) {
            this.reason = reason;
        }

        @Override int type() { return SHUTDOWN; }

        @Override void writePayload(DataOutputStream out) throws IOException {
            writeNullableString(out, reason);
        }
    }

    public static final class StackFrame {
        public final String className;
        public final String methodName;
        public final String fileName;
        public final int lineNumber;

        public StackFrame(String className, String methodName, String fileName, int lineNumber) {
            this.className = className;
            this.methodName = methodName;
            this.fileName = fileName;
            this.lineNumber = lineNumber;
        }

        public static List<StackFrame> of(Throwable throwable) {
            List<StackFrame> frames = new ArrayList<>();
            /* upstream: 'withLinearClosure { it.cause }.flatMap { it.stackTrace }' */
            for (Throwable current = throwable; current != null; current = current.getCause()) {
                for (StackTraceElement element : current.getStackTrace()) {
                    frames.add(new StackFrame(
                        element.getClassName(), element.getMethodName(),
                        element.getFileName(), element.getLineNumber()
                    ));
                }
                if (current.getCause() == current) break;
            }
            return frames;
        }
    }

    /** Mirrors upstream 'ReloadClassesResult' */
    public static final class ReloadResult extends Message {
        public final String reloadRequestId;
        public final boolean success;
        /** New classes were appended to the application class loader (irreversible) */
        public final boolean classesAdded;
        /** 'RedefineClasses' succeeded: a failure happened afterward (statics re-initialization, invalidation) */
        public final boolean classesRedefined;
        public final String errorMessage;
        public final List<StackFrame> errorStacktrace;

        public ReloadResult(
            String reloadRequestId, boolean success, boolean classesAdded, boolean classesRedefined,
            String errorMessage, List<StackFrame> errorStacktrace
        ) {
            this.reloadRequestId = reloadRequestId;
            this.success = success;
            this.classesAdded = classesAdded;
            this.classesRedefined = classesRedefined;
            this.errorMessage = errorMessage;
            this.errorStacktrace = errorStacktrace;
        }

        @Override int type() { return RELOAD_RESULT; }

        @Override void writePayload(DataOutputStream out) throws IOException {
            writeString(out, reloadRequestId);
            out.writeBoolean(success);
            out.writeBoolean(classesAdded);
            out.writeBoolean(classesRedefined);
            writeNullableString(out, errorMessage);
            writeFrames(out, errorStacktrace);
        }

        static ReloadResult read(DataInputStream in) throws IOException {
            return new ReloadResult(
                readString(in), in.readBoolean(), in.readBoolean(), in.readBoolean(),
                readNullableString(in), readFrames(in)
            );
        }
    }

    /** Mirrors upstream 'UIRendered' (Android has no 'WindowId') */
    public static final class UIRendered extends Message {
        public final String reloadRequestId;
        public final int iteration;

        public UIRendered(String reloadRequestId, int iteration) {
            this.reloadRequestId = reloadRequestId;
            this.iteration = iteration;
        }

        @Override int type() { return UI_RENDERED; }

        @Override void writePayload(DataOutputStream out) throws IOException {
            writeNullableString(out, reloadRequestId);
            out.writeInt(iteration);
        }
    }

    /** Mirrors upstream 'UIException' */
    public static final class UIException extends Message {
        public final String message;
        public final List<StackFrame> stacktrace;

        public UIException(String message, List<StackFrame> stacktrace) {
            this.message = message;
            this.stacktrace = stacktrace;
        }

        @Override int type() { return UI_EXCEPTION; }

        @Override void writePayload(DataOutputStream out) throws IOException {
            writeNullableString(out, message);
            writeFrames(out, stacktrace);
        }
    }

    /* Framing */

    public static void writeHandshake(OutputStream stream) throws IOException {
        DataOutputStream out = new DataOutputStream(stream);
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.flush();
    }

    public static void readHandshake(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        int magic = in.readInt();
        if (magic != MAGIC) throw new IOException("Unexpected magic number: " + Integer.toHexString(magic));
        int version = in.readInt();
        if (version != VERSION) throw new IOException("Unsupported protocol version " + version + " (expected " + VERSION + ")");
    }

    /** Preflight without a payload copy. Reject before any frame bytes reach the peer. */
    public static PreparedMessage prepare(Message message) throws IOException {
        int limit = payloadLimit(message.type());
        BoundedOutput counter = new BoundedOutput(null, limit);
        message.writePayload(new SizingOutput(counter));
        return new PreparedMessage(message, limit - counter.remaining);
    }

    /** Borrows message fields until writeTo completes; callers must not mutate them while queued. */
    public static final class PreparedMessage {
        private final Message message;
        private final int payloadSize;
        private PreparedMessage(Message message, int payloadSize) {
            this.message = message;
            this.payloadSize = payloadSize;
        }
        public int wireSize() { return payloadSize + 8; }
        public String requestId() { return Protocol.requestId(message); }
        public void writeTo(OutputStream stream) throws IOException {
            DataOutputStream frame = new DataOutputStream(stream);
            frame.writeInt(message.type());
            frame.writeInt(payloadSize);
            BoundedOutput payload = new BoundedOutput(stream, payloadSize);
            message.writePayload(new DataOutputStream(payload));
            if (payload.remaining != 0) throw new IOException("Message changed after preflight");
            stream.flush();
        }
    }

    public static String requestId(Message message) {
        if (message instanceof Reload) return ((Reload) message).reloadRequestId;
        if (message instanceof ReloadResult) return ((ReloadResult) message).reloadRequestId;
        if (message instanceof UIRendered) return ((UIRendered) message).reloadRequestId;
        return null;
    }

    public static void write(OutputStream stream, Message message) throws IOException {
        prepare(message).writeTo(stream);
    }

    private static int payloadLimit(int type) throws IOException {
        switch (type) {
            case RELOAD: return MAX_FRAME_SIZE;
            case RELOAD_RESULT: case UI_EXCEPTION: return MAX_EVENT_SIZE;
            case HELLO: case SHUTDOWN: case UI_RENDERED: return MAX_CONTROL_SIZE;
            case RESET_COMPOSITION: return 1;
            default: throw new IOException("Unknown message type: " + type);
        }
    }

    /** Returns null only at a frame boundary. Partial headers/payloads are truncated frames. */
    public static Message read(InputStream stream) throws IOException {
        int first = stream.read();
        if (first < 0) return null;
        DataInputStream frame = new DataInputStream(stream);
        int type = (first << 24) | (frame.readUnsignedByte() << 16)
            | (frame.readUnsignedByte() << 8) | frame.readUnsignedByte();
        int size = frame.readInt();
        int limit = payloadLimit(type);
        if (size < 0 || size > limit) throw new IOException("Frame size " + size + " exceeds type " + type + " limit " + limit);
        // Parse directly from a bounded view, retaining only the actual fields (DEX/resources included).
        DataInputStream in = new DataInputStream(new PayloadInput(stream, size));
        Message message;
        switch (type) {
            case RELOAD: message = Reload.read(in); break;
            case RESET_COMPOSITION: message = new ResetComposition(in.readBoolean()); break;
            case SHUTDOWN: message = new Shutdown(readNullableString(in)); break;
            case HELLO: message = Hello.read(in); break;
            case RELOAD_RESULT: message = ReloadResult.read(in); break;
            case UI_RENDERED: message = new UIRendered(readNullableString(in), in.readInt()); break;
            case UI_EXCEPTION: message = new UIException(readNullableString(in), readFrames(in)); break;
            default: throw new IOException("Unknown message type: " + type);
        }
        if (in.available() != 0) throw new IOException("Trailing bytes in frame of type " + type);
        return message;
    }

    /* Encoding */

    private static void writeCount(DataOutputStream out, int count) throws IOException {
        if (count < 0 || count > MAX_COLLECTION_SIZE) throw new IOException("Illegal element count: " + count);
        out.writeInt(count);
    }

    private static int readCount(DataInputStream in, int minimumBytes) throws IOException {
        int count = in.readInt();
        if (count < 0 || count > MAX_COLLECTION_SIZE || count > in.available() / minimumBytes)
            throw new IOException("Illegal element count: " + count);
        return count;
    }

    private static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException { return readBytes(in, MAX_FRAME_SIZE); }

    private static byte[] readBytes(DataInputStream in, int limit) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > limit || size > in.available()) throw new IOException("Illegal byte array size: " + size);
        byte[] bytes = new byte[size];
        in.readFully(bytes);
        return bytes;
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        // String.getBytes would allocate before a counting stream could reject an oversized value.
        int size = utf8Size(value);
        out.writeInt(size);
        if (out instanceof SizingOutput) ((SizingOutput) out).counter.write(null, 0, size);
        else out.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private static int utf8Size(String value) throws IOException {
        if (value.length() > MAX_EVENT_SIZE) throw new IOException("String exceeds byte limit " + MAX_EVENT_SIZE);
        int size = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x80) size++;
            else if (c < 0x800) size += 2;
            else if (Character.isHighSurrogate(c) && i + 1 < value.length() && Character.isLowSurrogate(value.charAt(i + 1))) {
                size += 4; i++;
            } else size += Character.isSurrogate(c) ? 1 : 3; // UTF-8 encoder replaces malformed input with '?'.
            if (size > MAX_EVENT_SIZE) throw new IOException("String exceeds byte limit " + MAX_EVENT_SIZE);
        }
        return size;
    }

    private static String readString(DataInputStream in) throws IOException {
        return new String(readBytes(in, MAX_EVENT_SIZE), StandardCharsets.UTF_8);
    }

    private static final class SizingOutput extends DataOutputStream {
        final BoundedOutput counter;
        SizingOutput(BoundedOutput counter) { super(counter); this.counter = counter; }
    }

    private static final class BoundedOutput extends OutputStream {
        final OutputStream target;
        int remaining;
        BoundedOutput(OutputStream target, int size) { this.target = target; remaining = size; }
        private void reserve(int count) throws IOException {
            if (count < 0 || count > remaining) throw new IOException("Payload exceeds byte budget; remaining=" + remaining + ", requested=" + count);
            remaining -= count;
        }
        @Override public void write(int value) throws IOException { reserve(1); if (target != null) target.write(value); }
        @Override public void write(byte[] bytes, int offset, int size) throws IOException {
            reserve(size); if (target != null) target.write(bytes, offset, size);
        }
    }

    private static final class PayloadInput extends InputStream {
        final InputStream source;
        int remaining;
        PayloadInput(InputStream source, int size) { this.source = source; remaining = size; }
        @Override public int available() { return remaining; }
        @Override public int read() throws IOException {
            if (remaining == 0) return -1;
            int value = source.read();
            if (value < 0) throw new EOFException("Truncated frame payload");
            remaining--; return value;
        }
        @Override public int read(byte[] bytes, int offset, int size) throws IOException {
            if (size == 0) return 0;
            if (remaining == 0) return -1;
            int count = source.read(bytes, offset, Math.min(size, remaining));
            if (count < 0) throw new EOFException("Truncated frame payload");
            remaining -= count; return count;
        }
    }

    private static void writeNullableString(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) writeString(out, value);
    }

    private static String readNullableString(DataInputStream in) throws IOException {
        return in.readBoolean() ? readString(in) : null;
    }

    private static void writeStrings(DataOutputStream out, List<String> values) throws IOException {
        writeCount(out, values.size());
        for (String value : values) writeString(out, value);
    }

    private static List<String> readStrings(DataInputStream in) throws IOException {
        int count = readCount(in, 4);
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) values.add(readString(in));
        return Collections.unmodifiableList(values);
    }

    private static void writeFrames(DataOutputStream out, List<StackFrame> frames) throws IOException {
        out.writeBoolean(frames != null);
        if (frames == null) return;
        writeCount(out, frames.size());
        for (StackFrame frame : frames) {
            writeString(out, frame.className);
            writeString(out, frame.methodName);
            writeNullableString(out, frame.fileName);
            out.writeInt(frame.lineNumber);
        }
    }

    private static List<StackFrame> readFrames(DataInputStream in) throws IOException {
        if (!in.readBoolean()) return null;
        int count = readCount(in, 13);
        List<StackFrame> frames = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            frames.add(new StackFrame(readString(in), readString(in), readNullableString(in), in.readInt()));
        }
        return frames;
    }
}
