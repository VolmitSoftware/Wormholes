package art.arcane.wormholes.door;

import org.bukkit.structure.Structure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PocketSnapshotsTest {
    private static final UUID SPACE_ID = new UUID(0, 1200);

    @TempDir
    Path temporaryDirectory;

    @Test
    void aSnapshotIsFiledUnderItsOwnPocketSoTwoPocketsNeverShareOne() {
        PocketSnapshots snapshots = new PocketSnapshots(temporaryDirectory, new MapStructureIo());
        UUID other = new UUID(0, 1201);

        Path mine = snapshots.file(SPACE_ID, PocketSnapshots.LATEST);
        Path theirs = snapshots.file(other, PocketSnapshots.LATEST);

        assertEquals("latest.nbt", mine.getFileName().toString());
        assertTrue(mine.startsWith(temporaryDirectory.resolve(PocketSnapshots.FOLDER).resolve(SPACE_ID.toString())));
        assertEquals(0, mine.compareTo(mine));
        assertTrue(!mine.equals(theirs));
    }

    @Test
    void savingThenLoadingGivesBackTheSameStructure() throws IOException {
        MapStructureIo io = new MapStructureIo();
        PocketSnapshots snapshots = new PocketSnapshots(temporaryDirectory, io);
        Structure captured = structure();

        snapshots.save(SPACE_ID, PocketSnapshots.LATEST, captured);

        assertEquals(Optional.of(captured), snapshots.load(SPACE_ID, PocketSnapshots.LATEST));
        assertEquals(Optional.empty(), snapshots.load(SPACE_ID, "before-the-fire"));
    }

    @Test
    void onlyStructureFilesAreListedAndTheirExtensionIsStripped() throws IOException {
        Path directory = Files.createDirectories(
            temporaryDirectory.resolve(PocketSnapshots.FOLDER).resolve(SPACE_ID.toString()));
        Files.writeString(directory.resolve("latest.nbt"), "x");
        Files.writeString(directory.resolve("before-the-fire.nbt"), "x");
        Files.writeString(directory.resolve("notes.txt"), "x");

        PocketSnapshots snapshots = new PocketSnapshots(temporaryDirectory, new MapStructureIo());

        assertEquals(List.of("before-the-fire", "latest"), snapshots.names(SPACE_ID));
        assertEquals(List.of(), snapshots.names(new UUID(0, 1202)));
    }

    @Test
    void aSnapshotNameIsNeverAllowedToReachOutsideItsFolder() {
        PocketSnapshots snapshots = new PocketSnapshots(temporaryDirectory, new MapStructureIo());

        assertThrows(IllegalArgumentException.class, () -> snapshots.file(SPACE_ID, "../../state"));
        assertThrows(IllegalArgumentException.class, () -> snapshots.file(SPACE_ID, "sub/dir"));
        assertThrows(NullPointerException.class, () -> snapshots.file(SPACE_ID, null));
    }

    private static Structure structure() {
        return (Structure) Proxy.newProxyInstance(
            PocketSnapshotsTest.class.getClassLoader(),
            new Class<?>[]{Structure.class},
            (instance, method, arguments) -> null);
    }

    /** An in-memory structure store, so a save is observable without a server. */
    private static final class MapStructureIo implements StructureIo {
        private final Map<Path, Structure> stored = new LinkedHashMap<>();

        @Override
        public Optional<Structure> load(Path file) {
            return Optional.ofNullable(stored.get(file));
        }

        @Override
        public void save(Structure structure, Path file) {
            stored.put(file, structure);
        }
    }
}
