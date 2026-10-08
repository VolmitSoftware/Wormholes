package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.stream.EnvironmentState;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Face;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.shape.ShapeDescriptor;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongLinkedOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongIterable;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.RenderSectionRegion;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.Arrays;
import java.util.function.ObjLongConsumer;
import java.util.HashMap;
import art.arcane.wormholes.network.client.TravelMessage;

public final class ClientTravelScene implements PortalScene {
    private static final long SNAPSHOT_NANOS = 2_000_000L;
    private static final int MAX_SNAPSHOTS = 64;
    private ClientLevel level;
    private EnvironmentState environment;
    private ApertureDescriptor geometry;
    private final Long2ObjectOpenHashMap<RenderSectionRegion> regions = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet sections = new LongOpenHashSet();
    private final LongOpenHashSet empty = new LongOpenHashSet();
    private final Set<TravelMessage.TravelCoordinate> chunks;
    private final Long2LongOpenHashMap revisions = new Long2LongOpenHashMap();
    private final LongLinkedOpenHashSet pendingSections = new LongLinkedOpenHashSet();
    private long revision = 1;
    private final TravelMessage.TravelWorld travelWorld;
    private Map<TravelMessage.TravelCoordinate, byte[]> nativeColumns = Map.of();
    private final Long2ObjectOpenHashMap<MeshIdentity> meshIdentities = new Long2ObjectOpenHashMap<>();

    public ClientTravelScene(ClientLevel level, TravelMessage.TravelBegin begin) {
        this.level = level;
        travelWorld = begin.world();
        environment = begin.environment();
        chunks = new HashSet<>(begin.chunks());
        geometry = geometry(begin.arrival());
        for (TravelMessage.TravelCoordinate column : chunks) {
            if (interior(column.x(), column.z())) {
                for (int y = level.getMinSectionY(); y < level.getMinSectionY() + level.getSectionsCount(); y++) {
                    long key = SectionPos.asLong(column.x(), y, column.z());
                    sections.add(key);
                    pendingSections.add(key);
                }
            }
        }
    }

    public void rebind(TravelMessage.TravelBegin begin) {
        if (!travelWorld.equals(begin.world()) || !chunks.equals(new HashSet<>(begin.chunks()))
            || !OpticTransform.IDENTITY.equals(begin.environment().transform())) {
            throw new IllegalArgumentException("Prepared return snapshot identity differs");
        }
        environment = begin.environment();
        geometry = geometry(begin.arrival());
    }

    private static ApertureDescriptor geometry(TravelMessage.TravelPose arrival) {
        return new ApertureDescriptor((int) Math.floor(arrival.x()), (int) Math.floor(arrival.y()),
            (int) Math.floor(arrival.z()), Face.N.ordinal(), true, 0, false, 1, 1, new long[]{1L}, ShapeDescriptor.FULL,
            0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0.0D, 0, -1L, List.of());
    }

    public void nativeColumns(Map<TravelMessage.TravelCoordinate, byte[]> columns) {
        nativeColumns = new HashMap<>(columns);
    }

    public void invalidateColumn(int x, int z) {
        if (!nativeColumns.isEmpty()) {
            nativeColumns.remove(new TravelMessage.TravelCoordinate(x, z));
        }
    }

    @Override
    public MeshIdentity meshContext() {
        return meshIdentities.isEmpty() ? null : meshIdentities.values().iterator().next();
    }

    @Override
    public MeshIdentity meshIdentity(long key) {
        return meshIdentities.get(key);
    }

