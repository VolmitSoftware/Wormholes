package art.arcane.optics.client;

import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.Supplier;

import art.arcane.optics.stream.BrickLightSource;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.scan.ProjectorSample;
import art.arcane.optics.math.BlockBox;
import art.arcane.optics.plate.PlateCell;
import art.arcane.optics.plate.ViewPlate;
import art.arcane.optics.plate.ViewPlateKey;
import art.arcane.optics.view.ContentView;
import art.arcane.optics.frame.ViewWindow;
import art.arcane.optics.frame.OpticTransform;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;

public final class PlateLight<B> implements BrickLightSource {
    public static final int UNAVAILABLE = ContentView.LIGHT_UNAVAILABLE;
    static final long REUSE_WINDOW_NANOS = 20_000_000_000L;
    private static final long REUSE_JITTER_NANOS = 20_000_000L;
    private static final int DIRTY_CHUNK_MARGIN = 1;
    private static final int SECTION_UNLIT = 0;
    private static final int SECTION_FILLED = 1;
    private static final int SECTION_INCOMPLETE = 2;

    private final WeakReference<ViewPlate<B>> plate;
    private final BlockBox box;
    private final ViewWindow frame;
    private final Sampler sampler;
    private final OpticTransform transform;
    private final double[] remote;
    private final boolean fullBright;
    private final long createdNanos;
    private final Long2ObjectOpenHashMap<Section> computed;
    private volatile PlateLight<B> previous;
    private volatile LongSet dirtyChunks;
    private long reusedSections;

    public PlateLight(ViewPlate<B> plate, ViewWindow frame, Sampler sampler, boolean fullBright) {
        this.plate = new WeakReference<ViewPlate<B>>(Objects.requireNonNull(plate, "plate"));
        this.box = plate.box();
        this.frame = Objects.requireNonNull(frame, "frame");
        this.sampler = Objects.requireNonNull(sampler, "sampler");
        this.transform = frame.transform().inverse();
        this.remote = new double[3];
        this.fullBright = fullBright;
        this.createdNanos = System.nanoTime();
        this.computed = new Long2ObjectOpenHashMap<Section>();
        this.dirtyChunks = LongSets.EMPTY_SET;
    }

    public static BlockBox remoteBox(BlockBox box, ViewWindow frame) {
        return frame.transform().inverse().box(box, 0);
    }

    public synchronized long reusedSections() {
        return reusedSections;
    }

    @Override
    public synchronized boolean fill(int sectionX, int sectionY, int sectionZ, byte[] blockNibbles, byte[] skyNibbles) {
        long key = CellKeys.pack(sectionX, sectionY, sectionZ);
        Section known = compatible(key);
        Section reused = known == null || !known.complete || createdNanos - known.computedNanos > reuseWindow(key)
            || touchesDirty(sectionX, sectionY, sectionZ) ? null : known;
        if (reused != null) {
            System.arraycopy(reused.block, 0, blockNibbles, 0, ViewStreamLimits.LIGHT_NIBBLE_BYTES);
            System.arraycopy(reused.sky, 0, skyNibbles, 0, ViewStreamLimits.LIGHT_NIBBLE_BYTES);
            computed.put(key, new Section(blockNibbles, skyNibbles, reused.filled, true, reused.computedNanos));
            reusedSections++;
            return reused.filled;
        }
        int state = compute(sectionX, sectionY, sectionZ, blockNibbles, skyNibbles, known);
        boolean complete = state != SECTION_INCOMPLETE;
        boolean filled = state == SECTION_FILLED;
        computed.put(key, new Section(blockNibbles, skyNibbles, filled, complete, createdNanos));
        return filled;
    }

    void inherit(PlateLight<B> older, LongSet dirty) {
        previous = older;
        dirtyChunks = dirty == null ? LongSets.EMPTY_SET : dirty;
    }

    void release() {
        previous = null;
    }

