package art.arcane.wormholes.render.view;

import art.arcane.optics.entity.EntityProfile;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.optics.entity.EntitySnapshot;
import art.arcane.wormholes.network.view.RemoteViewCache;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.fidelity.BlockEntitySample;

import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import art.arcane.optics.view.ContentView;
import art.arcane.optics.view.EntityData;
import art.arcane.optics.frame.OpticTransform;

public class RemoteProjectionView<B, T, M, E> implements ContentView<B, T>, EntityData<M, E> {
    private final RemoteViewCache.RemoteView<B, M, E> view;
    private final B fallback;
    private final Function<B, T> materials;
    private final ToIntFunction<String> biomeIds;
    private int cachedChunkX = Integer.MIN_VALUE;
    private int cachedChunkZ = Integer.MIN_VALUE;
    private RemoteViewCache.DecodedSlice<B> cachedSlice;
    private boolean cachedSliceValid;
    private long cachedSliceRevision;

    public RemoteProjectionView(RemoteViewCache.RemoteView<B, M, E> view, Options<B, T> options) {
        this.view = view;
        this.fallback = options.fallback();
        this.materials = options.materials();
        this.biomeIds = options.biomeIds();
    }

    public ProjectionEnvironment environment(OpticTransform transform) {
        ProjectionEnvironment captured = view.environment();
        return captured == null ? null : captured.withTransform(transform);
    }

    private RemoteViewCache.DecodedSlice<B> decodedSliceAt(int x, int z) {
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        long revision = view.getRevision();
        if (cachedSliceValid && chunkX == cachedChunkX && chunkZ == cachedChunkZ && cachedSliceRevision == revision) {
            return cachedSlice;
        }
        RemoteViewCache.DecodedSlice<B> slice = view.sliceAt(x, z);
        cachedChunkX = chunkX;
        cachedChunkZ = chunkZ;
        cachedSlice = slice;
        cachedSliceValid = true;
        cachedSliceRevision = revision;
        return slice;
    }

    @Override
    public UUID worldId() {
        return null;
    }

    @Override
    public int getMinHeight() {
        BlockBox box = view.getBox();
        return box == null ? 0 : box.minY();
    }

    @Override
    public int getMaxHeight() {
        BlockBox box = view.getBox();
        return box == null ? 0 : box.maxY() + 1;
    }

    @Override
    public B sampleBlockData(int x, int y, int z) {
        BlockBox box = view.getBox();
        if (box == null) {
            return null;
        }
        if (!box.contains(x, y, z)) {
            return fallback;
        }
        RemoteViewCache.DecodedSlice<B> slice = decodedSliceAt(x, z);
        if (slice == null) {
            return null;
        }
        return slice.blockAt(x, y, z);
    }

    @Override
    public BlockEntitySample sampleBlockEntity(int x, int y, int z) {
        BlockBox box = view.getBox();
        if (box == null || !box.contains(x, y, z)) {
            return null;
        }
        RemoteViewCache.DecodedSlice<B> slice = decodedSliceAt(x, z);
        return slice == null ? null : slice.blockEntityAt(x, y, z);
    }

    @Override
    public String sampleBiome(int x, int y, int z) {
        BlockBox box = view.getBox();
        if (box == null || x < box.minX() || x > box.maxX() || z < box.minZ() || z > box.maxZ()) {
            return null;
        }
        RemoteViewCache.DecodedSlice<B> slice = decodedSliceAt(x, z);
        return slice == null ? null : slice.biomeAt(x, Math.clamp(y, box.minY(), box.maxY()), z);
    }

    @Override
    public int biomeId(int x, int y, int z) {
        String biome = sampleBiome(x, y, z);
        return biome == null ? -1 : biomeIds.applyAsInt(biome);
    }

    @Override
    public int getLight(int x, int y, int z) {
        BlockBox box = view.getBox();
        if (box == null || x < box.minX() || x > box.maxX() || z < box.minZ() || z > box.maxZ()) {
            return LIGHT_UNAVAILABLE;
        }
        RemoteViewCache.DecodedSlice<B> slice = decodedSliceAt(x, z);
        if (slice == null) {
            return LIGHT_UNAVAILABLE;
        }
        int light = slice.lightAt(x, Math.clamp(y, box.minY(), box.maxY()), z);
        if (light == LIGHT_UNAVAILABLE || y >= box.minY() && y <= box.maxY()) {
            return light;
        }
        return ContentView.packLight(y > box.maxY() ? ContentView.unpackSkyLight(light) : 0, 0);
    }

    public List<EntitySnapshot> getEntities() {
        return view.getEntities();
    }

    public List<EntitySnapshot> getEntities(double centerX, double centerY, double centerZ, double range) {
        return view.getEntities();
    }

    public EntityProfile getProfile(UUID entityId) {
        return view.getProfile(entityId);
    }

    public List<M> getMetadata(UUID entityId) {
        return view.getMetadata(entityId);
    }

    public List<E> getEquipment(UUID entityId) {
        return view.getEquipment(entityId);
    }

    @Override
    public int getSkyDarken() {
        return view.getSkyDarken();
    }

    public boolean hasStorm() {
        return view.hasStorm();
    }

    public boolean isThundering() {
        return view.isThundering();
    }

    @Override
    public long getRevision() {
        return view.getRevision();
    }

    public int getStateVersion(UUID entityId) {
        return view.getStateVersion(entityId);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof RemoteProjectionView<?, ?, ?, ?> remote)) {
            return false;
        }
        return view.equals(remote.view);
    }

    @Override
    public int hashCode() {
        return view.hashCode();
    }
    @Override
    public T material(int x, int y, int z) {
        B block = sampleBlockData(x, y, z);
        return block == null ? null : materials.apply(block);
    }

    @Override
    public boolean isChunkReady(int x, int z) {
        return true;
    }

    @Override
    public void requestChunk(int x, int z) {
    }

    public record Options<B, T>(B fallback, Function<B, T> materials, ToIntFunction<String> biomeIds) {
    }
}
