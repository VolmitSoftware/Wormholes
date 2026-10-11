package art.arcane.wormholes.network;

import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

import java.io.IOException;
import java.net.BindException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.verify;

class TcpPeerTransportTest {
    @Test
    void repeatedOccupiedPortBindsCloseEveryFailedSocket() throws Exception {
        List<ServerSocket> allocated = new ArrayList<>();
        try (ServerSocket occupied = new ServerSocket()) {
            occupied.bind(new InetSocketAddress("127.0.0.1", 0));
            for (int index = 0; index < 64; index++) {
                allocated.add(new ServerSocket());
            }
            try (MockedConstruction<ServerSocket> sockets = mockConstruction(ServerSocket.class, (socket, context) -> {
                ServerSocket delegate = allocated.get(context.getCount() - 1);
                doAnswer(call -> {
                    delegate.setReuseAddress(call.getArgument(0, Boolean.class));
                    return null;
                }).when(socket).setReuseAddress(anyBoolean());
                doAnswer(call -> {
                    delegate.bind(call.getArgument(0, SocketAddress.class));
                    return null;
                }).when(socket).bind(any(SocketAddress.class));
                doAnswer(call -> {
                    delegate.close();
                    return null;
                }).when(socket).close();
            })) {
                for (ServerSocket delegate : allocated) {
                    assertThrows(BindException.class, () -> TcpPeerTransport.bind("127.0.0.1", occupied.getLocalPort()));
                    assertTrue(delegate.isClosed());
                }
                assertEquals(allocated.size(), sockets.constructed().size());
                for (ServerSocket socket : sockets.constructed()) {
                    verify(socket).close();
                }
            }
        } finally {
            for (ServerSocket socket : allocated) {
                socket.close();
            }
        }
    }

    @Test
    void bindFailureRetainsCloseFailureAsSuppressed() throws Exception {
        BindException bindFailure = new BindException("occupied");
        IOException closeFailure = new IOException("close failed");
        try (MockedConstruction<ServerSocket> sockets = mockConstruction(ServerSocket.class, (socket, context) -> {
            doThrow(bindFailure).when(socket).bind(any(SocketAddress.class));
            doThrow(closeFailure).when(socket).close();
        })) {
            BindException thrown = assertThrows(BindException.class, () -> TcpPeerTransport.bind("127.0.0.1", 25990));
            assertSame(bindFailure, thrown);
            assertEquals(1, thrown.getSuppressed().length);
            assertSame(closeFailure, thrown.getSuppressed()[0]);
            verify(sockets.constructed().getFirst()).close();
        }
    }

    @Test
    void invalidAddressClosesSocketAfterSetup() throws Exception {
        try (MockedConstruction<ServerSocket> sockets = mockConstruction(ServerSocket.class)) {
            assertThrows(IllegalArgumentException.class, () -> TcpPeerTransport.bind("127.0.0.1", -1));
            verify(sockets.constructed().getFirst()).close();
        }
    }
}