    private int compute(int sectionX, int sectionY, int sectionZ, byte[] blockNibbles, byte[] skyNibbles, Section known) {
        ViewPlate<B> source = plate.get();
        if (source == null) {
            return SECTION_INCOMPLETE;
        }
        int baseX = sectionX << 4;
        int baseY = sectionY << 4;
        int baseZ = sectionZ << 4;
        int fromX = Math.max(baseX, box.minX());
        int fromY = Math.max(baseY, box.minY());
        int fromZ = Math.max(baseZ, box.minZ());
        int toX = Math.min(baseX + 16, box.minX() + box.sizeX());
        int toY = Math.min(baseY + 16, box.minY() + box.sizeY());
        int toZ = Math.min(baseZ + 16, box.minZ() + box.sizeZ());
        boolean filled = false;
        for (int y = fromY; y < toY; y++) {
            for (int z = fromZ; z < toZ; z++) {
                for (int x = fromX; x < toX; x++) {
                    PlateCell<B> cell = source.cell(CellKeys.pack(x, y, z));
                    if (cell == null || !lit(cell.kind())) {
                        continue;
                    }
                    int packed = fullBright ? ContentView.packLight(15, 15) : sample(x, y, z);
                    int index = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15);
                    if (packed == UNAVAILABLE) {
                        if (known == null || !known.filled) {
                            return SECTION_INCOMPLETE;
                        }
                        BrickLightSource.setNibble(skyNibbles, index, BrickLightSource.nibble(known.sky, index));
                        BrickLightSource.setNibble(blockNibbles, index, BrickLightSource.nibble(known.block, index));
                        filled = true;
                        continue;
                    }
                    int sky = fullBright ? 15 : ContentView.unpackSkyLight(packed);
                    BrickLightSource.setNibble(skyNibbles, index, sky);
                    BrickLightSource.setNibble(blockNibbles, index, ContentView.unpackBlockLight(packed));
                    filled = true;
                }
            }
        }
        return filled ? SECTION_FILLED : SECTION_UNLIT;
    }

    private Section compatible(long key) {
        PlateLight<B> older = previous;
        if (older == null || older.fullBright != fullBright || !older.box.equals(box)
            || !sameFrame(older.frame, frame)) {
            return null;
        }
        synchronized (older) {
            return older.computed.get(key);
        }
    }

    private boolean touchesDirty(int sectionX, int sectionY, int sectionZ) {
        LongSet dirty = dirtyChunks;
        if (dirty.isEmpty()) {
            return false;
        }
        int[] bounds = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (int corner = 0; corner < 8; corner++) {
            double x = (sectionX << 4) + ((corner & 1) == 0 ? 0.5D : 15.5D);
            double y = (sectionY << 4) + ((corner & 2) == 0 ? 0.5D : 15.5D);
            double z = (sectionZ << 4) + ((corner & 4) == 0 ? 0.5D : 15.5D);
            include(transform, x, y, z, remote, bounds);
        }
        int minChunkX = (bounds[0] >> 4) - DIRTY_CHUNK_MARGIN;
        int maxChunkX = (bounds[3] >> 4) + DIRTY_CHUNK_MARGIN;
        int minChunkZ = (bounds[2] >> 4) - DIRTY_CHUNK_MARGIN;
        int maxChunkZ = (bounds[5] >> 4) + DIRTY_CHUNK_MARGIN;
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                if (dirty.contains(CellKeys.chunkKey(chunkX, chunkZ))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static long reuseWindow(long key) {
        return REUSE_WINDOW_NANOS + Math.floorMod(Long.hashCode(key * 0x9E3779B97F4A7C15L), 1000) * REUSE_JITTER_NANOS;
    }

    private int sample(int x, int y, int z) {
        transform.snappedPointInto(x + 0.5D, y + 0.5D, z + 0.5D, remote);
        return sampler.light((int) Math.floor(remote[0]), (int) Math.floor(remote[1]), (int) Math.floor(remote[2]));
    }

    private static void include(OpticTransform transform, double x, double y, double z, double[] out, int[] bounds) {
        transform.snappedPointInto(x, y, z, out);
        for (int axis = 0; axis < 3; axis++) {
            int value = (int) Math.floor(out[axis]);
            bounds[axis] = Math.min(bounds[axis], value);
            bounds[axis + 3] = Math.max(bounds[axis + 3], value);
        }
    }

    private static boolean lit(ProjectorSample.Kind kind) {
        return kind == ProjectorSample.Kind.BLOCK || kind == ProjectorSample.Kind.REMOTE_AIR || kind == ProjectorSample.Kind.MASK_AIR;
    }

    private static boolean sameFrame(ViewWindow left, ViewWindow right) {
        return left.transform().equals(right.transform()) && left.mirror() == right.mirror() && left.frontSide() == right.frontSide()
            && left.localOrigin().equals(right.localOrigin()) && left.remoteOrigin().equals(right.remoteOrigin())
            && left.localFrame().equals(right.localFrame()) && left.remoteFrame().equals(right.remoteFrame());
    }

    @FunctionalInterface
    public interface Sampler {
        int light(int x, int y, int z);
    }

    private record Section(byte[] block, byte[] sky, boolean filled, boolean complete, long computedNanos) {
    }

    public static final class Cache<B> {
        private static final int MAX_KEYS = 256;

        private final Map<ViewPlate<B>, BrickLightSource> lights;
        private final Map<ViewPlateKey, PlateLight<B>> latest;

        public Cache() {
            this.lights = new WeakHashMap<ViewPlate<B>, BrickLightSource>();
            this.latest = new LinkedHashMap<ViewPlateKey, PlateLight<B>>(16, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<ViewPlateKey, PlateLight<B>> eldest) {
                    return size() > MAX_KEYS;
                }
            };
        }

        public BrickLightSource light(ViewPlate<B> plate, Supplier<? extends BrickLightSource> factory) {
            synchronized (lights) {
                BrickLightSource known = lights.get(plate);
                if (known != null) {
                    return known;
                }
            }
            BrickLightSource created = factory.get();
            if (created == null) {
                return BrickLightSource.NONE;
            }
            synchronized (lights) {
                BrickLightSource raced = lights.get(plate);
                if (raced != null) {
                    return raced;
                }
                lights.put(plate, created);
                if (created instanceof PlateLight<?> fresh) {
                    link(plate, fresh);
                }
                return created;
            }
        }

        public int size() {
            synchronized (lights) {
                return lights.size();
            }
        }

        @SuppressWarnings("unchecked")
        private void link(ViewPlate<B> plate, PlateLight<?> fresh) {
            PlateLight<B> typed = (PlateLight<B>) fresh;
            PlateLight<B> older = latest.put(plate.key(), typed);
            ViewPlate<B> olderPlate = older == null ? null : older.plate.get();
            if (older == null || olderPlate == plate) {
                return;
            }
            older.release();
            if (olderPlate != null) {
                typed.inherit(older, olderPlate.dirtyChunks());
            }
        }
    }
}
