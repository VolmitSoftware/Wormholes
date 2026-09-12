package art.arcane.wormholes.door;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PocketInstancesTest {
    private static final long MINUTE = 60_000L;

    @TempDir
    Path temporaryDirectory;

    @Test
    void aTemplateIsOnlyInstancedWhenItsSidecarSaysSo() throws IOException {
        Path templates = Files.createDirectories(temporaryDirectory.resolve("pockets/templates"));
        Files.writeString(templates.resolve("dungeon.nbt"), "x");
        Files.writeString(templates.resolve("dungeon.toml"), "instanced = true\n");
        Files.writeString(templates.resolve("lobby.nbt"), "x");

        PocketInstances instances = instances();

        assertTrue(instances.isInstanced("dungeon"));
        assertFalse(instances.isInstanced("lobby"), "no sidecar means a shared room");
        assertFalse(instances.isInstanced("missing"));
    }

    @Test
    void everyTravelerGetsTheirOwnCopyOfAnInstancedTemplateAndComesBackToIt() {
        UUID first = new UUID(0, 1100);
        UUID second = new UUID(0, 1101);

        PocketBinding firstBinding = PocketInstances.bindingFor("dungeon", first);
        PocketBinding secondBinding = PocketInstances.bindingFor("dungeon", second);

        assertEquals(firstBinding, PocketInstances.bindingFor("dungeon", first), "returning is not a new copy");
        assertNotEquals(firstBinding, secondBinding);
        assertNotEquals(firstBinding, PocketInstances.bindingFor("arena", first));
        assertEquals(PocketBindingKind.PERSONAL, firstBinding.kind());
    }

    @Test
    void anOnEmptyInstanceResetsTheMomentItsLastOccupantLeaves() {
        PocketInstanceInfo info = info("on-empty", 0L, 5 * MINUTE);

        assertFalse(PocketInstances.shouldReset(info, true, 10 * MINUTE, 600));
        assertTrue(PocketInstances.shouldReset(info, false, 5 * MINUTE, 600));
    }

    @Test
    void aTimerInstanceWaitsOutItsIdleWindowBeforeResetting() {
        PocketInstanceInfo info = info("timer", 0L, 5 * MINUTE);

        assertFalse(PocketInstances.shouldReset(info, true, 60 * MINUTE, 600));
        assertFalse(PocketInstances.shouldReset(info, false, 5 * MINUTE + 599_000L, 600));
        assertTrue(PocketInstances.shouldReset(info, false, 5 * MINUTE + 600_000L, 600));
    }

    /**
     * The sweep case from the headline review: an idle on-empty instance was wiped and re-pasted every
     * 20 seconds forever, because nothing recorded that the reset had already run.
     */
    @Test
    void anIdleInstanceIsResetOncePerVisitRatherThanOncePerSweep() {
        PocketInstanceInfo visited = info("on-empty", 0L, 5 * MINUTE);

        assertTrue(PocketInstances.shouldReset(visited, false, 6 * MINUTE, 600));

        PocketInstanceInfo settled = visited.withLastReset(6 * MINUTE);
        assertFalse(PocketInstances.shouldReset(settled, false, 7 * MINUTE, 600));
        assertFalse(PocketInstances.shouldReset(settled, false, 600 * MINUTE, 600));

        PocketInstanceInfo revisited = settled.withLastOccupied(601 * MINUTE);
        assertTrue(PocketInstances.shouldReset(revisited, false, 602 * MINUTE, 600));
    }

    @Test
    void aTimerInstanceAlsoResetsOnlyOncePerVisit() {
        PocketInstanceInfo settled = info("timer", 0L, 5 * MINUTE).withLastReset(6 * MINUTE);

        assertFalse(PocketInstances.shouldReset(settled, false, 600 * MINUTE, 600));

        PocketInstanceInfo revisited = settled.withLastOccupied(601 * MINUTE);
        assertFalse(PocketInstances.shouldReset(revisited, false, 601 * MINUTE + 599_000L, 600));
        assertTrue(PocketInstances.shouldReset(revisited, false, 601 * MINUTE + 600_000L, 600));
    }

    @Test
    void aManualInstanceIsOnlyEverResetByAnOperator() {
        PocketInstanceInfo info = info("manual", 0L, 5 * MINUTE);

        assertFalse(PocketInstances.shouldReset(info, false, 5 * MINUTE + 600_000L, 600));
        assertFalse(PocketInstances.shouldReset(info, true, 5 * MINUTE, 600));
    }

    @Test
    void anUnknownResetPolicyFallsBackToTheSafestOneRatherThanFailing() {
        PocketInstanceInfo info = info("whenever", 0L, 0L);

        assertFalse(PocketInstances.shouldReset(info, false, 10 * MINUTE, 600),
            "an unreadable policy never wipes somebody's room");
    }

    @Test
    void theOldestEmptyInstancesAreEvictedFirstAndOccupiedOnesAreLeftAlone() {
        PocketSpace oldestEmpty = instance(1100, "dungeon", 1L, MINUTE);
        PocketSpace newerEmpty = instance(1101, "dungeon", 2L, 3 * MINUTE);
        PocketSpace newestEmpty = instance(1102, "dungeon", 3L, 5 * MINUTE);
        PocketSpace occupied = instance(1103, "dungeon", 0L, 0L);
        List<PocketSpace> live = List.of(newestEmpty, occupied, oldestEmpty, newerEmpty);

        List<PocketSpace> evicted = PocketInstances.evictionOrder(live, Set.of(occupied.spaceId()), 2);

        assertEquals(List.of(oldestEmpty, newerEmpty), evicted);
        assertEquals(List.of(), PocketInstances.evictionOrder(live, Set.of(occupied.spaceId()), 4));
        assertEquals(List.of(), PocketInstances.evictionOrder(List.of(), Set.of(), 1));
    }

    @Test
    void evictionNeverTakesMoreThanTheEmptyInstancesItHas() {
        PocketSpace empty = instance(1110, "dungeon", 1L, MINUTE);
        PocketSpace occupiedA = instance(1111, "dungeon", 2L, MINUTE);
        PocketSpace occupiedB = instance(1112, "dungeon", 3L, MINUTE);

        List<PocketSpace> evicted = PocketInstances.evictionOrder(
            List.of(empty, occupiedA, occupiedB), Set.of(occupiedA.spaceId(), occupiedB.spaceId()), 1);

        assertEquals(List.of(empty), evicted);
    }

    @Test
    void aSnapshotNameNeverEscapesItsPocketFolder() {
        PocketSnapshots snapshots = new PocketSnapshots(temporaryDirectory, new RecordingStructureIo());
        UUID spaceId = new UUID(0, 1120);

        assertTrue(snapshots.file(spaceId, "latest").startsWith(snapshots.directory(spaceId)));
        assertThrows(IllegalArgumentException.class, () -> snapshots.file(spaceId, "../escape"));
        assertThrows(IllegalArgumentException.class, () -> snapshots.file(spaceId, "nested/name"));
        assertThrows(IllegalArgumentException.class, () -> snapshots.file(spaceId, "  "));
    }

    private PocketInstances instances() {
        return new PocketInstances(
            new PocketTemplateService(temporaryDirectory, () -> "pockets/templates", new RecordingStructureIo()));
    }

    private static PocketInstanceInfo info(String policy, long createdAt, long lastOccupied) {
        return new PocketInstanceInfo("dungeon", PocketBinding.personal(new UUID(0, 1)),
            createdAt, policy, lastOccupied);
    }

    private static PocketSpace instance(int seed, String template, long slot, long lastOccupied) {
        PocketBinding binding = PocketBinding.personal(new UUID(0, seed));
        return new PocketSpace(
            PocketAllocator.spaceIdFor(binding), binding, slot,
            PocketAllocator.CHUNK_CENTER_OFFSET, PocketAllocator.DEFAULT_CENTER_Y,
            PocketAllocator.CHUNK_CENTER_OFFSET, PocketShell.defaults(),
            template, PocketRules.defaults(), PocketRoster.empty(), List.of(),
            new PocketInstanceInfo(template, binding, 0L, "on-empty", lastOccupied));
    }

    /** A structure store that never has anything; the instancing decisions never read one. */
    private static final class RecordingStructureIo implements StructureIo {
        @Override
        public java.util.Optional<org.bukkit.structure.Structure> load(Path file) {
            return java.util.Optional.empty();
        }

        @Override
        public void save(org.bukkit.structure.Structure structure, Path file) {
        }
    }
}
