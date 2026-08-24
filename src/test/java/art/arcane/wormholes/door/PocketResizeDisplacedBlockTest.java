package art.arcane.wormholes.door;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PocketResizeDisplacedBlockTest {
    @Test
    void slabEnumerationExactlyMatchesTheOriginalPredicateWithoutDuplicates() {
        List<ResizeCase> cases = List.of(
            new ResizeCase(16, 8),
            new ResizeCase(24, 17),
            new ResizeCase(32, 16),
            new ResizeCase(16, 16),
            new ResizeCase(8, 24)
        );
        for (ResizeCase resizeCase : cases) {
            PocketLayout previous = layout(resizeCase.previous());
            PocketLayout updated = layout(resizeCase.updated());
            Set<Coordinate> expected = originalSelection(previous, updated);
            Set<Coordinate> actual = new HashSet<>();

            PocketResizeService.forEachDisplacedBlock(previous, updated, (x, y, z) ->
                assertTrue(actual.add(new Coordinate(x, y, z)), "duplicate coordinate"));

            assertEquals(expected, actual, resizeCase.toString());
        }
    }

    @Test
    void maximumGrowthDoesNotWalkThePreviousVolume() {
        AtomicInteger visited = new AtomicInteger();

        PocketResizeService.forEachDisplacedBlock(layout(8), layout(128),
            (x, y, z) -> visited.incrementAndGet());

        assertEquals(0, visited.get());
    }

    private static Set<Coordinate> originalSelection(PocketLayout previous, PocketLayout updated) {
        Set<Coordinate> selected = new HashSet<>();
        for (int x = previous.minX(); x <= previous.maxX(); x++) {
            for (int y = previous.minY(); y <= previous.maxY(); y++) {
                for (int z = previous.minZ(); z <= previous.maxZ(); z++) {
                    boolean outside = !updated.contains(x, y, z);
                    if (outside || (updated.isShellBlock(x, y, z)
                        && previous.isInteriorBlock(x, y, z))) {
                        selected.add(new Coordinate(x, y, z));
                    }
                }
            }
        }
        return selected;
    }

    private static PocketLayout layout(int size) {
        PocketSpace space = new PocketSpace(
            new UUID(0L, 1L),
            PocketBinding.personal(new UUID(0L, 2L)),
            0L,
            8,
            64,
            8,
            new PocketShell(size, "SMOOTH_STONE", "CRIMSON_DOOR")
        );
        return new PocketLayout(space);
    }

    private record ResizeCase(int previous, int updated) {
    }

    private record Coordinate(int x, int y, int z) {
    }
}
