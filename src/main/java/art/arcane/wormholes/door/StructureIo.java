package art.arcane.wormholes.door;

import org.bukkit.Bukkit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Reads and writes vanilla structure files. Templates and snapshots share it, and tests swap it out
 * so the geometry and policy decisions run without a server.
 */
public interface StructureIo {
    Optional<org.bukkit.structure.Structure> load(Path file) throws IOException;

    void save(org.bukkit.structure.Structure structure, Path file) throws IOException;

    /** The server's own structure loader. */
    static StructureIo server() {
        return new StructureIo() {
            @Override
            public Optional<org.bukkit.structure.Structure> load(Path file) throws IOException {
                if (!Files.isRegularFile(file)) {
                    return Optional.empty();
                }
                return Optional.ofNullable(Bukkit.getStructureManager().loadStructure(file.toFile()));
            }

            @Override
            public void save(org.bukkit.structure.Structure structure, Path file) throws IOException {
                Files.createDirectories(file.getParent());
                Bukkit.getStructureManager().saveStructure(file.toFile(), structure);
            }
        };
    }
}
