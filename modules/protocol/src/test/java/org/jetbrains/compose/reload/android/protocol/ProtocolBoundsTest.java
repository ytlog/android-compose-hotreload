package org.jetbrains.compose.reload.android.protocol;

import org.junit.Test;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import static org.junit.Assert.*;

public class ProtocolBoundsTest {
    private static byte[] header(int type, int size) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeInt(type); out.writeInt(size);
        return bytes.toByteArray();
    }

    @Test public void rejectsOversizedAndUnknownFramesBeforeReadingTheirBodies() throws Exception {
        for (int[] spec : new int[][] {
            {Protocol.RELOAD, Protocol.MAX_FRAME_SIZE + 1},
            {Protocol.UI_EXCEPTION, Protocol.MAX_EVENT_SIZE + 1},
            {Protocol.UI_RENDERED, Protocol.MAX_CONTROL_SIZE + 1},
            {Protocol.RESET_COMPOSITION, 2}, {999, 0}, {Protocol.HELLO, -1}
        }) {
            byte[] header = header(spec[0], spec[1]);
            InputStream in = new ByteArrayInputStream(header) {
                @Override public synchronized int read() {
                    if (available() == 0) throw new AssertionError("Must reject using only the header");
                    return super.read();
                }
                @Override public synchronized int read(byte[] b, int off, int len) {
                    if (available() == 0) throw new AssertionError("Must reject using only the header");
                    return super.read(b, off, len);
                }
            };
            try { Protocol.read(in); fail("Expected size/type rejection"); }
            catch (IOException expected) { assertFalse(expected instanceof EOFException); }
        }
    }

    @Test public void oversizedReloadIsRejectedWithoutSendingAnyBytes() throws Exception {
        // The array alone fills the budget; headers/fields make the complete payload too large.
        Protocol.Reload message = new Protocol.Reload("large", Collections.emptyList(), Collections.emptyList(),
            new int[0], new byte[Protocol.MAX_FRAME_SIZE]);
        ByteArrayOutputStream wire = new ByteArrayOutputStream();
        try { Protocol.write(wire, message); fail("Expected preflight rejection"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("budget")); }
        assertEquals(0, wire.size());
    }

    @Test public void streamedEncodingKeepsProtocolThreeUtf8BytesIncludingMalformedSurrogates() throws Exception {
        for (String text : new String[]{"plain", "中文😀", "\uD800x\uDC00", "\uD800\uD800\uDC00", ""}) {
            ByteArrayOutputStream actual = new ByteArrayOutputStream();
            Protocol.write(actual, new Protocol.UIException(text, null));
            ByteArrayOutputStream expected = new ByteArrayOutputStream();
            byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
            DataOutputStream out = new DataOutputStream(expected);
            out.writeInt(Protocol.UI_EXCEPTION); out.writeInt(6 + encoded.length);
            out.writeBoolean(true); out.writeInt(encoded.length); out.write(encoded); out.writeBoolean(false);
            assertArrayEquals(expected.toByteArray(), actual.toByteArray());
        }
    }

    @Test public void streamedParserWorksWithZeroAvailableAndLeavesNextFrameUntouched() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Protocol.write(bytes, new Protocol.Reload("stream", Collections.singletonList(
            new Protocol.ClassDefinition("A", new byte[]{1, 2, 3}, false)), Collections.emptyList(), new int[]{4}, new byte[]{5, 6}));
        Protocol.write(bytes, new Protocol.UIRendered("next", 7));
        InputStream in = new FilterInputStream(new ByteArrayInputStream(bytes.toByteArray())) {
            @Override public int available() { return 0; }
            @Override public int read(byte[] b, int off, int len) throws IOException { return super.read(b, off, Math.min(len, 1)); }
        };
        Protocol.Reload decoded = (Protocol.Reload) Protocol.read(in);
        assertArrayEquals(new byte[]{1, 2, 3}, decoded.definitions.get(0).dex);
        assertArrayEquals(new byte[]{5, 6}, decoded.resourceApk);
        assertEquals("next", ((Protocol.UIRendered) Protocol.read(in)).reloadRequestId);
        assertNull(Protocol.read(in));
    }

    @Test public void partialHeadersAndPayloadsAreNotCleanEof() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Protocol.write(bytes, new Protocol.UIRendered("event", 1));
        byte[] frame = bytes.toByteArray();
        for (int size : new int[]{1, 3, 7, frame.length - 1}) {
            try { Protocol.read(new ByteArrayInputStream(frame, 0, size)); fail("Expected truncated frame"); }
            catch (EOFException expected) { }
        }
    }

    @Test public void excessiveCollectionsAreRejectedBeforeElementAllocationOrTransmission() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(header(Protocol.RELOAD, 1024 * 1024));
        out.writeInt(0); // empty request ID
        out.writeInt(Protocol.MAX_COLLECTION_SIZE + 1);
        try { Protocol.read(new ByteArrayInputStream(bytes.toByteArray())); fail("Expected count rejection"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("count")); }
        bytes.reset();
        Protocol.Reload many = new Protocol.Reload("groups", Collections.emptyList(), Collections.emptyList(),
            new int[Protocol.MAX_COLLECTION_SIZE + 1]);
        try { Protocol.write(bytes, many); fail("Expected count rejection"); }
        catch (IOException expected) { assertTrue(expected.getMessage().contains("count")); }
        assertEquals(0, bytes.size());
    }

    @Test public void diagnosticsBoundUntrustedIdsAndDoNotInjectLogLines() {
        TransportFailure failure = new TransportFailure(TransportFailure.Stage.ENQUEUE,
            TransportFailure.Reason.MESSAGE_REJECTED, new String(new char[1000]).replace('\0', 'x') + "\n",
            "oversize\r\nrequest", null);
        assertTrue(failure.requestId.length() <= 131);
        assertFalse(failure.getMessage().contains("\n"));
        assertFalse(failure.getMessage().contains("\r"));
    }

    @Test(timeout = 15_000) public void validLargeHeaderDoesNotAllocateItsAdvertisedBody() throws Exception {
        String javaExecutable = new File(System.getProperty("java.home"), "bin/java").getPath();
        String classpath = new File(Protocol.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath()
            + File.pathSeparator + new File(ProtocolStreamingProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getPath();
        Process process = new ProcessBuilder(javaExecutable, "-Xmx24m", "-cp", classpath,
            ProtocolStreamingProbe.class.getName()).redirectErrorStream(true).start();
        try {
            assertTrue("Low-heap probe must finish", process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[1024];
            int count;
            while ((count = process.getInputStream().read(buffer)) >= 0) output.write(buffer, 0, count);
            assertEquals(output.toString("UTF-8"), 0, process.exitValue());
            assertTrue(output.toString("UTF-8").contains("PASS: no full-frame allocation"));
        } finally { process.destroyForcibly(); }
    }
}
