package org.jetbrains.compose.reload.android.protocol;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class ProtocolTest {
    private static Protocol.Message roundTrip(Protocol.Message message) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Protocol.write(out, message);
        return Protocol.read(new ByteArrayInputStream(out.toByteArray()));
    }

    @Test
    public void reload() throws IOException {
        Protocol.Reload reload = (Protocol.Reload) roundTrip(new Protocol.Reload(
            "id",
            Arrays.asList(
                new Protocol.ClassDefinition("a.A", new byte[]{1, 2, 3}, false),
                new Protocol.ClassDefinition("a.B$$ExternalSyntheticLambda0", new byte[0], true)
            ),
            Collections.singletonList("a.A"),
            new int[]{42, -7}
        ));
        assertEquals("id", reload.reloadRequestId);
        assertEquals(2, reload.definitions.size());
        assertEquals("a.A", reload.definitions.get(0).className);
        assertArrayEquals(new byte[]{1, 2, 3}, reload.definitions.get(0).dex);
        assertFalse(reload.definitions.get(0).staticsOnly);
        assertTrue(reload.definitions.get(1).staticsOnly);
        assertEquals(Collections.singletonList("a.A"), reload.reinitializeStatics);
        assertArrayEquals(new int[]{42, -7}, reload.invalidatedGroupKeys);
    }

    @Test
    public void reloadResult() throws IOException {
        Protocol.ReloadResult result = (Protocol.ReloadResult) roundTrip(new Protocol.ReloadResult(
            "id", false, true, false, "boom",
            Protocol.StackFrame.of(new IllegalStateException("outer", new RuntimeException("cause")))
        ));
        assertFalse(result.success);
        assertTrue(result.classesAdded);
        assertFalse(result.classesRedefined);
        assertEquals("boom", result.errorMessage);
        assertTrue(result.errorStacktrace.size() > 1);
        assertEquals(ProtocolTest.class.getName(), result.errorStacktrace.get(0).className);
    }

    @Test
    public void nullableFields() throws IOException {
        Protocol.UIRendered rendered = (Protocol.UIRendered) roundTrip(new Protocol.UIRendered(null, 3));
        assertNull(rendered.reloadRequestId);
        assertEquals(3, rendered.iteration);

        Protocol.Hello hello = (Protocol.Hello) roundTrip(new Protocol.Hello(Protocol.VERSION, 12, "app", null));
        assertNull(hello.runtimeError);
        assertEquals("app", hello.packageName);
    }

    @Test
    public void endOfStream() throws IOException {
        assertNull(Protocol.read(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    public void rejectsOversizedCounts() throws IOException {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        DataOutputStream data = new DataOutputStream(payload);
        data.writeInt(2); // reloadRequestId length
        data.write(new byte[]{'i', 'd'});
        data.writeInt(Integer.MAX_VALUE); // definitions count

        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(frame);
        out.writeInt(Protocol.RELOAD);
        out.writeInt(payload.size());
        payload.writeTo(out);

        try {
            Protocol.read(new ByteArrayInputStream(frame.toByteArray()));
            fail("Expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("count"));
        }
    }

    @Test
    public void rejectsWrongMagic() {
        try {
            Protocol.readHandshake(new ByteArrayInputStream(new byte[]{0, 0, 0, 1, 0, 0, 0, 1}));
            fail("Expected IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("magic"));
        }
    }
}
