package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.stream.EnvironmentState;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongCollection;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.map.ChunkTrackerHolder;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.render.viewport.ViewportProvider;
import net.caffeinemc.mods.sodium.client.util.FogParameters;
import net.caffeinemc.mods.sodium.client.util.GameRendererStorage;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.SectionPos;
import net.minecraft.world.attribute.EnvironmentAttributeProbe;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.material.FogType;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

import java.util.IdentityHashMap;
import java.util.Map;

public final class ClientSodiumTerrain {
    private static final boolean AVAILABLE = ClientSodiumTerrain.class.getClassLoader()
        .getResource("net/caffeinemc/mods/sodium/client/render/SodiumWorldRenderer.class") != null;
    private static final int MAX_STATES = 3;
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Map<ClientLevel, State> STATES = new IdentityHashMap<>();
    private static long sequence;
    private static int prewarming;
    private static int lastWarmFrame = Integer.MIN_VALUE;
    private static Handoff handoff;

    private ClientSodiumTerrain() {
    }

    public static boolean available() {
        return AVAILABLE;
    }

    public static boolean prewarming() {
        return prewarming > 0;
    }

    public static Preparation prepare(ClientLevel level, EnvironmentState environment, CameraRenderState camera) {
        if (!AVAILABLE) {
            return Preparation.COVER;
        }
        RenderSystem.assertOnRenderThread();
        Minecraft minecraft = Minecraft.getInstance();
        rememberActive(minecraft);
        if (level == minecraft.level) {
            if (STATES.get(level).viewport == null) {
                return Preparation.COVER;
            }
            return ready(level) ? Preparation.READY : Preparation.PENDING;
        }
        if (minecraft.level != null && level.dimension().equals(minecraft.level.dimension())) {
            forget(level);
            return Preparation.COVER;
        }
        if (!compatible(level)) {
            forget(level);
            return Preparation.COVER;
        }
        State state = STATES.get(level);
        if (state != null && !state.compatible()) {
            forget(level);
            state = null;
        }
        if (state == null) {
            trim(minecraft.level);
            SodiumWorldRenderer renderer = new SodiumWorldRenderer(minecraft);
            state = new State(new Ownership(level, renderer, currentSettings(), minecraft.options.getEffectiveRenderDistance()));
            STATES.put(level, state);
            try {
                renderer.setLevel(level);
            } catch (RuntimeException | Error failure) {
                STATES.remove(level);
                try {
                    state.close();
                } catch (RuntimeException | Error cleanup) {
                    failure.addSuppressed(cleanup);
                }
                throw failure;
            }
        }
        if (state.warmFailed) {
            return Preparation.COVER;
        }
        state.used = ++sequence;
        state.warmEnvironment = environment;
        state.warmCamera = camera;
        Matrix4fc projection = ((GameRendererStorage) minecraft.gameRenderer).sodium$getProjectionMatrix();
        if (projection == null) {
            return Preparation.PENDING;
        }
        Frustum frustum = new Frustum(camera.viewRotationMatrix, new Matrix4f(projection));
        frustum.prepare(camera.pos.x, camera.pos.y, camera.pos.z);
        Viewport viewport = ((ViewportProvider) frustum).sodium$createViewport();
        state.viewport = viewport;
        float distance = (state.distance + 1) * 16.0f;
        FogParameters fog = new FogParameters(0, 0, 0, 1, Float.NaN, Float.NaN, 0, distance);
        TerrainCamera terrainCamera = new TerrainCamera(environment, camera);
        prepareTerrain(state, terrainCamera, fog, new Matrix4f(projection).mul(camera.viewRotationMatrix));
        return state.ready ? Preparation.READY : Preparation.PENDING;
    }

