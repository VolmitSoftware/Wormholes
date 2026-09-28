package art.arcane.wormholes.door;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class DoorArrivalSearchTest {
    @Test
    void preservesExactStoredPointWhenSafe() {
        DoorVec3 point = new DoorVec3(-0.25, 64.5, 8.75);
        assertEquals(Optional.of(point), DoorArrivals.findSafeNear(point, 3, candidate -> true));
    }

    @Test
    void searchesNearestRingWithOriginalVerticalPriority() {
        List<DoorVec3> visited = new ArrayList<>();
        DoorVec3 expected = new DoorVec3(-1.5, 63, 7.5);
        Optional<DoorVec3> result = DoorArrivals.findSafeNear(new DoorVec3(-0.25, 64, 8.75), 3, candidate -> {
            visited.add(candidate);
            return candidate.equals(expected);
        });
        assertEquals(Optional.of(expected), result);
        assertEquals(List.of(new DoorVec3(-0.25, 64, 8.75), new DoorVec3(-1.5, 64, 7.5),
            new DoorVec3(-1.5, 65, 7.5), expected), visited);
    }

    @Test
    void exhaustedSearchDoesNotLeaveConfiguredRadius() {
        DoorVec3 point = new DoorVec3(0.5, 64, 0.5);
        List<DoorVec3> visited = new ArrayList<>();
        assertTrue(DoorArrivals.findSafeNear(point, 1, candidate -> {
            visited.add(candidate);
            return false;
        }).isEmpty());
        assertEquals(41, visited.size());
        assertTrue(visited.stream().allMatch(candidate -> Math.abs(candidate.x() - point.x()) <= 1
            && Math.abs(candidate.z() - point.z()) <= 1));
    }
}
