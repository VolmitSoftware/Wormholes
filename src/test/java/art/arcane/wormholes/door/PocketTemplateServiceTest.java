package art.arcane.wormholes.door;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PocketTemplateServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void templatesAreListedByFileNameWithoutTheStructureExtension() throws IOException {
        Path templates = Files.createDirectories(temporaryDirectory.resolve("pockets/templates"));
        Files.writeString(templates.resolve("dungeon.nbt"), "x");
        Files.writeString(templates.resolve("arena.nbt"), "x");
        Files.writeString(templates.resolve("notes.txt"), "x");

        PocketTemplateService service = service();

        assertEquals(List.of("arena", "dungeon"), service.names());
        assertTrue(service.exists("dungeon"));
        assertFalse(service.exists("notes"));
        assertFalse(service.exists("missing"));
    }

    @Test
    void aTemplateNameNeverEscapesTheTemplatesDirectory() throws IOException {
        Files.createDirectories(temporaryDirectory.resolve("pockets/templates"));
        PocketTemplateService service = service();

        assertThrows(IllegalArgumentException.class, () -> service.exists("../secrets"));
        assertThrows(IllegalArgumentException.class, () -> service.exists("nested/dungeon"));
        assertThrows(IllegalArgumentException.class, () -> service.exists(""));
    }

    @Test
    void aTemplateIsAnchoredAtTheInteriorCorner() {
        PocketLayout layout = new PocketLayout(space(PocketShell.defaults().withSize(16)));

        PocketTemplateService.Placement small = PocketTemplateService.fit(layout, 4, 5, 6);
        assertEquals(layout.minX() + 1, small.originX());
        assertEquals(layout.minY() + 1, small.originY());
        assertEquals(layout.minZ() + 1, small.originZ());
        assertEquals(4, small.sizeX());
        assertEquals(5, small.sizeY());
        assertEquals(6, small.sizeZ());

        // a 16-block shell leaves a 14-block interior on every axis
        PocketTemplateService.Placement exact = PocketTemplateService.fit(layout, 14, 14, 14);
        assertEquals(layout.maxX() - 1, exact.originX() + exact.sizeX() - 1);
        assertEquals(layout.maxY() - 1, exact.originY() + exact.sizeY() - 1);
        assertEquals(layout.maxZ() - 1, exact.originZ() + exact.sizeZ() - 1);
    }

    /**
     * Structure.place takes no bounding box, so a truncated record and a whole-structure write was the
     * one way an authored file could reach past the shell and into the pocket next door.
     */
    @Test
    void aTemplateLargerThanTheRoomIsRefusedRatherThanTruncated() {
        PocketLayout layout = new PocketLayout(space(PocketShell.defaults().withSize(16)));

        assertTrue(PocketTemplateService.fit(layout, 15, 14, 14).isEmpty());
        assertTrue(PocketTemplateService.fit(layout, 14, 14, 15).isEmpty());
        assertTrue(PocketTemplateService.fit(layout, 64, 64, 64).isEmpty());
        assertFalse(PocketTemplateService.fit(layout, 14, 14, 14).isEmpty());
    }

    @Test
    void anEmptyStructureFitsAsNothingInsteadOfANegativeBox() {
        PocketLayout layout = new PocketLayout(space(PocketShell.defaults()));

        PocketTemplateService.Placement empty = PocketTemplateService.fit(layout, 0, 0, 0);

        assertEquals(0, empty.sizeX());
        assertEquals(0, empty.sizeY());
        assertEquals(0, empty.sizeZ());
        assertTrue(empty.isEmpty());
    }

    @Test
    void theInteriorCaptureBoxExcludesTheProtectedShell() {
        PocketLayout layout = new PocketLayout(space(PocketShell.defaults().withSize(16)));

        PocketTemplateService.Placement interior = PocketTemplateService.interior(layout);

        assertEquals(layout.minX() + 1, interior.originX());
        assertEquals(layout.minY() + 1, interior.originY());
        assertEquals(layout.minZ() + 1, interior.originZ());
        assertEquals(14, interior.sizeX());
        assertEquals(14, interior.sizeY());
        assertEquals(14, interior.sizeZ());
    }

    @Test
    void loadingAMissingTemplateAnswersEmptyRatherThanFailing() throws IOException {
        Files.createDirectories(temporaryDirectory.resolve("pockets/templates"));

        assertEquals(Optional.empty(), service().load("dungeon"));
    }

    private PocketTemplateService service() {
        return new PocketTemplateService(temporaryDirectory, () -> "pockets/templates", new EmptyStructureIo());
    }

    private static PocketSpace space(PocketShell shell) {
        PocketBinding binding = PocketBinding.personal(new UUID(0, 950));
        return new PocketSpace(
            PocketAllocator.spaceIdFor(binding), binding, 0L,
            PocketAllocator.CHUNK_CENTER_OFFSET, PocketAllocator.DEFAULT_CENTER_Y,
            PocketAllocator.CHUNK_CENTER_OFFSET, shell);
    }

    /** A structure store with nothing in it; these tests never read or write one. */
    private static final class EmptyStructureIo implements StructureIo {
        @Override
        public java.util.Optional<org.bukkit.structure.Structure> load(java.nio.file.Path file) {
            return java.util.Optional.empty();
        }

        @Override
        public void save(org.bukkit.structure.Structure structure, java.nio.file.Path file) {
        }
    }
}
