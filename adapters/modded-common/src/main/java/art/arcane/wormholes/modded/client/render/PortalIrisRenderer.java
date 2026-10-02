package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.network.client.ClientViewEnvironment;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.ShaderPack;
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
import java.util.ArrayList;
import java.util.function.Supplier;

final class PortalIrisRenderer implements PortalShaderRenderer {
    private static final long MAX_BYTES = 1024L * 1024L * 1024L;

    private final Supplier<ShaderPack> packs;
    private final LinkedHashMap<Integer, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<NamespacedId, Shared> shared = new HashMap<>();
    private final Map<NamespacedId, Long> materialRevisions = new HashMap<>();
    private PortalIrisResolution resolution = new PortalIrisResolution(MAX_BYTES);
    private PortalIrisMaterials materials = new PortalIrisMaterials();
    private final PortalShaderWarmup warmups = new PortalShaderWarmup();
    private ShaderPack pack;
    private long reserved;
    private long revision;
    private long frame;

    PortalIrisRenderer(Supplier<ShaderPack> packs) {
        this.packs = packs;
    }

    static PortalIrisRenderer create() {
        return new PortalIrisRenderer(() -> Iris.getCurrentPack().orElseThrow());
    }

    @Override
    public void beginFrame() {
        frame++;
        warmups.beginFrame();
    }

    @Override
    public List<Resolution> resolution(Sizing sizing) {
        updatePack();
        Map<NamespacedId, List<Integer>> depths = new HashMap<>();
        for (DemandView view : sizing.views()) {
            NamespacedId dimension = PortalIrisPipeline.dimension(pack, view.environment());
            depths.computeIfAbsent(dimension, ignored -> new ArrayList<>()).add(view.depth());
        }
        Map<NamespacedId, PortalIrisResolution.Dimension> demand = new HashMap<>();
        for (Map.Entry<NamespacedId, List<Integer>> entry : depths.entrySet()) {
            demand.put(entry.getKey(), new PortalIrisResolution.Dimension(pack.getProgramSet(entry.getKey()), entry.getValue()));
        }
        List<Resolution> sizes = resolution.select(new PortalIrisResolution.Demand(sizing.width(), sizing.height(), demand, PortalIrisResources.revision()));
        for (DemandView view : sizing.views()) {
            Entry entry = entries.get(view.key());
            if (entry != null && entry.matches(view.environment())) {
                entry.frame = frame;
                Resolution size = sizes.get(view.depth());
                long bytes = entryBytes(entry, view.environment(), size.width(), size.height());
                if (bytes < entry.bytes) {
                    resize(entry, bytes, size.width(), size.height());
                }
            }
        }
        return sizes;
    }

    @Override
    public Session acquire(int key, ClientViewEnvironment environment, int width, int height) {
        updatePack();
        Entry existing = entries.get(key);
        if (existing != null && existing.matches(environment)) {
            existing.frame = frame;
            resize(existing, entryBytes(existing, environment, width, height), width, height);
            return existing;
        }
        remove(key);
        NamespacedId dimension = PortalIrisPipeline.dimension(pack, environment);
        ProgramSet programs = pack.getProgramSet(dimension);
        long bytes = PortalIrisResources.targets(programs, width, height);
        boolean share = PortalIrisResources.shareShadows(programs);
        Shared shadow = share ? shared.get(dimension) : null;
        long shadowBytes = shadow == null ? PortalIrisResources.shadows(programs) : 0;
        if (shadow != null) {
            shadow.users++;
        }
        try {
            reserve(Math.addExact(bytes, shadowBytes));
            if (share && shadow == null) {
                shadow = new Shared(new PortalSharedShadows(), shadowBytes);
                shadow.users = 1;
                shared.put(dimension, shadow);
                reserved += shadowBytes;
            }
            long materialRevision = materialRevisions.computeIfAbsent(dimension, ignored -> ++revision);
            Entry entry = new Entry(new EntryRequest(key, environment, width, height, materialRevision,
                bytes + (share ? 0 : shadowBytes), share ? 0 : shadowBytes, shadow, materials, warmups));
            entry.frame = frame;
            entries.put(key, entry);
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

    private long entryBytes(Entry entry, ClientViewEnvironment environment, int width, int height) {
        return PortalIrisResources.targets(pack.getProgramSet(PortalIrisPipeline.dimension(pack, environment)), width, height)
            + (entry.shadows == null ? entry.shadowBytes : 0);
    }

    private void resize(Entry entry, long bytes, int width, int height) {
        reserve(Math.max(0, bytes - entry.bytes));
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

    private void reserve(long bytes) {
        while (reserved + bytes > MAX_BYTES) {
            Integer candidate = null;
            for (Map.Entry<Integer, Entry> entry : entries.entrySet()) {
                if (entry.getValue().active == 0 && entry.getValue().frame != frame) {
                    candidate = entry.getKey();
                    break;
                }
            }
            if (candidate == null) {
                throw new IllegalStateException("Destination shader resources exceed the 1 GiB pool limit");
            }
            remove(candidate);
        }
    }

    @Override
    public void remove(int key) {
        Entry entry = entries.remove(key);
        if (entry != null) {
            try {
                entry.close();
            } finally {
                reserved -= entry.bytes;
                release(entry.shadows);
            }
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
                remove(key);
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

    private record EntryRequest(int key, ClientViewEnvironment environment, int width, int height,
                                long materialRevision, long bytes, long shadowBytes, Shared shadows, PortalIrisMaterials materials,
                                PortalShaderWarmup warmups) {
    }

    private static final class Entry implements Session, AutoCloseable {
        private final TextureTarget target;
        private final ClientViewEnvironment environment;
        private final long materialRevision;
        private long bytes;
        private final long shadowBytes;
        private final Shared shadows;
        private final PortalIrisMaterials mappings;
        private final PortalShaderWarmup warmups;
        private PortalIrisPipeline pipeline;
        private SkyRenderer sky;
        private int active;
        private long frame;

        private Entry(EntryRequest request) {
            environment = request.environment();
            materialRevision = request.materialRevision();
            bytes = request.bytes();
            shadowBytes = request.shadowBytes();
            shadows = request.shadows();
            mappings = request.materials();
            warmups = request.warmups();
            target = new TextureTarget("Wormholes shader view " + request.key(), request.width(), request.height(),
                GpuFormat.RGBA8_UNORM, GpuFormat.D32_FLOAT);
        }

        private boolean matches(ClientViewEnvironment updated) {
            return environment.world().dimensionKey().equals(updated.world().dimensionKey());
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
            return pipeline != null;
        }

        @Override
        public boolean warm(PortalShaderContext.View view) {
            if (pipeline == null) {
                if (!warmups.permit()) {
                    return false;
                }
                pipeline = new PortalIrisPipeline(new PortalIrisPipeline.Request(view, materialRevision,
                    shadows == null ? null : shadows.targets, mappings));
            }
            return true;
        }

        @Override
        public Frame begin(PortalShaderContext.View view) {
            if (pipeline == null) {
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
            return PortalIrisTerrain.get(layer, reflected);
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
