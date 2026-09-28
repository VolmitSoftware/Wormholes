package art.arcane.wormholes.door;

import org.bukkit.Bukkit;
import org.bukkit.structure.Structure;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public enum BukkitStructureIo implements StructureIo<Structure> {
    INSTANCE;

    @Override
    public Optional<Structure> load(Path file) throws IOException {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        return Optional.ofNullable(Bukkit.getStructureManager().loadStructure(file.toFile()));
    }

    @Override
    public void save(Structure structure, Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Bukkit.getStructureManager().saveStructure(file.toFile(), structure);
    }
}