    static void warmPrepared() {
        if (!AVAILABLE || STATES.isEmpty() || !PortalShaderScope.shaders()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        int frame = PortalIrisMainPipelines.nativeFrame();
        if (frame == lastWarmFrame) {
            return;
        }
        for (State state : STATES.values()) {
            if (state.level == minecraft.level || state.warmEnvironment == null || state.warmFailed
                || !state.compatible() || !visibility(state).wormholes$visibilityReady()) {
                continue;
            }
            Object pipeline = PortalIrisMainPipelines.nativePipeline(state.level);
            if (pipeline == null) {
                continue;
            }
            state.selectWarmPipeline(pipeline);
            if (state.warmStage == WarmStage.COMPLETE) {
                continue;
            }
            if (state.viewport == null || !sectionsBuilt(state, state.viewport)) {
                continue;
            }
            lastWarmFrame = frame;
            state.uniformFramePending = true;
            try {
                ((PortalSodiumTerrainAccess) state.renderer).wormholes$prepareFrame();
                if (PortalIrisMainPipelines.warmNative(new PortalIrisMainPipelines.NativeDraw(state.level,
                    state.warmEnvironment, state.warmCamera, state.renderer, state.warmStage))) {
                    state.warmStage = state.warmStage.next();
                }
            } catch (RuntimeException | Error failure) {
                state.warmFailed = true;
                state.ready = false;
                LOGGER.error("Unable to draw prepared native terrain for {}", state.level.dimension().identifier(), failure);
            }
            return;
        }
    }

    public static void endFrame() {
        if (!AVAILABLE || STATES.isEmpty()) {
            return;
        }
        SodiumWorldRenderer active = SodiumWorldRenderer.instanceNullable();
        Throwable failure = null;
        for (State state : STATES.values()) {
            if (!state.uniformFramePending) {
                continue;
            }
            state.uniformFramePending = false;
            if (state.closed || state.renderer == active) {
                continue;
            }
            try {
                state.renderer.endFrame();
            } catch (RuntimeException | Error cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    static void mainDrawn(Object pipeline) {
        if (!AVAILABLE || STATES.isEmpty()) {
            return;
        }
        State state = STATES.get(Minecraft.getInstance().level);
        if (state != null && state.renderer == SodiumWorldRenderer.instanceNullable()) {
            state.warmPipeline = pipeline;
            state.warmStage = WarmStage.COMPLETE;
            state.warmFailed = false;
        }
    }

    public static boolean ready(ClientLevel level) {
        if (!AVAILABLE) {
            return false;
        }
        State state = STATES.get(level);
        if (state != null && state.viewport != null && !state.ready && level == Minecraft.getInstance().level && state.compatible()) {
            state.ready = sectionsBuilt(state, state.viewport) && visibility(state).wormholes$visibilityReady() && state.warmed();
        }
        return state != null && state.viewport != null && state.ready && visibility(state).wormholes$visibilityReady()
            && state.warmed() && state.compatible() && compatible(level);
    }

    public static boolean usesPreparedTerrain(ClientLevel level) {
        State state = AVAILABLE ? STATES.get(level) : null;
        return state != null && !state.warmFailed && (state.viewport != null || level != Minecraft.getInstance().level)
            && state.compatible() && compatible(level);
    }

    public static boolean handlesMainUpdates(ClientLevel level) {
        State state = AVAILABLE ? STATES.get(level) : null;
        return state != null && !state.closed && level == Minecraft.getInstance().level
            && state.renderer == SodiumWorldRenderer.instanceNullable();
    }

    public static void forget(ClientLevel level) {
        if (!AVAILABLE || STATES.isEmpty()) {
            return;
        }
        State state = STATES.remove(level);
        if (state != null) {
            if (state.renderer != SodiumWorldRenderer.instanceNullable()) {
                state.close();
            } else {
                releaseColumns(state);
            }
        }
    }

    public static boolean retainColumn(ClientLevel level, RenderSectionManager manager, int x, int z) {
        State state = ownedState(level, manager);
        if (state == null) {
            return false;
        }
        LevelChunk column = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
        if (column == null) {
            state.parkedColumns.remove(ChunkPos.pack(x, z));
            return false;
        }
        if (state.readyColumns == null) {
            state.readyColumns = ChunkTrackerHolder.get(level).getReadyChunks();
        }
        state.parkedColumns.put(ChunkPos.pack(x, z), column);
        return true;
    }

    public static void prepareColumns(ClientLevel level, RenderSectionManager manager) {
        State state = ownedState(level, manager);
        if (state == null || state.parkedColumns.isEmpty()) {
            return;
        }
        ObjectIterator<Long2ObjectMap.Entry<LevelChunk>> columns = state.parkedColumns.long2ObjectEntrySet().fastIterator();
        while (columns.hasNext()) {
            Long2ObjectMap.Entry<LevelChunk> entry = columns.next();
            long key = entry.getLongKey();
            int x = ChunkPos.getX(key);
            int z = ChunkPos.getZ(key);
            LevelChunk current = level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
            if (current != entry.getValue()) {
                columns.remove();
                state.ready = false;
                discardColumn(level, manager, x, z);
                if (current != null && state.readyColumns.contains(key)) {
                    manager.onChunkAdded(x, z);
                }
            } else if (state.readyColumns.contains(key)) {
                columns.remove();
                manager.markGraphDirty();
            }
        }
    }

    public static boolean deferColumnBuild(ClientLevel level, RenderSectionManager manager, int x, int z) {
        State state = ownedState(level, manager);
        return state != null && state.parkedColumns.containsKey(ChunkPos.pack(x, z))
            && !state.readyColumns.contains(ChunkPos.pack(x, z));
    }

    public static void columnUnloaded(ClientLevel level, int x, int z) {
        State state = AVAILABLE ? STATES.get(level) : null;
        if (state != null && !state.closed) {
            state.ready = false;
            state.parkedColumns.remove(ChunkPos.pack(x, z));
            discardColumn(level, ((PortalSodiumTerrainAccess) state.renderer).wormholes$terrainManager(), x, z);
        }
    }

    public static void lightChanged(ClientLevel level, long sectionKey, boolean vanillaUpdated) {
        if (!AVAILABLE) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (!minecraft.isSameThread()) {
            minecraft.execute(() -> lightChanged(level, sectionKey, vanillaUpdated));
            return;
        }
        State state = STATES.get(level);
        if (state == null || state.closed) {
            return;
        }
        if (!vanillaUpdated || !handlesMainUpdates(level)) {
            state.renderer.scheduleRebuildForChunk(SectionPos.x(sectionKey), SectionPos.y(sectionKey), SectionPos.z(sectionKey), false);
        }
    }

    public static void dirty(ClientLevel level, long sectionKey) {
        if (!AVAILABLE) {
            return;
        }
        State state = STATES.get(level);
        if (state != null) {
            int sectionX = SectionPos.x(sectionKey);
            int sectionY = SectionPos.y(sectionKey);
            int sectionZ = SectionPos.z(sectionKey);
            for (int x = sectionX - 1; x <= sectionX + 1; x++) {
                for (int y = Math.max(level.getMinSectionY(), sectionY - 1);
                     y <= Math.min(level.getMinSectionY() + level.getSectionsCount() - 1, sectionY + 1); y++) {
                    for (int z = sectionZ - 1; z <= sectionZ + 1; z++) {
                        state.renderer.scheduleRebuildForChunk(x, y, z, true);
                    }
                }
            }
        }
    }

    public static void clear() {
        if (!AVAILABLE || STATES.isEmpty()) {
            return;
        }
        SodiumWorldRenderer active = SodiumWorldRenderer.instanceNullable();
        State[] states = STATES.values().toArray(State[]::new);
        STATES.clear();
        Throwable failure = null;
        for (State state : states) {
            try {
                if (state.renderer == active) {
                    releaseColumns(state);
                } else {
                    state.close();
                }
            } catch (RuntimeException | Error cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    public static Handoff handoff(ClientLevel level) {
        return new Handoff(level, false);
    }

    public static Handoff authoritativeHandoff(ClientLevel level) {
        return new Handoff(level, true);
    }

    public static void beforeLevelChange(ClientLevel level) {
        if (handoff != null && handoff.destination == level && handoff.state != null && handoff.state.compatible()) {
            ((PortalSodiumRendererAccess) Minecraft.getInstance().levelRenderer).wormholes$terrainRenderer(handoff.state.renderer);
            handoff.installed = true;
            handoff.state.used = ++sequence;
        } else {
            clear();
        }
    }

    public static boolean retainReload(SodiumWorldRenderer renderer) {
        if (handoff != null && handoff.installed && handoff.state.renderer == renderer && handoff.state.compatible()) {
            return true;
        }
        clear();
        return false;
    }

    public static boolean retainLevelInvalidation(ClientLevel level) {
        return handoff != null && handoff.installed && handoff.destination == level
            && STATES.get(level) == handoff.state && handoff.state.compatible()
            && handoff.state.renderer == SodiumWorldRenderer.instanceNullable();
    }

    public static boolean retainUnshadedHandoff() {
        return handoff != null && handoff.state != null && handoff.state.settings == null
            && !PortalShaderScope.shaders() && handoff.destination == Minecraft.getInstance().level
            && STATES.get(handoff.destination) == handoff.state && handoff.state.compatible();
    }

    private static void prepareTerrain(State state, Camera camera, FogParameters fog, Matrix4f matrix) {
        prewarming++;
        try {
            state.renderer.setupTerrain(camera, state.viewport, fog, false, false, matrix);
            state.ready = sectionsBuilt(state, state.viewport) && visibility(state).wormholes$visibilityReady() && state.warmed();
        } finally {
            prewarming--;
        }
        state.ready = state.ready && visibility(state).wormholes$visibilityReady();
    }

    private static boolean compatible(ClientLevel level) {
        return !PortalShaderScope.irisPresent() || PortalIrisMainPipelines.terrainCompatible(level);
    }

    private static PortalSodiumSectionAccess visibility(State state) {
        return (PortalSodiumSectionAccess) ((PortalSodiumTerrainAccess) state.renderer).wormholes$terrainManager();
    }

    private static State ownedState(ClientLevel level, RenderSectionManager manager) {
        State state = STATES.get(level);
        return state != null && !state.closed
            && ((PortalSodiumTerrainAccess) state.renderer).wormholes$terrainManager() == manager ? state : null;
    }

    private static void discardColumn(ClientLevel level, RenderSectionManager manager, int x, int z) {
        for (int y = level.getMinSectionY(); y < level.getMinSectionY() + level.getSectionsCount(); y++) {
            manager.onSectionRemoved(x, y, z);
        }
    }

    private static void releaseColumns(State state) {
        if (state.parkedColumns.isEmpty()) {
            return;
        }
        RenderSectionManager manager = ((PortalSodiumTerrainAccess) state.renderer).wormholes$terrainManager();
        for (long key : state.parkedColumns.keySet()) {
            discardColumn(state.level, manager, ChunkPos.getX(key), ChunkPos.getZ(key));
        }
        state.parkedColumns.clear();
    }

    private static PortalIrisSettings currentSettings() {
        return PortalShaderScope.shaders() ? PortalIrisSettings.capture() : null;
    }

    private static void rememberActive(Minecraft minecraft) {
        if (minecraft.level == null) {
            return;
        }
        SodiumWorldRenderer renderer = SodiumWorldRenderer.instance();
        State active = STATES.get(minecraft.level);
        if (active == null || active.renderer != renderer) {
            if (active != null) {
                active.close();
            }
            trim(minecraft.level);
            active = new State(new Ownership(minecraft.level, renderer, currentSettings(), minecraft.options.getEffectiveRenderDistance()));
            STATES.put(minecraft.level, active);
        }
        active.used = ++sequence;
        if (active.warmEnvironment == null) {
            active.warmStage = WarmStage.COMPLETE;
        }
    }

    private static void trim(ClientLevel active) {
        if (STATES.size() < MAX_STATES) {
            return;
        }
        State oldest = null;
        for (State state : STATES.values()) {
            if (state.level != active && (oldest == null || state.used < oldest.used)) {
                oldest = state;
            }
        }
        if (oldest != null) {
            STATES.remove(oldest.level);
            oldest.close();
        }
    }

    private static boolean sectionsBuilt(State state, Viewport viewport) {
        int centerX = viewport.getChunkCoord().x();
        int centerZ = viewport.getChunkCoord().z();
        for (int x = centerX - state.distance; x <= centerX + state.distance; x++) {
            for (int z = centerZ - state.distance; z <= centerZ + state.distance; z++) {
                LevelChunk chunk = state.level.getChunkSource().getChunk(x, z, ChunkStatus.FULL, false);
                if (chunk == null || !neighborsLoaded(state.level, x, z)) {
                    continue;
                }
                for (int y = state.level.getMinSectionY(); y < state.level.getMinSectionY() + state.level.getSectionsCount(); y++) {
                    if (withinRenderDistance(viewport.getTransform(), x, y, z, state.distance * 16.0f)
                        && viewport.isBoxVisible((x << 4) + 8, (y << 4) + 8, (z << 4) + 8)
                        && !chunk.getSection(y - state.level.getMinSectionY()).hasOnlyAir() && !state.renderer.isSectionReady(x, y, z)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    static boolean withinRenderDistance(CameraTransform camera, int x, int y, int z, float distance) {
        float deltaX = nearestAxis((x << 4) - camera.intX) - camera.fracX;
        float deltaY = nearestAxis((y << 4) - camera.intY) - camera.fracY;
        float deltaZ = nearestAxis((z << 4) - camera.intZ) - camera.fracZ;
        return deltaX * deltaX + deltaZ * deltaZ < distance * distance && Math.abs(deltaY) < distance;
    }

    private static int nearestAxis(int origin) {
        return Math.max(origin - 1, Math.min(0, origin + 17));
    }

    private static boolean neighborsLoaded(ClientLevel level, int x, int z) {
        for (int neighborX = x - 1; neighborX <= x + 1; neighborX++) {
            for (int neighborZ = z - 1; neighborZ <= z + 1; neighborZ++) {
                if (level.getChunkSource().getChunk(neighborX, neighborZ, ChunkStatus.FULL, false) == null) {
                    return false;
                }
            }
        }
        return true;
    }

    public enum Preparation {
        COVER, PENDING, READY
    }

    public static final class Handoff implements AutoCloseable {
        private final Handoff previous;
        private final ClientLevel destination;
        private final State state;
        private boolean installed;
        private boolean closed;

        private Handoff(ClientLevel destination, boolean authoritative) {
            previous = handoff;
            this.destination = destination;
            if (AVAILABLE) {
                rememberActive(Minecraft.getInstance());
            }
            State retained = AVAILABLE ? STATES.get(destination) : null;
            if (authoritative) {
                state = retained != null && retained.compatible()
                    && (!PortalShaderScope.irisPresent() || PortalIrisMainPipelines.authoritativeTerrainCompatible(destination)) ? retained : null;
            } else {
                state = ready(destination) || retained != null && retained.viewport != null
                    && destination == Minecraft.getInstance().level && retained.compatible() && compatible(destination)
                    ? retained : null;
            }
            handoff = this;
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            if (handoff != this) {
                throw new IllegalStateException("Normal Sodium terrain handoff scope order");
            }
            closed = true;
            handoff = previous;
        }
    }

    enum WarmStage {
        SKY, SOLID, CUTOUT, TRANSLUCENT, HAND_SOLID, HAND_TRANSLUCENT, POST, COMPLETE;

        private static final WarmStage[] ORDER = values();

        WarmStage next() {
            return this == COMPLETE ? COMPLETE : ORDER[ordinal() + 1];
        }
    }

    static final class State implements AutoCloseable {
        private final ClientLevel level;
        private final SodiumWorldRenderer renderer;
        private final PortalIrisSettings settings;
        private final int distance;
        private final Long2ObjectOpenHashMap<LevelChunk> parkedColumns = new Long2ObjectOpenHashMap<>();
        private LongCollection readyColumns;
        private long used;
        private Viewport viewport;
        private boolean ready;
        private boolean closed;
        private EnvironmentState warmEnvironment;
        private CameraRenderState warmCamera;
        private Object warmPipeline;
        private WarmStage warmStage = WarmStage.SKY;
        private boolean warmFailed;
        private boolean uniformFramePending;

        State(Ownership ownership) {
            level = ownership.level();
            renderer = ownership.renderer();
            settings = ownership.settings();
            distance = ownership.distance();
        }

        boolean warmed() {
            if (!PortalShaderScope.shaders()) {
                return true;
            }
            if (warmEnvironment != null) {
                Object pipeline = PortalIrisMainPipelines.nativePipeline(level);
                if (pipeline == null) {
                    return false;
                }
                selectWarmPipeline(pipeline);
            }
            return !warmFailed && warmStage == WarmStage.COMPLETE;
        }

        void selectWarmPipeline(Object pipeline) {
            if (warmPipeline != pipeline) {
                warmPipeline = pipeline;
                warmStage = WarmStage.SKY;
                ready = false;
            }
        }

        private boolean compatible() {
            PortalIrisSettings current = currentSettings();
            return !closed && distance == Minecraft.getInstance().options.getEffectiveRenderDistance()
                && (settings == null ? current == null : settings.terrainCompatible(current));
        }

        @Override
        public void close() {
            if (!closed) {
                closed = true;
                ready = false;
                uniformFramePending = false;
                parkedColumns.clear();
                renderer.setLevel(null);
            }
        }
    }

    record Ownership(ClientLevel level, SodiumWorldRenderer renderer, PortalIrisSettings settings, int distance) {
    }

    private static final class TerrainCamera extends Camera {
        private final EnvironmentAttributeProbe attributes;
        private final FogType medium;

        private TerrainCamera(EnvironmentState environment, CameraRenderState camera) {
            setPosition(camera.pos);
            setRotation(camera.yRot, camera.xRot);
            setEntity(Minecraft.getInstance().getCameraEntity());
            attributes = new PortalShaderCamera(environment, camera).attributeProbe();
            medium = FogType.valueOf(environment.world().eyeMedium().name());
        }

        @Override
        public EnvironmentAttributeProbe attributeProbe() {
            return attributes;
        }

        @Override
        public FogType getFluidInCamera() {
            return medium;
        }

        @Override
        public boolean isInitialized() {
            return true;
        }
    }
}
