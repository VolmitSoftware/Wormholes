package art.arcane.wormholes.door;


import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads and writes vanilla structure files. Templates and snapshots share it, and tests swap it out
 * so the geometry and policy decisions run without a server.
 */
public interface StructureIo<S> {
    Optional<S> load(Path file) throws IOException;

    void save(S structure, Path file) throws IOException;

}