    private MeshIdentity meshIdentity(int x, int z) {
        if (nativeColumns.isEmpty()) {
            return null;
        }
        byte[][] columns = new byte[9][];
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                byte[] data = nativeColumns.get(new TravelMessage.TravelCoordinate(x + dx, z + dz));
                if (data == null) {
                    return null;
                }
                columns[(dz + 1) * 3 + dx + 1] = data;
            }
        }
        return new MeshIdentity(travelWorld, columns);
    }

    boolean inLevel(ClientLevel value) {
        return level == value;
    }

    ClientLevel level() {
        return level;
    }

    public void adoptLevel(ClientLevel level) {
        this.level = level;
        nativeColumns = Map.of();
    }

    public boolean complete() {
        return pendingSections.isEmpty() && !sections.isEmpty();
    }

    public void advance() {
        advance(System.nanoTime() + SNAPSHOT_NANOS);
    }

    private void advance(long deadline) {
        if (pendingSections.isEmpty()) {
            return;
        }
        RenderRegionCache cache = new RenderRegionCache();
        int limit = Math.min(MAX_SNAPSHOTS, pendingSections.size());
        for (int count = 0; count < limit; count++) {
            if (count > 0 && System.nanoTime() >= deadline) {
                break;
            }
            long key = pendingSections.removeFirstLong();
            if (!snapshot(cache, SectionPos.x(key), SectionPos.y(key), SectionPos.z(key))) {
                pendingSections.add(key);
            }
        }
    }

    public LongIterable changedSection(long sectionKey) {
        LongOpenHashSet changed = new LongOpenHashSet();
        int x = SectionPos.x(sectionKey);
        int y = SectionPos.y(sectionKey);
        int z = SectionPos.z(sectionKey);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (!interior(x + dx, z + dz)) {
                    continue;
                }
                for (int dy = -1; dy <= 1; dy++) {
                    int sectionY = y + dy;
                    if (sectionY < level.getMinSectionY() || sectionY >= level.getMinSectionY() + level.getSectionsCount()) {
                        continue;
                    }
                    long key = SectionPos.asLong(x + dx, sectionY, z + dz);
                    pendingSections.add(key);
                    meshIdentities.remove(key);
                    changed.add(key);
                }
            }
        }
        return changed;
    }

    private boolean interior(int x, int z) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (!chunks.contains(new TravelMessage.TravelCoordinate(x + dx, z + dz))) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean snapshot(RenderRegionCache cache, int x, int y, int z) {
        long key = SectionPos.asLong(x, y, z);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (level.getChunkSource().getChunk(x + dx, z + dz, ChunkStatus.FULL, false) == null) {
                    regions.remove(key);
                    empty.remove(key);
                    revisions.remove(key);
                    meshIdentities.remove(key);
                    return false;
                }
            }
        }
        LevelChunk chunk = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
        sections.add(key);
        revisions.put(key, ++revision);
        MeshIdentity identity = meshIdentity(x, z);
        if (identity == null) {
            meshIdentities.remove(key);
        } else {
            meshIdentities.put(key, identity);
        }
        if (chunk.getSection(chunk.getSectionIndex(y << 4)).hasOnlyAir()) {
            empty.add(key);
            regions.remove(key);
        } else {
            empty.remove(key);
            regions.put(key, cache.createRegion(level, key));
        }
        return true;
    }

    @Override
    public boolean fullWorld() {
        return true;
    }

    @Override
    public boolean empty(long sectionKey) {
        return empty.contains(sectionKey);
    }

    @Override
    public ApertureDescriptor geometry() {
        return geometry;
    }

    @Override
    public EnvironmentState environment() {
        return environment;
    }

    @Override
    public BlockAndTintGetter world(long sectionKey) {
        return regions.get(sectionKey);
    }

    @Override
    public LongIterable sectionKeys() {
        return sections;
    }

    @Override
    public long revision(long sectionKey) {
        return sections.contains(sectionKey) && !pendingSections.contains(sectionKey)
            ? revisions.get(sectionKey) : -1;
    }

    @Override
    public boolean refreshing(long sectionKey) {
        return pendingSections.contains(sectionKey) && revisions.containsKey(sectionKey);
    }
    record MeshIdentity(TravelMessage.TravelWorld world, byte[][] columns) implements PortalScene.MeshIdentity {
        @Override
        public int contextHash() {
            return world.hashCode();
        }

        @Override
        public boolean sameContext(PortalScene.MeshIdentity other) {
            return other instanceof MeshIdentity identity && world.equals(identity.world);
        }

        @Override
        public boolean same(PortalScene.MeshIdentity value) {
            if (!(value instanceof MeshIdentity other) || !sameContext(other) || columns.length != other.columns.length) {
                return false;
            }
            for (int index = 0; index < columns.length; index++) {
                if (!Arrays.equals(columns[index], other.columns[index])) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public void references(ObjLongConsumer<Object> consumer) {
            consumer.accept(this, 48L + 16 + columns.length * 8L);
            for (byte[] column : columns) {
                consumer.accept(column, (long) column.length);
            }
        }
    }

}
