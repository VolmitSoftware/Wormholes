package art.arcane.wormholes.network;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

final class TestPorts {
    private static final Set<Integer> ISSUED = ConcurrentHashMap.newKeySet();

    private TestPorts() {
    }

    static int free() throws IOException {
        for (int attempt = 0; attempt < 64; attempt++) {
            int candidate;
            try (ServerSocket socket = new ServerSocket(0)) {
                candidate = socket.getLocalPort();
            }
            if (ISSUED.add(candidate)) {
                return candidate;
            }
        }
        throw new IOException("Could not allocate a port that was not already handed to another test");
    }
}
