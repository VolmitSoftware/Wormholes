package art.arcane.wormholes.modded.client.world;

import art.arcane.wormholes.modded.client.ClientArrivalPreparation;
import art.arcane.wormholes.modded.client.PreparedChunkColumns;
import art.arcane.wormholes.modded.client.render.WormholesRenderers;
import art.arcane.wormholes.modded.client.render.iris.IrisPipelines;
import art.arcane.wormholes.modded.client.render.sodium.SodiumArrivalTerrain;
import art.arcane.wormholes.modded.mixin.client.ClientWorldLevelRendererAccess;
import art.arcane.wormholes.modded.mixin.client.PreparedVertexBufferAccess;
import art.arcane.wormholes.modded.mixin.client.PreparedViewAreaAccess;
import art.arcane.wormholes.network.client.ArrivalMessage;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.ChunkRenderDispatcher;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** Compiles the terrain of a prepared arrival level in its own renderer ahead of the crossing. */
public final class PreparedTerrainRenderer implements ClientArrivalPreparation.Terrain {
    private static final boolean SODIUM = WormholesRenderers.installed(WormholesRenderers.SODIUM);
    private static final boolean IRIS = WormholesRenderers.installed(WormholesRenderers.IRIS);
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

    @Override
    public void prepare() {
        if (!initialized) {
            collectSections();
            if (!SODIUM) {
                viewArea().repositionCamera(camera.x, camera.z);
                dispatcher().setCamera(camera);
            }
            initialized = true;
        }
        if (SODIUM) {
            ClientWorldLoader.prepareArrivalTerrain(level, camera, true);
            allocated = SodiumArrivalTerrain.bytes(renderer());
            return;
        }
        ChunkRenderDispatcher dispatcher = dispatcher();
        RenderRegionCache regions = new RenderRegionCache();
        int scheduled = 0;
        for (long section : required) {
            if (submitted.contains(section)) {
                continue;
            }
            ChunkRenderDispatcher.RenderChunk render = section(section);
            if (render == null) {
                throw new IllegalStateException("Prepared arrival section is outside the renderer view area");
            }
            render.rebuildChunkAsync(dispatcher, regions);
            render.setNotDirty();
            submitted.add(section);
            if (++scheduled == BUILDS_PER_TICK) {
                break;
            }
        }
        dispatcher.uploadAllPendingUploads();
        allocated = vanillaBytes();
        ClientWorldLoader.worldRenderer(level).rendered();
    }

    @Override
    public boolean ready() {
        if (!initialized || IRIS && !IrisPipelines.arrivalReady(level)) {
            return false;
        }
        if (SODIUM) {
            return SodiumArrivalTerrain.ready(renderer(), required);
        }
        ChunkRenderDispatcher dispatcher = renderer().getChunkRenderDispatcher();
        if (viewArea() == null || dispatcher == null || !dispatcher.isQueueEmpty() || submitted.size() != required.size()) {
            return false;
        }
        for (long section : required) {
            ChunkRenderDispatcher.RenderChunk render = section(section);
            if (render == null || render.getCompiledChunk() == ChunkRenderDispatcher.CompiledChunk.UNCOMPILED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public long bytes() {
        return allocated;
    }

    @Override
    public void rebind(ClientLevel target) {
        if (!ready()) {
            throw new IllegalStateException("Arrival renderer is still building terrain");
        }
        if (SODIUM) {
            SodiumArrivalTerrain.rebind(renderer(), target);
        } else {
            ((ClientWorldLevelRendererAccess) renderer()).wormholes$level(target);
            dispatcher().setLevel(target);
        }
    }

    @Override
    public void adoptChunks(ClientLevel target) {
        if (SODIUM) {
            SodiumArrivalTerrain.adoptChunks(renderer(), target);
        }
    }

    private LevelRenderer renderer() {
        return ClientWorldLoader.worldRenderer(level).renderer();
    }

    private ViewArea viewArea() {
        return ((ClientWorldLevelRendererAccess) renderer()).wormholes$viewArea();
    }

    private ChunkRenderDispatcher dispatcher() {
        return renderer().getChunkRenderDispatcher();
    }

    private ChunkRenderDispatcher.RenderChunk section(long section) {
        ChunkRenderDispatcher.RenderChunk render = ((PreparedViewAreaAccess) viewArea()).wormholes$section(SectionPos.of(section).origin());
        return render != null && render.getOrigin().equals(SectionPos.of(section).origin()) ? render : null;
    }

    private void collectSections() {
        AtomicReferenceArray<LevelChunk> columns = ((PreparedChunkColumns) level.getChunkSource()).wormholes$columns();
        for (int index = 0; index < columns.length(); index++) {
            LevelChunk chunk = columns.get(index);
            if (chunk == null || Math.abs(chunk.getPos().x - centerX) > renderRadius || Math.abs(chunk.getPos().z - centerZ) > renderRadius) {
                continue;
            }
            LevelChunkSection[] sections = chunk.getSections();
            for (int section = 0; section < sections.length; section++) {
                if (!sections[section].hasOnlyAir()) {
                    required.add(SectionPos.asLong(chunk.getPos().x, level.getSectionYFromSectionIndex(section), chunk.getPos().z));
                }
            }
        }
    }

    private long vanillaBytes() {
        long bytes = 0L;
        for (long section : required) {
            ChunkRenderDispatcher.RenderChunk render = section(section);
            if (render == null || render.getCompiledChunk() == ChunkRenderDispatcher.CompiledChunk.UNCOMPILED) {
                continue;
            }
            for (RenderType layer : RenderType.chunkBufferLayers()) {
                if (render.getCompiledChunk().isEmpty(layer)) {
                    continue;
                }
                VertexBuffer buffer = render.getBuffer(layer);
                VertexFormat format = buffer.getFormat();
                if (format != null) {
                    long indices = ((PreparedVertexBufferAccess) buffer).wormholes$indexCount();
                    bytes += indices / 6L * 4L * format.getVertexSize();
                }
            }
        }
        return bytes;
    }
}
