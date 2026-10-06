package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.stream.ProjectionEnvironment;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.ShaderPack;
import java.util.LinkedHashSet;
import java.util.Set;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SkyRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.function.Supplier;

final class PortalIrisRenderer implements PortalShaderRenderer {
    private static final long MAX_BYTES = 1536L * 1024L * 1024L;
    private static final Comparator<Entry> REUSE_ORDER = Comparator.comparing(Entry::ready)
        .thenComparingLong(entry -> (long) entry.target.width * entry.target.height)
        .thenComparingLong(entry -> entry.bytes).reversed();

    private final Supplier<ShaderPack> packs;
    private final List<Entry> idle = new ArrayList<>();
    private final LinkedHashMap<Integer, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<NamespacedId, Shared> shared = new HashMap<>();
    private final Map<NamespacedId, Long> materialRevisions = new HashMap<>();
    private PortalIrisResolution resolution = new PortalIrisResolution(MAX_BYTES);
    private PortalIrisMaterials materials = new PortalIrisMaterials();
    private final PortalShaderWarmup warmups = new PortalShaderWarmup();
    private ShaderPack pack;
    private long reserved;
    private long revision;

    PortalIrisRenderer(Supplier<ShaderPack> packs) {
        this.packs = packs;
    }

    static PortalIrisRenderer create() {
        return new PortalIrisRenderer(() -> Iris.getCurrentPack().orElseThrow());
    }

    private NamespacedId dimension(ProjectionEnvironment environment) {
        return PortalIrisPipeline.dimension(pack, environment);
    }

    @Override
    public void beginFrame() {
        warmups.beginFrame();
    }

    @Override
    public List<Resolution> resolution(Sizing sizing) {
        updatePack();
        Map<NamespacedId, List<Integer>> depths = new HashMap<>();
        for (DemandView view : sizing.views()) {
            NamespacedId dimension = dimension(view.environment());
            depths.computeIfAbsent(dimension, ignored -> new ArrayList<>()).add(view.depth());
        }
        Map<NamespacedId, PortalIrisResolution.Dimension> demand = new HashMap<>();
        for (Map.Entry<NamespacedId, List<Integer>> entry : depths.entrySet()) {
            demand.put(entry.getKey(), new PortalIrisResolution.Dimension(pack.getProgramSet(entry.getKey()), entry.getValue()));
        }
        List<Resolution> sizes = resolution.select(new PortalIrisResolution.Demand(sizing.width(), sizing.height(), demand, PortalIrisResources.revision(), retained(demand, sizing.views())));
        for (DemandView view : sizing.views()) {
            Entry entry = entries.get(view.key());
            if (entry != null && entry.dimension.equals(dimension(view.environment()))) {
                Resolution size = sizes.get(view.depth());
                long bytes = entryBytes(entry, view.environment(), size.width(), size.height());
                if (bytes < entry.bytes) {
                    resize(entry, bytes, size.width(), size.height());
                }
            }
        }
        return sizes;
    }

    private long retained(Map<NamespacedId, PortalIrisResolution.Dimension> demand, List<DemandView> views) {
        long bytes = 0;
        Map<NamespacedId, Integer> uses = new HashMap<>();
        for (Map.Entry<NamespacedId, PortalIrisResolution.Dimension> requested : demand.entrySet()) {
            uses.put(requested.getKey(), requested.getValue().depths().size());
        }
        for (DemandView view : views) {
            Entry entry = entries.get(view.key());
            if (entry != null && entry.dimension.equals(dimension(view.environment()))) {
                uses.computeIfPresent(entry.dimension, (key, count) -> Math.max(0, count - 1));
            }
        }
        for (Entry entry : idle) {
            bytes += entry.bytes;
        }
        for (Map.Entry<NamespacedId, Integer> requested : uses.entrySet()) {
            if (requested.getValue() == 0) {
                continue;
            }
            List<Entry> reusable = reusableEntries(requested.getKey());
            int count = Math.min(requested.getValue(), reusable.size());
            for (int index = 0; index < count; index++) {
                bytes -= reusable.get(index).bytes;
            }
        }
        for (Map.Entry<NamespacedId, Shared> entry : shared.entrySet()) {
            if (!demand.containsKey(entry.getKey())) {
                bytes += entry.getValue().bytes;
            }
        }
        return bytes;
    }

