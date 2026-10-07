package art.arcane.wormholes.network;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class TestPorts {
    private static final Set<Integer> ISSUED = ConcurrentHashMap.newKeySet();

    private TestPorts() {
    }

    public static int free() throws IOException {
        for (int attempt = 0; attempt < 64; attempt++) {
            int candidate = probe();
            if (ISSUED.add(candidate)) {
                return candidate;
            }
        }
        throw new IOException("Could not allocate a port that was not already handed to another test");
    }

    static int probe() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            return socket.getLocalPort();
        }
    }
}
