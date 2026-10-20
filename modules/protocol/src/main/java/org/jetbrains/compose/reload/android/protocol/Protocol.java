package org.jetbrains.compose.reload.android.protocol;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
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
    public static final int VERSION = 2;
    public static final int MAX_FRAME_SIZE = 512 * 1024 * 1024;

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
            this.reloadRequestId = reloadRequestId;
            this.definitions = definitions;
            this.reinitializeStatics = reinitializeStatics;
            this.invalidatedGroupKeys = invalidatedGroupKeys;
            this.resourceApk = resourceApk;
        }

        @Override int type() { return RELOAD; }

        @Override void writePayload(DataOutputStream out) throws IOException {
            writeString(out, reloadRequestId);
            out.writeInt(definitions.size());
            for (ClassDefinition definition : definitions) {
                writeString(out, definition.className);
                writeBytes(out, definition.dex);
                out.writeBoolean(definition.staticsOnly);
            }
            writeStrings(out, reinitializeStatics);
            out.writeInt(invalidatedGroupKeys.length);
            for (int key : invalidatedGroupKeys) out.writeInt(key);
            out.writeBoolean(resourceApk != null);
            if (resourceApk != null) writeBytes(out, resourceApk);
        }

        static Reload read(DataInputStream in) throws IOException {
            String id = readString(in);
            int count = readCount(in);
            List<ClassDefinition> definitions = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                definitions.add(new ClassDefinition(readString(in), readBytes(in), in.readBoolean()));
            }
            List<String> reinitialize = readStrings(in);
            int[] groups = new int[readCount(in)];
            for (int i = 0; i < groups.length; i++) groups[i] = in.readInt();
            byte[] resourceApk = in.readBoolean() ? readBytes(in) : null;
            return new Reload(id, definitions, reinitialize, groups, resourceApk);
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

    public static void write(OutputStream stream, Message message) throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        message.writePayload(new DataOutputStream(payload));
        DataOutputStream out = new DataOutputStream(stream);
        out.writeInt(message.type());
        out.writeInt(payload.size());
        payload.writeTo(out);
        out.flush();
    }

    /** Returns null at the end of the stream */
    public static Message read(InputStream stream) throws IOException {
        DataInputStream frame = new DataInputStream(stream);
        int type;
        try {
            type = frame.readInt();
        } catch (EOFException e) {
            return null;
        }
        int size = frame.readInt();
        if (size < 0 || size > MAX_FRAME_SIZE) throw new IOException("Illegal frame size: " + size);
        byte[] payload = new byte[size];
        frame.readFully(payload);
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(payload));
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

    private static int readCount(DataInputStream in) throws IOException {
        int count = in.readInt();
        /* Every element needs at least one byte: the remaining payload bounds any count */
        if (count < 0 || count > in.available()) throw new IOException("Illegal element count: " + count);
        return count;
    }

    private static void writeBytes(DataOutputStream out, byte[] bytes) throws IOException {
        out.writeInt(bytes.length);
        out.write(bytes);
    }

    private static byte[] readBytes(DataInputStream in) throws IOException {
        int size = in.readInt();
        if (size < 0 || size > in.available()) throw new IOException("Illegal byte array size: " + size);
        byte[] bytes = new byte[size];
        in.readFully(bytes);
        return bytes;
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        writeBytes(out, value.getBytes(StandardCharsets.UTF_8));
    }

    private static String readString(DataInputStream in) throws IOException {
        return new String(readBytes(in), StandardCharsets.UTF_8);
    }

    private static void writeNullableString(DataOutputStream out, String value) throws IOException {
        out.writeBoolean(value != null);
        if (value != null) writeString(out, value);
    }

    private static String readNullableString(DataInputStream in) throws IOException {
        return in.readBoolean() ? readString(in) : null;
    }

    private static void writeStrings(DataOutputStream out, List<String> values) throws IOException {
        out.writeInt(values.size());
        for (String value : values) writeString(out, value);
    }

    private static List<String> readStrings(DataInputStream in) throws IOException {
        int count = readCount(in);
        List<String> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) values.add(readString(in));
        return Collections.unmodifiableList(values);
    }

    private static void writeFrames(DataOutputStream out, List<StackFrame> frames) throws IOException {
        out.writeBoolean(frames != null);
        if (frames == null) return;
        out.writeInt(frames.size());
        for (StackFrame frame : frames) {
            writeString(out, frame.className);
            writeString(out, frame.methodName);
            writeNullableString(out, frame.fileName);
            out.writeInt(frame.lineNumber);
        }
    }

    private static List<StackFrame> readFrames(DataInputStream in) throws IOException {
        if (!in.readBoolean()) return null;
        int count = readCount(in);
        List<StackFrame> frames = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            frames.add(new StackFrame(readString(in), readString(in), readNullableString(in), in.readInt()));
        }
        return frames;
    }
}
