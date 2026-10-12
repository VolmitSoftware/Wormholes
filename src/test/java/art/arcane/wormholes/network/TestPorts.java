package art.arcane.wormholes.network;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.BindException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class TestPorts {
    private static final int ATTEMPTS = 64;
    private static final int MIN_CANDIDATE_PORT = 1024;
    private static final int MAX_CANDIDATE_PORT = 32767;
    private static final long CLAIM_WIDTH = 1L;
    private static final Path CLAIMS = Path.of(System.getProperty("java.io.tmpdir"),
        "wormholes-test-ports-" + System.getProperty("user.name") + ".lock");
    private static final List<FileLock> HELD = new ArrayList<>();
    private static FileChannel claims;

    private TestPorts() {
    }

    public static synchronized int free() throws IOException {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            int candidate = ThreadLocalRandom.current().nextInt(MIN_CANDIDATE_PORT, MAX_CANDIDATE_PORT + 1);
            if (claim(candidate) && available(candidate)) {
                return candidate;
            }
        }
        throw new IOException("Could not claim a loopback port that no other test process holds; claims live in " + CLAIMS);
    }

    private static boolean available(int port) throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress("0.0.0.0", port));
            return true;
        } catch (BindException occupied) {
            return false;
        }
    }

    static synchronized boolean claim(int port) throws IOException {
        FileLock lock;
        try {
            lock = claims().tryLock(port, CLAIM_WIDTH, false);
        } catch (OverlappingFileLockException alreadyClaimedHere) {
            return false;
        }
        if (lock == null) {
            return false;
        }
        HELD.add(lock);
        return true;
    }

    static int probe() throws IOException {
        try (ServerSocket socket = new ServerSocket()) {
            socket.setReuseAddress(false);
            socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            return socket.getLocalPort();
        }
    }

    private static FileChannel claims() throws IOException {
        if (claims == null) {
            claims = FileChannel.open(CLAIMS, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        }
        return claims;
    }
}
