package art.arcane.wormholes.network;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@Timeout(60)
class TestPortsTest {
    private static final int EPHEMERAL_RANGE_SWEEP = 20_000;
    private static final long CLAIMANT_EXIT_SECONDS = 10L;

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

    @Test
    void aPortClaimedByAnotherTestProcessIsNeverHandedOutHere() throws IOException, InterruptedException, URISyntaxException {
        Path classes = Path.of(TestPortsClaimant.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Process claimant = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Djava.io.tmpdir=" + System.getProperty("java.io.tmpdir"), "-cp", classes.toString(), TestPortsClaimant.class.getName())
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start();
        try (BufferedReader output = new BufferedReader(new InputStreamReader(claimant.getInputStream(), StandardCharsets.UTF_8))) {
            String line = output.readLine();
            assertNotNull(line, "the claimant process exited before claiming a port");
            int held = Integer.parseInt(line.trim());
            assertFalse(TestPorts.claim(held), "port " + held + " is claimed by another test process and its listener may bind it at any moment");
        } finally {
            claimant.getOutputStream().close();
            if (!claimant.waitFor(CLAIMANT_EXIT_SECONDS, TimeUnit.SECONDS)) {
                claimant.destroyForcibly();
            }
        }
    }

    @Test
    void aPortIsHandedOutOncePerProcess() throws IOException {
        int first = TestPorts.free();
        assertFalse(TestPorts.claim(first));
        assertNotEquals(first, TestPorts.free());
    }
}
