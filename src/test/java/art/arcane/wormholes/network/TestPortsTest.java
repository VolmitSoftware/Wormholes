package art.arcane.wormholes.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

@Timeout(60)
class TestPortsTest {
    private static final int EPHEMERAL_RANGE_SWEEP = 20_000;

    @Test
    void probeNeverHandsOutAPortHeldByALoopbackOnlyListener() throws IOException {
        try (ServerSocket loopbackOnly = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            int held = loopbackOnly.getLocalPort();
            for (int probe = 0; probe < EPHEMERAL_RANGE_SWEEP; probe++) {
                assertNotEquals(held, TestPorts.probe(),
                    "a peer listener on this port would bind the wildcard while dials to 127.0.0.1 reach the loopback-only listener");
            }
        }
    }
}
