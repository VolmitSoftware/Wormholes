package art.arcane.wormholes.modded.seamless;

import art.arcane.wormholes.network.client.TravelMessage;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongList;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.world.level.ChunkPos;

import java.util.ArrayList;
import java.util.List;

public final class RouteWindow {
    public static final int MAX_RADIUS = TravelMessage.MAX_REMOTE_VIEW_RADIUS;

    private final int centerX;
    private final int centerZ;
    private final int radius;
    private final LongList ordered;

    public RouteWindow(int centerX, int centerZ, int radius) {
        if (radius < 1 || radius > MAX_RADIUS) {
            throw new IllegalArgumentException("Route window radius " + radius);
        }
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radius = radius;
        this.ordered = order(centerX, centerZ, radius);
    }

    public int centerX() {
        return centerX;
    }

    public int centerZ() {
        return centerZ;
    }

    public int radius() {
        return radius;
    }

    public boolean contains(int chunkX, int chunkZ) {
        return ChunkTrackingView.isWithinDistance(centerX, centerZ, radius, chunkX, chunkZ, true);
    }

    public boolean contains(long key) {
        return contains(ChunkPos.getX(key), ChunkPos.getZ(key));
    }

    public boolean border(int chunkX, int chunkZ) {
        if (!contains(chunkX, chunkZ)) {
            return false;
        }
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                if (!contains(chunkX + offsetX, chunkZ + offsetZ)) {
                    return true;
                }
            }
        }
        return false;
    }

    public int minX() {
        return centerX - radius - 1;
    }

    public int maxX() {
        return centerX + radius + 1;
    }

    public int minZ() {
        return centerZ - radius - 1;
    }

    public int maxZ() {
        return centerZ + radius + 1;
    }

    public LongList keys() {
        return ordered;
    }

    public boolean sameShape(RouteWindow other) {
        return other != null && matches(other.centerX, other.centerZ, other.radius);
    }

    public boolean matches(int otherCenterX, int otherCenterZ, int otherRadius) {
        return otherCenterX == centerX && otherCenterZ == centerZ && otherRadius == radius;
    }

    public RouteWindow withRadius(int next) {
        return next == radius ? this : new RouteWindow(centerX, centerZ, next);
    }

    public ChunkTrackingView.Positioned view() {
        return new ChunkTrackingView.Positioned(new ChunkPos(centerX, centerZ), radius);
    }

    public List<TravelMessage.TravelCoordinate> coordinates() {
        List<TravelMessage.TravelCoordinate> coordinates = new ArrayList<>(ordered.size());
        for (int index = 0; index < ordered.size(); index++) {
            long key = ordered.getLong(index);
            coordinates.add(new TravelMessage.TravelCoordinate(ChunkPos.getX(key), ChunkPos.getZ(key)));
        }
        return coordinates;
    }

    public boolean within(ChunkTrackingView view) {
        for (int index = 0; index < ordered.size(); index++) {
            long key = ordered.getLong(index);
            if (!view.contains(ChunkPos.getX(key), ChunkPos.getZ(key))) {
                return false;
            }
        }
        return true;
    }

    private static LongList order(int centerX, int centerZ, int radius) {
        int extent = radius + 1;
        LongArrayList keys = new LongArrayList((extent * 2 + 1) * (extent * 2 + 1));
        for (int ring = 0; ring <= extent; ring++) {
            for (int offsetX = -ring; offsetX <= ring; offsetX++) {
                for (int offsetZ = -ring; offsetZ <= ring; offsetZ++) {
                    if (Math.max(Math.abs(offsetX), Math.abs(offsetZ)) != ring) {
                        continue;
                    }
                    if (ChunkTrackingView.isWithinDistance(centerX, centerZ, radius, centerX + offsetX, centerZ + offsetZ, true)) {
                        keys.add(ChunkPos.pack(centerX + offsetX, centerZ + offsetZ));
                    }
                }
            }
        }
        return LongList.of(keys.toLongArray());
    }
}
