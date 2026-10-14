package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReferenceArray;

import static net.minecraft.world.level.chunk.status.ChunkStatus.FULL;

final class ResidentLevel {
    static final int LIGHT_SECTION_BYTES = 2 * 2048;

    private final ClientLevel level;
    private final TravelMessage.TravelWorld world;
    private int handle;
    private int viewRadius;
    private TravelMessage.TravelCoordinate center;
    private long used;

    ResidentLevel(ClientLevel level, TravelMessage.TravelWorld world) {
        this.level = level;
        this.world = world;
    }

    static void discardEntities(ClientLevel level) {
        List<Entity> entities = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            entities.add(entity);
        }
        for (Entity entity : entities) {
            level.removeEntity(entity.getId(), Entity.RemovalReason.DISCARDED);
        }
    }

    static long estimate(ClientLevel level) {
        AtomicReferenceArray<LevelChunk> columns = ((PreparedChunkColumns) level.getChunkSource()).wormholes$columns();
        if (columns == null) {
            return 0L;
        }
        long bytes = 0L;
        for (int index = 0; index < columns.length(); index++) {
            LevelChunk column = columns.get(index);
            if (column == null) {
                continue;
            }
            for (LevelChunkSection section : column.getSections()) {
                bytes += section.getSerializedSize() + LIGHT_SECTION_BYTES;
            }
        }
        return bytes;
    }

    ClientLevel level() {
        return level;
    }

    TravelMessage.TravelWorld world() {
        return world;
    }

    int handle() {
        return handle;
    }

    boolean bound() {
        return handle != 0;
    }

    long used() {
        return used;
    }

    int viewRadius() {
        return viewRadius;
    }

    TravelMessage.TravelCoordinate center() {
        return center;
    }

    void bind(TravelMessage.RemoteLevelOpen open, long stamp) {
        handle = open.levelHandle();
        viewRadius = open.viewRadius();
        center = open.center();
        used = stamp;
        level.getChunkSource().updateViewCenter(center.x(), center.z());
        level.setRainLevel(open.environment().sky().rain());
        level.setThunderLevel(open.environment().sky().thunder());
    }

    void unbind(long stamp) {
        handle = 0;
        used = stamp;
    }

    void touch(long stamp) {
        used = stamp;
    }

    boolean overlaps(TravelMessage.RemoteLevelOpen open) {
        if (!world.equals(open.world())) {
            return false;
        }
        int radius = open.viewRadius();
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                if (level.getChunkSource().getChunk(open.center().x() + dx, open.center().z() + dz, FULL, false) != null) {
                    return true;
                }
            }
        }
        return false;
    }
}
