package art.arcane.wormholes.network.client;

import art.arcane.wormholes.chunk.presend.ChunkCoordinate;
import art.arcane.wormholes.chunk.presend.ChunkPreSendPlanner;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import art.arcane.optics.stream.ViewStreamLimits;

class ClientTravelWindowTest {
    @Test
    void negotiatedDistanceIncludesOneChunkHaloAndStaysInsideProtocolBounds() {
        assertEquals(1, ClientTravelWindow.radius(Integer.MIN_VALUE));
        assertEquals(1, ClientTravelWindow.radius(0));
        assertEquals(9, ClientTravelWindow.radius(8));
        assertEquals(16, ClientTravelWindow.radius(32));
        assertEquals(16, ClientTravelWindow.radius(Integer.MAX_VALUE));
        assertEquals(ViewStreamLimits.MAX_TRAVEL_CHUNKS, ClientTravelWindow.count(16));
        assertThrows(IllegalArgumentException.class, () -> ClientTravelWindow.count(0));
        assertThrows(IllegalArgumentException.class, () -> ClientTravelWindow.count(17));
    }

    @Test
    void manifestCapturesCollisionCenterFirstAndIncludesEveryHorizonColumnOnce() {
        List<ClientViewMessage.TravelCoordinate> coordinates = ClientTravelWindow.coordinates(-19, 37, 9);

        assertEquals(new ClientViewMessage.TravelCoordinate(-19, 37), coordinates.getFirst());
        assertEquals(361, coordinates.size());
        assertEquals(coordinates.size(), new HashSet<>(coordinates).size());
        assertTrue(coordinates.contains(new ClientViewMessage.TravelCoordinate(-28, 28)));
        assertTrue(coordinates.contains(new ClientViewMessage.TravelCoordinate(-10, 46)));
        for (ClientViewMessage.TravelCoordinate coordinate : coordinates.subList(0, 9)) {
            assertTrue(Math.abs(coordinate.x() + 19) <= 1);
            assertTrue(Math.abs(coordinate.z() - 37) <= 1);
        }
    }

    @Test
    void everySupportedRadiusPreservesPlannerOrderAcrossTranslatedAndOverflowingCenters() {
        int[][] centers = { { 0, 0 }, { -19, 37 }, { 47, -11 }, { -1_875_000, 1_875_000 },
            { Integer.MIN_VALUE, Integer.MAX_VALUE }, { Integer.MAX_VALUE, Integer.MIN_VALUE },
            { Integer.MIN_VALUE + 16, Integer.MAX_VALUE - 16 } };
        for (int radius = 1; radius <= 16; radius++) {
            for (int[] center : centers) {
                assertEquals(expected(center[0], center[1], radius), ClientTravelWindow.coordinates(center[0], center[1], radius));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> ClientTravelWindow.coordinates(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> ClientTravelWindow.coordinates(0, 0, 17));
    }

    @Test
    void concurrentWindowsRemainIndependentAndImmutable() throws Exception {
        List<Callable<Void>> requests = new ArrayList<>();
        for (int index = 0; index < 32; index++) {
            int centerX = index - 20;
            int centerZ = 31 - index;
            int radius = index % 16 + 1;
            requests.add(() -> {
                List<ClientViewMessage.TravelCoordinate> coordinates = ClientTravelWindow.coordinates(centerX, centerZ, radius);
                assertEquals(expected(centerX, centerZ, radius), coordinates);
                assertThrows(UnsupportedOperationException.class, coordinates::clear);
                return null;
            });
        }
        try (ExecutorService executor = Executors.newFixedThreadPool(8)) {
            for (Future<Void> completed : executor.invokeAll(requests)) {
                completed.get();
            }
        }
    }

    private static List<ClientViewMessage.TravelCoordinate> expected(int centerX, int centerZ, int radius) {
        List<ClientViewMessage.TravelCoordinate> expected = new ArrayList<>();
        for (ChunkCoordinate coordinate : ChunkPreSendPlanner.ring(centerX, centerZ, radius)) {
            expected.add(new ClientViewMessage.TravelCoordinate(coordinate.x(), coordinate.z()));
        }
        return expected;
    }
}
