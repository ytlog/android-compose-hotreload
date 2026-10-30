package org.jetbrains.compose.reload.android.protocol;

import org.junit.Test;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

public class RuntimeSessionTest {
    private static final Protocol.Hello HELLO = new Protocol.Hello(Protocol.VERSION, 42, "fixture.app", null);

    private static final class Peer implements AutoCloseable {
        final ServerSocket listener = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        final Socket client = new Socket(InetAddress.getLoopbackAddress(), listener.getLocalPort());
        final Socket server = listener.accept();
        final CountDownLatch closed = new CountDownLatch(1);
        final CompletableFuture<RuntimeSession> accepted = new CompletableFuture<>();
        final CountDownLatch writing = new CountDownLatch(1);
        final CountDownLatch largeWriteStarted = new CountDownLatch(1);
        final CountDownLatch unblock = new CountDownLatch(1);
        volatile boolean blockWrites;
        Peer(long handshake, long frame, long write, int capacity) throws IOException {
            client.setSoTimeout(5_000);
            server.setSendBufferSize(1024);
            Thread thread = new Thread(() -> {
                try {
                    OutputStream raw = server.getOutputStream();
                    OutputStream output = new OutputStream() {
                        @Override public void write(int value) throws IOException { write(new byte[]{(byte) value}); }
                        @Override public void write(byte[] bytes, int offset, int size) throws IOException {
                            if (blockWrites) {
                                writing.countDown();
                                try { unblock.await(); } catch (InterruptedException e) {
                                    Thread.currentThread().interrupt();
                                    throw new IOException(e);
                                }
                            }
                            if (size > 65536) largeWriteStarted.countDown();
                            raw.write(bytes, offset, size);
                        }
                    };
                    accepted.complete(RuntimeSession.accept(server.getInputStream(), output, () -> {
                        server.close(); unblock.countDown(); closed.countDown();
                    }, HELLO, handshake, frame, write, capacity));
                } catch (Throwable error) { accepted.completeExceptionally(error); }
            });
            thread.setDaemon(true);
            thread.start();
        }
        RuntimeSession handshake() throws Exception {
            Protocol.writeHandshake(client.getOutputStream());
            Protocol.readHandshake(client.getInputStream());
            assertTrue(Protocol.read(client.getInputStream()) instanceof Protocol.Hello);
            return accepted.get(5, TimeUnit.SECONDS);
        }
        @Override public void close() throws Exception {
            if (accepted.isDone() && !accepted.isCompletedExceptionally()) accepted.get().close();
            unblock.countDown(); server.close(); client.close(); listener.close();
        }
    }

    @Test(timeout = 10_000) public void silentHandshakeReleasesServer() throws Exception {
        try (Peer peer = new Peer(150, 500, 500, 8)) {
            assertTrue(peer.closed.await(5, TimeUnit.SECONDS));
            assertEquals(-1, peer.client.getInputStream().read());
            try { peer.accepted.get(5, TimeUnit.SECONDS); fail("Expected timeout"); }
            catch (java.util.concurrent.ExecutionException expected) {
                assertTrue(expected.getCause() instanceof SocketTimeoutException);
            }
        }
    }

    @Test(timeout = 10_000) public void partialHandshakeCannotOccupyServer() throws Exception {
        try (Peer peer = new Peer(150, 500, 500, 8)) {
            peer.client.getOutputStream().write(0x43);
            peer.client.getOutputStream().flush();
            assertTrue(peer.closed.await(5, TimeUnit.SECONDS));
            assertEquals(-1, peer.client.getInputStream().read());
        }
    }

    @Test(timeout = 10_000) public void idleSurvivesButPartialFrameExpires() throws Exception {
        try (Peer peer = new Peer(300, 150, 500, 8)) {
            RuntimeSession session = peer.handshake();
            Thread.sleep(400); // beyond both cancelled handshake and idle frame limits
            assertTrue(session.send(new Protocol.UIRendered("jb-request", 1)));
            assertEquals("jb-request", ((Protocol.UIRendered) Protocol.read(peer.client.getInputStream())).reloadRequestId);
            peer.client.getOutputStream().write(0);
            peer.client.getOutputStream().flush();
            try { session.read(); fail("Expected frame timeout"); }
            catch (SocketTimeoutException expected) { assertTrue(session.isClosed()); }
        }
    }

    @Test(timeout = 10_000) public void queuedEventsAndAcknowledgementsKeepOrderAndIdentity() throws Exception {
        try (Peer peer = new Peer(500, 500, 500, 8)) {
            RuntimeSession session = peer.handshake();
            String id = "17$official-request";
            Protocol.write(peer.client.getOutputStream(), new Protocol.Reload(id,
                Collections.emptyList(), Collections.emptyList(), new int[0]));
            assertEquals(id, ((Protocol.Reload) session.read()).reloadRequestId);
            assertTrue(session.send(new Protocol.ReloadResult(id, true, false, true, null, null)));
            assertTrue(session.send(new Protocol.UIRendered(id, 2)));
            assertEquals(id, ((Protocol.ReloadResult) Protocol.read(peer.client.getInputStream())).reloadRequestId);
            assertEquals(id, ((Protocol.UIRendered) Protocol.read(peer.client.getInputStream())).reloadRequestId);
        }
    }

    @Test(timeout = 10_000) public void blockedPeerDoesNotBlockUiAndWriteDeadlineClosesSession() throws Exception {
        try (Peer peer = new Peer(500, 500, 1_000, 8)) {
            RuntimeSession session = peer.handshake();
            peer.client.setReceiveBufferSize(1024);
            String large = new String(new char[8 * 1024 * 1024]).replace('\0', 'x');
            assertTrue(session.send(new Protocol.UIException(large, null)));
            // Callers can continue while the writer is stuck on a real socket.
            assertTrue(session.send(new Protocol.UIRendered("next", 1)));
            assertTrue("Must reach the real large socket write", peer.largeWriteStarted.await(5, TimeUnit.SECONDS));
            assertTrue(peer.closed.await(5, TimeUnit.SECONDS));
            assertTrue(session.isClosed());
            assertFalse(session.send(new Protocol.UIRendered("late", 2)));
        }
    }

    @Test(timeout = 10_000) public void queueOverflowDisconnectsAndCannotLeakIntoNextSession() throws Exception {
        try (Peer peer = new Peer(500, 500, 5_000, 1)) {
            RuntimeSession session = peer.handshake();
            peer.blockWrites = true;
            assertTrue(session.send(new Protocol.UIRendered("old", 1)));
            assertTrue(peer.writing.await(5, TimeUnit.SECONDS));
            assertTrue(session.send(new Protocol.UIRendered("old", 2)));
            assertFalse(session.send(new Protocol.UIRendered("overflow", 3)));
            assertTrue(peer.closed.await(5, TimeUnit.SECONDS));
            try (Peer next = new Peer(500, 500, 500, 8)) {
                RuntimeSession fresh = next.handshake();
                assertFalse(session.send(new Protocol.UIRendered("stale", 4)));
                assertTrue(fresh.send(new Protocol.UIRendered("new", 1)));
                assertEquals("new", ((Protocol.UIRendered) Protocol.read(next.client.getInputStream())).reloadRequestId);
            }
        }
    }
}
