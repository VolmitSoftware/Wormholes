package art.arcane.wormholes.network.client;

import art.arcane.wormholes.chunk.presend.ChunkCoordinate;
import art.arcane.wormholes.chunk.presend.ChunkPreSendPlanner;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;

public final class ClientTravelWindow {
    private static final int MAX_RADIUS = 16;
    private static final AtomicReferenceArray<List<ChunkCoordinate>> RELATIVE_WINDOWS = new AtomicReferenceArray<>(MAX_RADIUS + 1);

    private ClientTravelWindow() {
    }

    public static int radius(int renderDistance) {
        return (int) Math.clamp((long) renderDistance + 1L, 1L, MAX_RADIUS);
    }

    public static int count(int radius) {
        if (radius < 1 || radius > MAX_RADIUS) {
            throw new IllegalArgumentException("Prepared travel radius must be between 1 and " + MAX_RADIUS);
        }
        int diameter = radius * 2 + 1;
        return diameter * diameter;
    }

    public static List<ClientViewMessage.TravelCoordinate> coordinates(int centerX, int centerZ, int radius) {
        List<ClientViewMessage.TravelCoordinate> coordinates = new ArrayList<>(count(radius));
        int offsetX = centerX;
        int offsetZ = centerZ;
        List<ChunkCoordinate> ordered;
        if (centerX < Integer.MIN_VALUE + radius || centerX > Integer.MAX_VALUE - radius
            || centerZ < Integer.MIN_VALUE + radius || centerZ > Integer.MAX_VALUE - radius) {
            ordered = ChunkPreSendPlanner.ring(centerX, centerZ, radius);
            offsetX = 0;
            offsetZ = 0;
        } else {
            ordered = relativeWindow(radius);
        }
        for (ChunkCoordinate coordinate : ordered) {
            coordinates.add(new ClientViewMessage.TravelCoordinate(coordinate.x() + offsetX, coordinate.z() + offsetZ));
        }
        return List.copyOf(coordinates);
    }

    private static List<ChunkCoordinate> relativeWindow(int radius) {
        List<ChunkCoordinate> ordered = RELATIVE_WINDOWS.get(radius);
        if (ordered == null) {
            List<ChunkCoordinate> created = List.copyOf(ChunkPreSendPlanner.ring(0, 0, radius));
            ordered = RELATIVE_WINDOWS.compareAndSet(radius, null, created) ? created : RELATIVE_WINDOWS.get(radius);
        }
        return ordered;
    }
}