    @Override
    public Session acquire(int key, ProjectionEnvironment environment, int width, int height) {
        updatePack();
        Entry existing = entries.get(key);
        if (existing != null && existing.dimension.equals(dimension(environment))) {
            resize(existing, entryBytes(existing, environment, width, height), width, height);
            return existing;
        }
        remove(key);
        NamespacedId dimension = dimension(environment);
        Entry reusable = reusable(dimension);
        if (reusable != null) {
            entries.put(key, reusable);
            resize(reusable, entryBytes(reusable, environment, width, height), width, height);
            return reusable;
        }
        Entry entry = create(key, dimension, width, height);
        entries.put(key, entry);
        return entry;
    }

    private Entry reusable(NamespacedId dimension) {
        Entry selected = null;
        for (Entry entry : idle) {
            if (entry.dimension.equals(dimension) && (selected == null || REUSE_ORDER.compare(entry, selected) < 0)) {
                selected = entry;
            }
        }
        if (selected != null) {
            idle.remove(selected);
        }
        return selected;
    }

    private List<Entry> reusableEntries(NamespacedId dimension) {
        List<Entry> candidates = new ArrayList<>();
        for (Entry entry : idle) {
            if (entry.dimension.equals(dimension)) {
                candidates.add(entry);
            }
        }
        candidates.sort(REUSE_ORDER);
        return candidates;
    }

