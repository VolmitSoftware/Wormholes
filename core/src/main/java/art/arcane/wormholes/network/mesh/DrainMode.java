package art.arcane.wormholes.network.mesh;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Operator switch that takes this server out of destination rotation: inbound handoffs are denied
 * with a short retry and every beacon carries {@code drain = true}, while projections keep serving.
 * Persisted as the presence of mesh/drain.flag so a restart does not silently rejoin rotation.
 */
public final class DrainMode {
    private static final String FLAG_FILE = "drain.flag";

    private final Path flag;
    private volatile boolean draining;
    private volatile Runnable listener;

    private DrainMode(Path flag, boolean draining) {
        this.flag = flag;
        this.draining = draining;
    }

    public static DrainMode loadOrCreate(Path dataDirectory) throws IOException {
        Path meshDirectory = dataDirectory.resolve("mesh");
        Files.createDirectories(meshDirectory);
        Path flag = meshDirectory.resolve(FLAG_FILE);
        return new DrainMode(flag, Files.exists(flag));
    }

    public boolean isDraining() {
        return draining;
    }

    /** Runs after every state change (beacon push). */
    public void setListener(Runnable listener) {
        this.listener = listener;
    }

    public synchronized void set(boolean drain) throws IOException {
        if (draining == drain) {
            return;
        }
        if (drain) {
            Files.writeString(flag, "draining\n");
        } else {
            Files.deleteIfExists(flag);
        }
        draining = drain;
        Runnable active = listener;
        if (active != null) {
            active.run();
        }
    }
}
