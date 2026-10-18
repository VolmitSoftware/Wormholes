package art.arcane.wormholes.modded.client.world;

import art.arcane.wormholes.modded.client.PreparedChunkColumns;
import art.arcane.wormholes.network.client.ArrivalMessage;
import art.arcane.wormholes.modded.client.render.sodium.SodiumArrivalTerrain;
import art.arcane.wormholes.modded.client.render.iris.IrisPipelines;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import net.minecraft.client.renderer.chunk.CompiledSectionMesh;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionMesh;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceArray;

public final class PreparedTerrainRenderer {
    private static final boolean SODIUM = PreparedTerrainRenderer.class.getClassLoader()
        .getResource("net/caffeinemc/mods/sodium/client/render/SodiumWorldRenderer.class") != null;
    private static final boolean IRIS = PreparedTerrainRenderer.class.getClassLoader()
        .getResource("net/irisshaders/iris/Iris.class") != null;
    private static final int BUILDS_PER_TICK = 4;

    private final ClientLevel level;
    private final Vec3 camera;
    private final int centerX;
    private final int centerZ;
    private final int renderRadius;
    private final List<Long> required = new ArrayList<>();
    private final Set<Long> submitted = new HashSet<>();
    private boolean initialized;
    private long allocated;

    public PreparedTerrainRenderer(ClientLevel level, ArrivalMessage.Prepare prepare) {
        this.level = level;
        camera = new Vec3(prepare.arrival().x(), prepare.arrival().y(), prepare.arrival().z());
        centerX = prepare.centerX();
        centerZ = prepare.centerZ();
        renderRadius = prepare.renderRadius();
    }

    public void prepare() {
        if (!initialized) {
            collectSections();
            Minecraft minecraft = Minecraft.getInstance();
            renderer().invalidateCompiledGeometry(level, minecraft.options, minecraft.gameRenderer.mainCamera(), minecraft.getBlockColors());
            renderer().viewArea().repositionCamera(SectionPos.of(camera));
            renderer().sectionRenderDispatcher().setCameraPosition(camera);
            ClientWorldLoader.retainArrivalGeometry(level);
            initialized = true;
        }
        if (SODIUM) {
            ClientWorldLoader.prepareArrivalTerrain(level, camera, true);
            allocated = SodiumArrivalTerrain.bytes(renderer());
            return;
        }
        LevelRenderer renderer = renderer();
        SectionRenderDispatcher dispatcher = renderer.sectionRenderDispatcher();
        RenderRegionCache regions = new RenderRegionCache();
        int scheduled = 0;
        for (long section : required) {
            if (submitted.contains(section)) {
                continue;
            }
            SectionRenderDispatcher.RenderSection render = renderer.viewArea().getRenderSectionAt(SectionPos.of(section).origin());
            if (render == null || render.getSectionNode() != section) {
                throw new IllegalStateException("Prepared arrival section is outside the renderer view area");
            }
            render.compileAsync(regions.createRegion(level, section));
            submitted.add(section);
            if (++scheduled == BUILDS_PER_TICK) {
                break;
            }
        }
        dispatcher.lock();
        try {
            dispatcher.uploadTerrainBuffersToGpu();
            allocated = vanillaBytes(renderer);
        } finally {
            dispatcher.unlock();
        }
        ClientWorldLoader.worldRenderer(level).rendered();
    }

    public boolean ready() {
        if (!initialized || IRIS && !IrisPipelines.arrivalReady(level)) {
            return false;
        }
        if (SODIUM) {
            return SodiumArrivalTerrain.ready(renderer(), required);
        }
        LevelRenderer renderer = renderer();
        if (renderer.viewArea() == null || renderer.sectionRenderDispatcher() == null
            || !renderer.sectionRenderDispatcher().isQueueEmpty() || submitted.size() != required.size()) {
            return false;
        }
        for (long section : required) {
            SectionRenderDispatcher.RenderSection render = renderer.viewArea().getRenderSectionAt(SectionPos.of(section).origin());
            if (render == null || render.getSectionNode() != section || !uploaded(render.getSectionMesh())) {
                return false;
            }
        }
        return true;
    }

    public long bytes() {
        return allocated;
    }

    public void rebind(ClientLevel target) {
        if (!ready()) {
            throw new IllegalStateException("Arrival renderer is still building terrain");
        }
        if (SODIUM) {
            SodiumArrivalTerrain.rebind(renderer(), target);
        }
    }

    public void adoptChunks(ClientLevel target) {
        if (SODIUM) {
            SodiumArrivalTerrain.adoptChunks(target);
        }
    }

    private LevelRenderer renderer() {
        return ClientWorldLoader.worldRenderer(level).renderer();
    }

    private void collectSections() {
        AtomicReferenceArray<LevelChunk> columns = ((PreparedChunkColumns) level.getChunkSource()).wormholes$columns();
        for (int index = 0; index < columns.length(); index++) {
            LevelChunk chunk = columns.get(index);
            if (chunk == null || Math.abs(chunk.getPos().x() - centerX) > renderRadius
                || Math.abs(chunk.getPos().z() - centerZ) > renderRadius) {
                continue;
            }
            LevelChunkSection[] sections = chunk.getSections();
            for (int section = 0; section < sections.length; section++) {
                if (!sections[section].hasOnlyAir()) {
                    required.add(SectionPos.asLong(chunk.getPos().x(), level.getSectionYFromSectionIndex(section), chunk.getPos().z()));
                }
            }
        }
    }

    static boolean uploaded(SectionMesh mesh) {
        if (mesh == CompiledSectionMesh.UNCOMPILED) {
            return false;
        }
        if (mesh == CompiledSectionMesh.EMPTY) {
            return true;
        }
        if (!(mesh instanceof CompiledSectionMesh compiled)) {
            return false;
        }
        for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
            if (!compiled.isEmpty(layer) && (!compiled.isVertexBufferUploaded(layer) || !compiled.isIndexBufferUploaded(layer))) {
                return false;
            }
        }
        return true;
    }

    private long vanillaBytes(LevelRenderer renderer) {
        long bytes = 0L;
        Set<Object> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (long section : required) {
            BlockPos origin = SectionPos.of(section).origin();
            SectionRenderDispatcher.RenderSection render = renderer.viewArea().getRenderSectionAt(origin);
            if (render == null || !uploaded(render.getSectionMesh())) {
                continue;
            }
            for (ChunkSectionLayer layer : ChunkSectionLayer.values()) {
                if (render.getSectionMesh().isEmpty(layer)) {
                    continue;
                }
                SectionRenderDispatcher.RenderSectionBufferSlice buffers = renderer.sectionRenderDispatcher()
                    .getRenderSectionSlice(render.getSectionMesh(), layer);
                if (seen.add(buffers.vertexBuffer())) {
                    bytes += buffers.vertexBuffer().size();
                }
                if (buffers.indexBuffer() != null && seen.add(buffers.indexBuffer())) {
                    bytes += buffers.indexBuffer().size();
                }
            }
        }
        return bytes;
    }
}