    private Entry create(int key, NamespacedId dimension, int width, int height) {
        ProgramSet programs = pack.getProgramSet(dimension);
        long bytes = PortalIrisResources.targets(programs, width, height);
        boolean share = PortalIrisResources.shareShadows(programs);
        Shared shadow = share ? shared.get(dimension) : null;
        long shadowBytes = shadow == null ? PortalIrisResources.shadows(programs) : 0;
        if (shadow != null) {
            shadow.users++;
        }
        try {
            reserve();
            if (share && shadow == null) {
                shadow = new Shared(new PortalSharedShadows(), shadowBytes);
                shadow.users = 1;
                shared.put(dimension, shadow);
                reserved += shadowBytes;
            }
            long materialRevision = materialRevisions.computeIfAbsent(dimension, ignored -> ++revision);
            Entry entry = new Entry(new EntryRequest(key, dimension, width, height, materialRevision,
                bytes + (share ? 0 : shadowBytes), share ? 0 : shadowBytes, shadow, materials, warmups));
            reserved += entry.bytes;
            return entry;
        } catch (RuntimeException | Error failure) {
            try {
                release(shadow);
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private long entryBytes(Entry entry, ProjectionEnvironment environment, int width, int height) {
        return PortalIrisResources.targets(pack.getProgramSet(dimension(environment)), width, height)
            + (entry.shadows == null ? entry.shadowBytes : 0);
    }

    private void resize(Entry entry, long bytes, int width, int height) {
        reserve();
        if (entry.target.width != width || entry.target.height != height) {
            try (PortalTextureScope textures = new PortalTextureScope();
                 PortalFramebufferScope framebuffer = PortalFramebufferScope.capture()) {
                entry.target.resize(width, height);
                if (entry.pipeline != null) {
                    entry.pipeline.resize();
                }
            }
        }
        reserved += bytes - entry.bytes;
        entry.bytes = bytes;
    }

    private long cachedBytes() {
        long bytes = 0;
        Set<Shared> retainedShadows = new LinkedHashSet<>(shared.values());
        for (Entry entry : idle) {
            bytes += entry.bytes;
        }
        for (Entry entry : entries.values()) {
            retainedShadows.remove(entry.shadows);
        }
        for (Shared shadow : retainedShadows) {
            bytes += shadow.bytes;
        }
        return bytes;
    }

    private void reserve() {
        while (cachedBytes() > MAX_BYTES && !idle.isEmpty()) {
            destroy(idle.removeLast());
        }
    }

    @Override
    public void remove(int key) {
        Entry entry = entries.remove(key);
        if (entry == null) {
            return;
        }
        if (entry.active == 0) {
            if (entry.pipeline != null) {
                entry.pipeline.released();
            }
            idle.addFirst(entry);
            reserve();
        } else {
            destroy(entry);
        }
    }

    @Override
    public void discard(int key) {
        Entry entry = entries.remove(key);
        if (entry != null) {
            destroy(entry);
        }
    }

    @Override
    public void resetHistory(int key) {
        Entry entry = entries.get(key);
        if (entry != null && entry.pipeline != null) {
            entry.pipeline.released();
        }
    }

    @Override
    public boolean usesPack(Object shaderPack) {
        return pack != null && pack == shaderPack;
    }

    private void destroy(Entry entry) {
        try {
            entry.close();
        } finally {
            reserved -= entry.bytes;
            release(entry.shadows);
        }
    }

    @Override
    public long bytes() {
        return reserved;
    }

    @Override
    public void disconnect() {
        try {
            close();
        } finally {
            PortalIrisShaderStages.clearCurrentContext();
        }
    }

    @Override
    public void close() {
        Throwable failure = null;
        while (!entries.isEmpty()) {
            int key = entries.keySet().iterator().next();
            try {
                discard(key);
            } catch (RuntimeException | Error cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        while (!idle.isEmpty()) {
            try {
                destroy(idle.removeLast());
            } catch (RuntimeException | Error cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        materialRevisions.clear();
        resolution = new PortalIrisResolution(MAX_BYTES);
        materials = new PortalIrisMaterials();
        pack = null;
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private void updatePack() {
        ShaderPack current = packs.get();
        if (current != pack) {
            close();
            pack = current;
        }
    }

    private void release(Shared shadow) {
        if (shadow == null || --shadow.users != 0) {
            return;
        }
        shared.values().remove(shadow);
        try (PortalTextureScope textures = new PortalTextureScope();
             PortalFramebufferScope framebuffer = PortalFramebufferScope.capture()) {
            shadow.targets.close();
        } finally {
            reserved -= shadow.bytes;
        }
    }

    private static final class Shared {
        private final PortalSharedShadows targets;
        private final long bytes;
        private int users;

        private Shared(PortalSharedShadows targets, long bytes) {
            this.targets = targets;
            this.bytes = bytes;
        }
    }

    private record EntryRequest(int key, NamespacedId dimension, int width, int height,
                                long materialRevision, long bytes, long shadowBytes, Shared shadows, PortalIrisMaterials materials,
                                PortalShaderWarmup warmups) {
    }

    private static final class Entry implements Session, AutoCloseable {
        private final TextureTarget target;
        private final NamespacedId dimension;
        private final long materialRevision;
        private long bytes;
        private final long shadowBytes;
        private final Shared shadows;
        private final PortalIrisMaterials mappings;
        private final PortalShaderWarmup warmups;
        private PortalIrisPipeline pipeline;
        private SkyRenderer sky;
        private int active;

        private Entry(EntryRequest request) {
            dimension = request.dimension();
            materialRevision = request.materialRevision();
            bytes = request.bytes();
            shadowBytes = request.shadowBytes();
            shadows = request.shadows();
            mappings = request.materials();
            warmups = request.warmups();
            target = new TextureTarget("Wormholes shader view " + request.key(), request.width(), request.height(),
                GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        }

        @Override
        public TextureTarget target() {
            return target;
        }

        @Override
        public PortalTerrainMaterials materials() {
            return pipeline == null ? PortalTerrainMaterials.VANILLA : pipeline.materials();
        }

        @Override
        public boolean ready() {
            return pipeline != null && pipeline.ready();
        }

        @Override
        public boolean warm(PortalShaderContext.View view) {
            if (ready()) {
                return true;
            }
            if (!warmups.permit()) {
                return false;
            }
            if (pipeline == null) {
                construct();
                return ready();
            }
            return pipeline.warm(view);
        }

        private void construct() {
            pipeline = new PortalIrisPipeline(new PortalIrisPipeline.Request(dimension, target, materialRevision,
                shadows == null ? null : shadows.targets, mappings));
        }

        @Override
        public Frame begin(PortalShaderContext.View view) {
            if (!ready()) {
                throw new IllegalStateException("Destination shader pipeline has not been warmed");
            }
            PortalIrisFrame frame = pipeline.begin(view);
            active++;
            return () -> {
                try {
                    frame.close();
                } finally {
                    active--;
                }
            };
        }

        @Override
        public CompiledRenderPipeline terrain(ChunkSectionLayer layer, boolean reflected) {
            return pipeline.terrain(layer, reflected);
        }

        @Override
        public void endTerrain() {
            pipeline.endTerrain();
        }

        @Override
        public ShadowFrame shadows(CameraRenderState camera) {
            return pipeline.shadows(camera);
        }

        @Override
        public void prepare() {
            pipeline.prepare();
        }

        @Override
        public SkyRenderer sky() {
            if (sky == null) {
                Minecraft client = Minecraft.getInstance();
                sky = new SkyRenderer(client.getTextureManager(), client.getAtlasManager(), target);
            }
            return sky;
        }

        @Override
        public void translucents() {
            pipeline.translucents();
        }

        @Override
        public void finish() {
            pipeline.finish();
        }

        @Override
        public void close() {
            try {
                if (sky != null) {
                    sky.close();
                }
            } finally {
                sky = null;
                try {
                    if (pipeline != null) {
                        pipeline.close();
                    }
                } finally {
                    pipeline = null;
                    target.destroyBuffers();
                }
            }
        }
    }
}
