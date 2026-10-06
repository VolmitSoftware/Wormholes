package art.arcane.wormholes.door;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import art.arcane.optics.math.Vec3d;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DoorArrivalSearchTest {
    @Test
    void preservesExactStoredPointWhenSafe() {
        Vec3d point = new Vec3d(-0.25, 64.5, 8.75);
        assertEquals(Optional.of(point), DoorArrivals.findSafeNear(point, 3, candidate -> true));
    }

    @Test
    void searchesNearestRingWithOriginalVerticalPriority() {
        List<Vec3d> visited = new ArrayList<>();
        Vec3d expected = new Vec3d(-1.5, 63, 7.5);
        Optional<Vec3d> result = DoorArrivals.findSafeNear(new Vec3d(-0.25, 64, 8.75), 3, candidate -> {
            visited.add(candidate);
            return candidate.equals(expected);
        });
        assertEquals(Optional.of(expected), result);
        assertEquals(List.of(new Vec3d(-0.25, 64, 8.75), new Vec3d(-1.5, 64, 7.5),
            new Vec3d(-1.5, 65, 7.5), expected), visited);
    }

    @Test
    void exhaustedSearchDoesNotLeaveConfiguredRadius() {
        Vec3d point = new Vec3d(0.5, 64, 0.5);
        List<Vec3d> visited = new ArrayList<>();
        assertTrue(DoorArrivals.findSafeNear(point, 1, candidate -> {
            visited.add(candidate);
            return false;
        }).isEmpty());
        assertEquals(41, visited.size());
        assertTrue(visited.stream().allMatch(candidate -> Math.abs(candidate.x() - point.x()) <= 1
            && Math.abs(candidate.z() - point.z()) <= 1));
    }
}
