package art.arcane.wormholes.clientgametest;

import java.lang.reflect.Method;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.stream.EnvironmentState;
import art.arcane.wormholes.modded.client.render.stencil.PortalSurface;
import art.arcane.wormholes.modded.client.render.stencil.PortalViews;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.ClientPortal;
import java.util.Iterator;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.client.render.dh.DhBlazeClipPrograms;
import art.arcane.wormholes.modded.client.render.dh.DhBlazeClipTransformation;
import art.arcane.wormholes.modded.client.render.dh.DhClipPrograms;
import art.arcane.wormholes.modded.client.render.dh.DhClipTransformation;
import art.arcane.wormholes.modded.client.render.iris.IrisClipPrograms;
import art.arcane.wormholes.modded.client.render.iris.IrisViewPipelines;
import art.arcane.wormholes.modded.client.render.iris.IrisClipPlanes;
import art.arcane.wormholes.modded.client.render.iris.IrisClipTransformation;
import art.arcane.wormholes.modded.client.render.stencil.PortalLayer;
import art.arcane.wormholes.modded.mixin.client.DhPortalClientApiAccess;
import art.arcane.wormholes.modded.mixin.client.DhPortalBlazeTemporalAccess;
import com.seibel.distanthorizons.common.render.blaze.wrappers.texture.BlazeTextureWrapper;
import com.seibel.distanthorizons.core.util.math.DhVec3d;
import com.google.gson.GsonBuilder;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.math.DhApiMat4f;
import com.seibel.distanthorizons.api.objects.math.DhApiVec3f;
import com.seibel.distanthorizons.core.api.internal.ClientApi;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.render.CameraZoom;
import com.seibel.distanthorizons.core.render.DhApiRenderProxy;
import com.seibel.distanthorizons.core.render.RenderBufferHandler;
import com.seibel.distanthorizons.core.render.RenderParams;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import net.irisshaders.iris.shadows.ShadowRenderingState;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4d;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11C;
import org.lwjgl.opengl.GL13C;
import com.mojang.blaze3d.opengl.GlTexture;
import com.mojang.blaze3d.opengl.GlSampler;
import com.mojang.blaze3d.textures.GpuTextureView;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL21C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL31C;
import org.lwjgl.opengl.GL32C;
import org.lwjgl.opengl.GL33C;
import org.lwjgl.system.MemoryStack;
import com.mojang.blaze3d.opengl.GlStateManager;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import org.lwjgl.system.MemoryUtil;

public final class DistantHorizonsTap {
    private static final ArrayDeque<Scope> LAYERS = new ArrayDeque<>();
    private static final Map<Key, Sample> SAMPLES = new HashMap<>();
    private static final ArrayDeque<Pending> QUERIES = new ArrayDeque<>();
    private static final List<Checkpoint> CHECKPOINTS = new ArrayList<>();
    private static final Map<Key, History> HISTORIES = new HashMap<>();
    private static final Map<HistoryPath, History> PATH_HISTORIES = new HashMap<>();
    private static final Map<Object, HistorySample> CAMERA_HISTORIES = new IdentityHashMap<>();
    private static final Map<Key, RenderBufferHandler> BUFFERS = new HashMap<>();
    private static final Set<Integer> CLIP_DISTANCES = new HashSet<>();
    private static final Set<Integer> SHADER_DUMPS = new HashSet<>();
    private static final Map<String, Integer> STAGE_COUNTS = new HashMap<>();
    private static final Map<Key, Integer> COPY_COUNTS = new HashMap<>();
    private static final Map<Integer, CopyPending> COPY_QUERIES = new HashMap<>();
    private static final List<CopyEvidence> COPY_EVIDENCE = new ArrayList<>();
    private static final Set<BlazeTextureWrapper> TEMPORAL_TEXTURES = Collections.newSetFromMap(new IdentityHashMap<>());
    private static final Map<BlazeTextureWrapper, BlazeResource> TEMPORAL_RESOURCES = new IdentityHashMap<>();
    private static boolean enabled;
    private static long virtualMainDraws;
    private static String virtualMainWorld;
    private static boolean shaders;
    private static String failure;
    private static RenderParams rendering;
    private static DhApiVec3f offset;
    private static int bufferWidth;
    private static int draws;
    private static int temporalFrame;
    private static CopyState parentCopyBefore;
    private static int parentCopyFrame;
    private static int parentCopyProbes;

    private DistantHorizonsTap() {
    }

    public static void start(boolean shaderMode) {
        stop();
        verifyCopyClipScopes();
        SAMPLES.clear();
        HISTORIES.clear();
        CAMERA_HISTORIES.clear();
        PATH_HISTORIES.clear();
        BUFFERS.clear();
        CHECKPOINTS.clear();
        CLIP_DISTANCES.clear();
        SHADER_DUMPS.clear();
        STAGE_COUNTS.clear();
        COPY_COUNTS.clear();
        COPY_EVIDENCE.clear();
        TEMPORAL_TEXTURES.clear();
        TEMPORAL_RESOURCES.clear();
        NativeDhTemporalAssertions.reset();
        draws = 0;
        temporalFrame = 0;
        parentCopyBefore = null;
        parentCopyProbes = 0;
        virtualMainDraws = 0L;
        virtualMainWorld = null;
        failure = null;
        shaders = shaderMode;
        if (shaders) {
            IrisPipelineCostTap.start();
        }
        enabled = true;
    }

    public static void dumpAnimatedUniforms(String stage) {
        if (!enabled || !shaders) {
            throw new IllegalStateException("DH shader uniform capture requires active shader acceptance");
        }
        StringBuilder values = new StringBuilder();
        Minecraft minecraft = Minecraft.getInstance();
        values.append("frame=").append(SystemTimeUniforms.COUNTER.getAsInt()).append(" level=").append(minecraft.level.dimension().identifier())
            .append(" bossFog=").append(minecraft.gui.hud.getBossOverlay().shouldCreateWorldFog()).append('\n');
        for (int program = 1; program <= 4096; program++) {
            if (!GL20C.glIsProgram(program) || GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == 0) {
                continue;
            }
            for (String uniform : List.of("frameTimeCounter", "frameTime", "frameTimeSmooth", "frameCounter", "endFlashIntensity", "endFlashIntensityM",
                "heavyFog", "vlFactor", "blindFactor", "darknessFactor")) {
                int location = GL20C.glGetUniformLocation(program, uniform);
                if (location >= 0) {
                    values.append(program).append(' ').append(uniform).append('=').append(
                        uniform.equals("frameCounter") || uniform.equals("heavyFog")
                            ? GL20C.glGetUniformi(program, location) : GL20C.glGetUniformf(program, location)).append('\n');
                }
            }
        }
        try {
            Path directory = Path.of("dh-shaders");
            Files.createDirectories(directory);
            Files.writeString(directory.resolve(stage + "-animated-uniforms.txt"), values.toString());
        } catch (IOException failure) {
            throw new UncheckedIOException("Unable to retain DH animated shader uniforms", failure);
        }
    }

    public static void stop() {
        if (shaders) {
            IrisPipelineCostTap.stop();
        }
        enabled = false;
        CAMERA_HISTORIES.clear();
        PATH_HISTORIES.clear();
        for (Pending query : QUERIES) {
            GL15C.glDeleteQueries(query.id());
        }
        QUERIES.clear();
        for (int query : COPY_QUERIES.keySet()) {
            GL15C.glDeleteQueries(query);
        }
        COPY_QUERIES.clear();
        LAYERS.clear();
        rendering = null;
        offset = null;
        BUFFERS.clear();
    }

    public static void beginLayer() {
        if (enabled) {
            LAYERS.push(new Scope(Minecraft.getInstance().level, ClientApi.RENDER_STATE.clientLevelWrapper,
                new DhMat4f(ClientApi.RENDER_STATE.mcProjectionMatrix), new DhMat4f(ClientApi.RENDER_STATE.mcModelViewMatrix)));
        }
    }

    public static void layer(PortalLayer layer) {
        if (enabled && !LAYERS.isEmpty()) {
            LAYERS.peek().layer = layer;
        }
    }

    public static void endLayer() {
        if (!enabled) {
            return;
        }
        Scope parent = LAYERS.pop();
        if (Minecraft.getInstance().level != parent.level || ClientApi.RENDER_STATE.clientLevelWrapper != parent.wrapper
            || ClientApi.RENDER_STATE.mcProjectionMatrix != parent.projectionReference
            || ClientApi.RENDER_STATE.mcModelViewMatrix != parent.modelViewReference
            || !matrix(ClientApi.RENDER_STATE.mcProjectionMatrix).equals(matrix(parent.projection), 0.0001F)
            || !matrix(ClientApi.RENDER_STATE.mcModelViewMatrix).equals(matrix(parent.modelView), 0.0001F)) {
            fail("DH parent level, projection or modelview was not restored after a portal layer");
        }
        if (!parent.parameters.matches(DhPortalClientApiAccess.wormholes$renderParams())) {
            fail("DH nested layer changed its parent's render parameters, API copy or camera references");
        }
        if (parent.iris != null && !parent.iris.restored()) {
            fail("DH nested layer changed its parent's Iris event flags or priority-10 override bindings");
        }
        if (!parent.clipping.restored() || parent.distances != clipDistances() || !parent.state.equals(RenderState.capture())) {
            fail("DH nested layer did not restore its parent's clip plane, all eight clip distances or runtime state: plane="
                + parent.clipping.restored() + " distances=" + parent.distances + " -> " + clipDistances()
                + " state=" + parent.state + " -> " + RenderState.capture());
        }
        poll();
    }

    public static void beginTerrain(RenderParams params) {
        if (!enabled) {
            return;
        }
        poll();
        rendering = params;
        offset = null;
        Sample previous = SAMPLES.get(key(layer()));
        if (previous != null) {
            previous.queries = 0;
        }
        PortalLayer layer = layer();
        if (!shadow()) {
            BUFFERS.put(key(layer), params.renderBufferHandler);
        }
        if (layer == null && !shadow()) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null && minecraft.level != minecraft.player.level()) {
                if (params.clientLevelWrapper == null || params.clientLevelWrapper.getWrappedMcObject() != minecraft.level) {
                    fail("DH virtual main-camera draw selected the player's world instead of the camera's world");
                }
                Vec3 actual = new Vec3(params.exactCameraPosition.x, params.exactCameraPosition.y, params.exactCameraPosition.z);
                if (actual.distanceTo(minecraft.gameRenderer.mainCamera().position()) > 0.001D) {
                    fail("DH virtual main-camera position differs from the active Minecraft camera");
                }
            }
        }
        if (layer == null || shadow()) {
            return;
        }
        if (params.dhClientLevel == null || params.clientLevelWrapper == null
            || params.clientLevelWrapper.getWrappedMcObject() != layer.level()
            || params.dhClientLevel.getClientLevelWrapper() != params.clientLevelWrapper) {
            fail("DH rendered a different level than the resident destination");
        }
        Vec3 camera = new Vec3(params.exactCameraPosition.x, params.exactCameraPosition.y, params.exactCameraPosition.z);
        if (camera.distanceTo(layer.camera().pos) > 0.001D
            || !matrix(params.mcModelViewMatrix).equals(layer.camera().viewRotationMatrix, 0.0001F)
            || !matrix(params.dhModelViewMatrix).equals(layer.camera().viewRotationMatrix, 0.0001F)
            || !matrix(params.mcProjectionMatrix).equals(layer.camera().projectionMatrix, 0.0001F)) {
            fail("DH camera, Minecraft projection or modelview differs from the virtual portal camera");
        }
        if (!matrix(params.dhProjectionMatrix).isFinite() || !matrix(params.dhModelViewMatrix).isFinite()
            || !matrix(params.dhMvmProjMatrix).isFinite() || !matrix(params.dhInverseMvmProjectionMatrix).isFinite()
            || !matrix(params.mcInverseMvmProjectionMatrix).isFinite()) {
            fail("DH render matrices contain non-finite values");
        }
        verifyInverseCoordinates(params);
    }

    public static int beginBlazeTerrain(RenderParams params) {
        int distances = enabled ? clipDistances() : 0;
        beginTerrain(params);
        return distances;
    }

    public static void endBlazeTerrain(int distances) {
        if (enabled && clipDistances() != distances) {
            fail("DH Blaze terrain pass did not restore all eight clip distances");
        }
        endTerrain();
    }

    public static void endTerrain() {
        rendering = null;
        offset = null;
    }

    public static void modelOffset(DhApiVec3f position) {
        if (enabled) {
            offset = position.copy();
        }
    }

    public static void buffer(LodBufferContainer container) {
        if (enabled) {
            bufferWidth = DhSectionPos.getBlockWidth(container.pos);
            if (rendering != null && rendering.clientLevelWrapper != null
                && container.minCornerBlockPos.getY() != rendering.clientLevelWrapper.getMinHeight()) {
                fail("Actual DH terrain buffer uses height origin " + container.minCornerBlockPos.getY()
                    + " instead of destination minimum " + rendering.clientLevelWrapper.getMinHeight());
            }
        }
    }

    public static int beforeDraw(int count) {
        return beforeDraw(count, false);
    }

    public static int beforeBlazeDraw(int count) {
        return beforeDraw(count, true);
    }

    public static void verifyBlazeDraw(int count) {
        if (enabled && rendering != null && offset != null && count > 0) {
            verifyDraw(SAMPLES.get(key(layer())), true);
        }
    }

    public static void afterDraw(int query) {
        if (query != 0) {
            GL15C.glEndQuery(GL15C.GL_SAMPLES_PASSED);
        }
    }

    public static void stageImage(String stage, GpuTextureView texture) {
        PortalLayer layer = layer();
        if (!enabled || shaders || layer == null || texture == null) {
            return;
        }
        Key key = key(layer);
        if (key.depth() != 1 || !key.origin().equals(new BlockPos(0, 70, 20))) {
            return;
        }
        int ordinal = STAGE_COUNTS.merge(stage, 1, Integer::sum);
        if (ordinal == 30) {
            copyInputImage(texture, stage);
            System.out.println("WORMHOLES_DH_STAGE_IMAGE stage=" + stage + " texture=" + ((GlTexture) texture.texture()).glId());
        }
    }

    public static void terrainImage() {
        copyInputImage(Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTextureView(), "sodium-child-opaque");
    }

    public static int beginCopy(GpuTextureView input, GpuTextureView destination) {
        PortalLayer layer = layer();
        if (!enabled || shaders || destination.texture() != Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTexture()) {
            return 0;
        }
        if (layer == null) {
            int childCopies = COPY_COUNTS.getOrDefault(new Key(new BlockPos(0, 70, 20), 1), 0);
            if (childCopies >= 29 && childCopies <= 31 && parentCopyProbes < 3) {
                parentCopyProbes++;
                parentCopyFrame = TravelTap.frameIndex();
                parentCopyBefore = copyState(((GlTexture) input.texture()).glId());
                copyImage("dh-parent-copy-before-" + parentCopyFrame + "-" + parentCopyProbes);
                copyInputImage(input, "dh-parent-copy-input-" + parentCopyFrame + "-" + parentCopyProbes);
                return -1;
            }
            return 0;
        }
        if (COPY_EVIDENCE.size() >= 24) {
            return 0;
        }
        Key key = key(layer);
        int ordinal = COPY_COUNTS.merge(key, 1, Integer::sum);
        if (ordinal != 1 && ordinal != 30 && ordinal != 120) {
            return 0;
        }
        if (GL15C.glGetQueryi(GL15C.GL_SAMPLES_PASSED, GL15C.GL_CURRENT_QUERY) != 0) {
            fail("DH copy probe encountered an already active GPU samples query");
            return 0;
        }
        boolean image = ordinal == 30 && key.depth() == 1 && key.origin().equals(new BlockPos(0, 70, 20));
        if (image) {
            copyImage("dh-copy-before");
            copyInputImage(input, "dh-copy-input");
        }
        int inputTexture = ((GlTexture) input.texture()).glId();
        CopyState before = copyState(inputTexture);
        int query = GL15C.glGenQueries();
        COPY_QUERIES.put(query, new CopyPending(key, ordinal, image, inputTexture, before));
        GL15C.glBeginQuery(GL15C.GL_SAMPLES_PASSED, query);
        return query;
    }

    public static void endCopy(int query) {
        if (query == -1) {
            System.out.println("WORMHOLES_DH_PARENT_COPY_PROBE frame=" + parentCopyFrame + " before=" + parentCopyBefore
                + " after=" + copyState(parentCopyBefore.inputTexture()));
            copyImage("dh-parent-copy-after-" + parentCopyFrame + "-" + parentCopyProbes);
            parentCopyBefore = null;
            return;
        }
        if (query == 0) {
            return;
        }
        GL15C.glEndQuery(GL15C.GL_SAMPLES_PASSED);
        CopyPending pending = COPY_QUERIES.remove(query);
        try {
            long samples = Integer.toUnsignedLong(GL15C.glGetQueryObjectui(query, GL15C.GL_QUERY_RESULT));
            CopyEvidence evidence = new CopyEvidence(pending.key(), pending.ordinal(), samples, pending.before(), copyState(pending.inputTexture()));
            COPY_EVIDENCE.add(evidence);
            System.out.println("WORMHOLES_DH_COPY_PROBE frame=" + TravelTap.frameIndex() + " " + evidence);
            if (pending.image()) {
                copyImage("dh-copy-after");
            }
        } finally {
            GL15C.glDeleteQueries(query);
        }
    }

    public static void history(Object pipeline, int first, int second, int frame) {
        if (!enabled) {
            return;
        }
        Key key = key(layer());
        HistorySample old = CAMERA_HISTORIES.get(pipeline);
        if (!GL11C.glIsTexture(first) || !GL11C.glIsTexture(second)) {
            fail("DH shader acceptance requires actual live colortex5 temporal textures");
        }
        int renders = old != null ? old.history().renders() + 1 : 1;
        History history = new History(pipeline, first, second, renders, frame, null);
        CAMERA_HISTORIES.put(pipeline, new HistorySample(key, history));
        HISTORIES.put(key, history);
        PATH_HISTORIES.put(historyPath(), history);
    }

    public static Map<HistoryPath, History> nestedHistorySnapshot() {
        History main = mainHistory();
        Map<HistoryPath, History> histories = new HashMap<>();
        for (Map.Entry<HistoryPath, History> entry : PATH_HISTORIES.entrySet()) {
            if (entry.getKey().cameras().size() >= 2 && entry.getKey().cameras().stream()
                .allMatch(camera -> camera.kind() == PortalView.Kind.MIRROR)
                && main != null && entry.getValue().frame() == main.frame()) {
                histories.put(entry.getKey(), entry.getValue());
            }
        }
        if (histories.size() < 2) {
            throw new AssertionError("DH acceptance requires two actual complete nested mirror histories");
        }
        return Map.copyOf(histories);
    }

    public static void assertNestedHistoryStable(Map<HistoryPath, History> expected, boolean rendered) {
        History main = mainHistory();
        Set<Integer> textures = new HashSet<>();
        for (Map.Entry<HistoryPath, History> entry : expected.entrySet()) {
            History current = PATH_HISTORIES.get(entry.getKey());
            History previous = entry.getValue();
            if (current == null || current.pipeline() != previous.pipeline() || current.first() != previous.first()
                || current.second() != previous.second() || !GL11C.glIsTexture(current.first())
                || !GL11C.glIsTexture(current.second()) || !textures.add(current.first()) || !textures.add(current.second())
                || main == null || current.first() == main.first() || current.first() == main.second()
                || current.second() == main.first() || current.second() == main.second()
                || rendered && current.frame() != main.frame()) {
                throw new AssertionError("Complete nested DH camera history changed or stopped rendering: " + entry.getKey());
            }
        }
    }

    private static HistoryPath historyPath() {
        List<HistoryStep> cameras = new ArrayList<>(LAYERS.size());
        Iterator<Scope> iterator = LAYERS.descendingIterator();
        while (iterator.hasNext()) {
            PortalLayer layer = iterator.next().layer;
            if (layer != null) {
                PortalView view = layer.view();
                cameras.add(new HistoryStep(view.kind(), view.historyKey(), view.source(), view.destination(), view.toDestination()));
            }
        }
        return new HistoryPath(cameras);
    }

    private static Map<String, Map<String, String>> mirrorProofEvidence() {
        WormholesClient client = WormholesClient.instance();
        if (client == null) {
            return Map.of();
        }
        PortalViews views = client.portalViews();
        List<PortalView> pending = new ArrayList<>(views.current());
        Set<Object> visited = new HashSet<>();
        Map<String, Map<String, String>> evidence = new HashMap<>();
        try {
            Method toward = PortalViews.class.getDeclaredMethod("toward", ApertureDescriptor.class, EnvironmentState.class);
            toward.setAccessible(true);
            for (int index = 0; index < pending.size(); index++) {
                PortalView view = pending.get(index);
                if (!visited.add(view.key())) {
                    continue;
                }
                pending.addAll(views.children(view));
                if (view.kind() != PortalView.Kind.MIRROR) {
                    continue;
                }
                int slot = views.streamKey(view);
                ClientPortal portal = client.session().portal(slot);
                EnvironmentState environment = client.session().environment(slot);
                Map<String, String> row = new HashMap<>();
                row.put("history", view.historyKey().toString());
                row.put("slot", Integer.toString(slot));
                row.put("sameWorld", Boolean.toString(view.source() == view.destination()));
                row.put("affine", Boolean.toString(client.session().affine(slot) != null));
                row.put("geometry", view.surface().geometry().toString());
                row.put("geometryMatches", Boolean.toString(portal != null && portal.geometry().equals(view.surface().geometry())));
                Vec3d zero = new Vec3d(0.0D, 0.0D, 0.0D);
                row.put("viewZero", view.toDestination().point(zero).toString());
                row.put("reflectionZero", PortalSurface.mirrorTransform(view.surface().geometry()).point(zero).toString());
                row.put("environment", String.valueOf(environment));
                if (environment != null) {
                    Similarity transform = (Similarity) toward.invoke(null, view.surface().geometry(), environment);
                    row.put("environmentZero", transform.point(zero).toString());
                    row.put("environmentTransform", transform.toString());
                }
                evidence.put(view.key().toString(), Map.copyOf(row));
            }
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Unable to capture native mirror proof evidence", failure);
        }
        return Map.copyOf(evidence);
    }

    private static Map<String, HistoryEvidence> pathHistoryEvidence() {
        Map<String, HistoryEvidence> evidence = new HashMap<>();
        for (Map.Entry<HistoryPath, History> entry : PATH_HISTORIES.entrySet()) {
            History history = entry.getValue();
            evidence.put(entry.getKey().toString(), new HistoryEvidence(System.identityHashCode(history.pipeline()), history.first(),
                history.second(), history.renders(), history.frame(),
                history.resources() == null ? null : history.resources().evidence()));
        }
        return evidence;
    }

    public static History history(BlockPos origin, int depth) {
        return HISTORIES.get(new Key(origin, depth));
    }

    public static List<History> histories(BlockPos origin, int depth) {
        History main = mainHistory();
        if (main == null) {
            return List.of();
        }
        Key key = new Key(origin, depth);
        List<History> histories = new ArrayList<>();
        for (HistorySample sample : CAMERA_HISTORIES.values()) {
            if (sample.key().equals(key) && sample.history().frame() == main.frame()) {
                histories.add(sample.history());
            }
        }
        return histories;
    }

    public static History mainHistory() {
        return HISTORIES.get(new Key(BlockPos.ZERO, 0));
    }

    public static boolean temporalHistorySupported() {
        return true;
    }

    public static void temporalFrame() {
        if (enabled && !shaders) {
            temporalFrame++;
        }
    }

    public static void blazeHistory(RenderParams parameters, DhPortalBlazeTemporalAccess access) {
        if (!enabled || shaders) {
            return;
        }
        BlazeTextureWrapper first = access.wormholes$colorA();
        BlazeTextureWrapper second = access.wormholes$colorB();
        BlazeTextureWrapper depth = access.wormholes$depth();
        TEMPORAL_TEXTURES.add(first);
        TEMPORAL_TEXTURES.add(second);
        TEMPORAL_TEXTURES.add(depth);
        int firstId = ((GlTexture) first.getTexture()).glId();
        int secondId = ((GlTexture) second.getTexture()).glId();
        int depthId = ((GlTexture) depth.getTexture()).glId();
        if (!GL11C.glIsTexture(depthId) || firstId == secondId || firstId == depthId || secondId == depthId) {
            fail("Actual DH TAA did not retain three separate live color and depth textures");
        }
        DhVec3d previous = access.wormholes$previousCamera();
        DhVec3d camera = parameters.exactCameraPosition;
        DhMat4f expected = new DhMat4f(parameters.dhProjectionMatrix);
        expected.multiply(parameters.dhModelViewMatrix);
        if (previous.x != camera.x || previous.y != camera.y || previous.z != camera.z
            || !matrix(access.wormholes$previousMatrix()).equals(matrix(expected), 0.0001F)) {
            fail("Actual DH TAA previous camera or MVP differs from its own rendered virtual camera");
        }
        BlazeHistoryResources resources = new BlazeHistoryResources(List.of(captureBlazeResource(first),
            captureBlazeResource(second), captureBlazeResource(depth)));
        history(first, firstId, secondId, temporalFrame);
        Key key = key(layer());
        History sample = HISTORIES.get(key);
        History complete = new History(sample.pipeline(), sample.first(), sample.second(), sample.renders(), sample.frame(), resources);
        HISTORIES.put(key, complete);
        PATH_HISTORIES.put(historyPath(), complete);
        CAMERA_HISTORIES.put(complete.pipeline(), new HistorySample(key, complete));
    }

    private static BlazeResource captureBlazeResource(BlazeTextureWrapper wrapper) {
        BlazeResource resource = TEMPORAL_RESOURCES.get(wrapper);
        if (resource == null || !resource.matches(wrapper)) {
            resource = new BlazeResource(wrapper);
            TEMPORAL_RESOURCES.put(wrapper, resource);
        }
        return resource;
    }

    public static void releasedBlazeTexture(BlazeTextureWrapper wrapper) {
        if (!TEMPORAL_TEXTURES.remove(wrapper)) {
            return;
        }
        GlTexture texture = (GlTexture) wrapper.getTexture();
        GlSampler sampler = (GlSampler) wrapper.getTextureSampler();
        if (!wrapper.getTextureView().isClosed() || !texture.isClosed() || GL11C.glIsTexture(texture.glId())
            || !sampler.isClosed() || GL33C.glIsSampler(sampler.getId())) {
            throw new AssertionError("Distant Horizons temporal cleanup retained an owned view, GPU texture or sampler");
        }
        BlazeResource resource = TEMPORAL_RESOURCES.get(wrapper);
        if (resource == null) {
            throw new AssertionError("Distant Horizons cleanup has no sampled native resources: " + wrapper.getName());
        }
        if (!resource.matches(wrapper)) {
            throw new AssertionError("Distant Horizons cleanup closed different native resources than its history sample: "
                + resource.evidence());
        }
        resource.releaseObserved = true;
        System.out.println("WORMHOLES_DH_TAA_TEXTURE_RELEASE_PASS name=" + wrapper.getName() + " texture=" + texture.glId()
            + " resources=" + resource.evidence());
    }

    public static long visibleSamples(BlockPos origin, int depth) {
        poll();
        Sample sample = SAMPLES.get(new Key(origin, depth));
        return sample == null ? 0L : sample.visibleSamples;
    }

    public static RenderBufferHandler buffers(BlockPos origin, int depth) {
        return BUFFERS.get(new Key(origin, depth));
    }

    public static int draws() {
        return draws;
    }

    public static long virtualMainDraws() {
        return virtualMainDraws;
    }

    public static String virtualMainWorld() {
        return virtualMainWorld;
    }

    public static String failure() {
        return failure;
    }

    public static void checkpoint(String name) {
        poll();
        Map<String, Sample> samples = new HashMap<>();
        for (Map.Entry<Key, Sample> entry : SAMPLES.entrySet()) {
            samples.put(entry.getKey().toString(), entry.getValue().copy());
            entry.getValue().queries = 0;
        }
        IrisPipelineCostTap.Counts costs = shaders ? IrisPipelineCostTap.snapshot() : null;
        Map<String, HistoryEvidence> histories = new HashMap<>();
        for (Map.Entry<Key, History> entry : HISTORIES.entrySet()) {
            History history = entry.getValue();
            histories.put(entry.getKey().toString(), new HistoryEvidence(System.identityHashCode(history.pipeline()), history.first(),
                history.second(), history.renders(), history.frame(),
                history.resources() == null ? null : history.resources().evidence()));
        }
        CHECKPOINTS.add(new Checkpoint(name, costs, samples, histories, virtualMainDraws, virtualMainWorld, shaders ? IrisViewPipelines.cameraSnapshots() : null,
            shaders ? IrisViewPipelines.diagnostics() : null, pathHistoryEvidence(), mirrorProofEvidence()));
        System.out.println("DH acceptance " + name + ": costs=" + costs + " draws=" + draws + " samples=" + samples);
        RenderParams params = DhPortalClientApiAccess.wormholes$renderParams();
        System.out.println("DH state validation=" + ClientApi.INSTANCE.lastRenderParamValidationMessage
            + " disabled=" + ClientApi.INSTANCE.rendererDisabledBecauseOfExceptions + " wrapper=" + ClientApi.RENDER_STATE.clientLevelWrapper
            + " paramsWrapper=" + params.clientLevelWrapper + " level=" + params.dhClientLevel
            + " lightmap=" + params.lightmap + " buffers=" + params.renderBufferHandler);
    }

    public static void writeEvidence() {
        try {
            Files.writeString(Path.of("dh-acceptance.json"), new GsonBuilder().setPrettyPrinting().create()
                .toJson(new Evidence(shaders, failure, CHECKPOINTS, COPY_EVIDENCE)) + "\n");
        } catch (IOException error) {
            throw new UncheckedIOException("Unable to retain DH GPU acceptance evidence", error);
        }
    }

    private static CopyState copyState(int inputTexture) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            ByteBuffer color = stack.malloc(4);
            ByteBuffer stencil = stack.malloc(4);
            ByteBuffer writeMask = stack.malloc(4);
            GL11C.glGetBooleanv(GL11C.GL_COLOR_WRITEMASK, writeMask);
            int program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
            int sampler = program == 0 ? -1 : GL20C.glGetUniformLocation(program, "uCopyTexture");
            int unit = sampler < 0 ? -1 : GL20C.glGetUniformi(program, sampler);
            int texture = 0;
            int activeTexture = GL11C.glGetInteger(GL13C.GL_ACTIVE_TEXTURE);
            if (unit >= 0) {
                try {
                    GlStateManager._activeTexture(GL13C.GL_TEXTURE0 + unit);
                    texture = GL11C.glGetInteger(GL11C.GL_TEXTURE_BINDING_2D);
                } finally {
                    GlStateManager._activeTexture(activeTexture);
                }
            }
            int colorMask = 0;
            for (int index = 0; index < 4; index++) {
                if (writeMask.get(index) != 0) {
                    colorMask |= 1 << index;
                }
            }
            readCopyPixels(1000, Minecraft.getInstance().gameRenderer.mainRenderTarget().height - 481, 1, 1, GL11C.GL_RGBA, color);
            readCopyPixels(1000, Minecraft.getInstance().gameRenderer.mainRenderTarget().height - 481, 1, 1, GL11C.GL_STENCIL_INDEX, stencil);
            return new CopyState(GL11C.glIsEnabled(GL11C.GL_STENCIL_TEST), GL11C.glGetInteger(GL11C.GL_STENCIL_FUNC),
                GL11C.glGetInteger(GL11C.GL_STENCIL_REF), GL11C.glGetInteger(GL11C.GL_STENCIL_VALUE_MASK),
                GL11C.glGetInteger(GL30C.GL_DRAW_FRAMEBUFFER_BINDING), GL30C.glGetFramebufferAttachmentParameteri(
                    GL30C.GL_DRAW_FRAMEBUFFER, GL30C.GL_STENCIL_ATTACHMENT, GL30C.GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME),
                clipDistances(), stencil.get(0) & 0xFF, color.getInt(0), inputTexture,
                ((GlTexture) Minecraft.getInstance().gameRenderer.mainRenderTarget().getColorTexture()).glId(),
                program, unit, texture, colorMask);
        }
    }

    private static void copyInputImage(GpuTextureView input, String name) {
        int previous = GlStateManager.getFrameBuffer(GL30C.GL_READ_FRAMEBUFFER);
        int framebuffer = GL30C.glGenFramebuffers();
        try {
            GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, framebuffer);
            GlStateManager._glFramebufferTexture2D(GL30C.GL_READ_FRAMEBUFFER, GL30C.GL_COLOR_ATTACHMENT0,
                GL11C.GL_TEXTURE_2D, ((GlTexture) input.texture()).glId(), 0);
            GL11C.glReadBuffer(GL30C.GL_COLOR_ATTACHMENT0);
            if (GL30C.glCheckFramebufferStatus(GL30C.GL_READ_FRAMEBUFFER) != GL30C.GL_FRAMEBUFFER_COMPLETE) {
                fail("DH copy input probe framebuffer is incomplete");
                return;
            }
            copyImage(name, framebuffer, input.texture().getWidth(0), input.texture().getHeight(0));
        } finally {
            GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, previous);
            GlStateManager._glDeleteFramebuffers(framebuffer);
        }
    }

    private static void copyImage(String name) {
        Minecraft minecraft = Minecraft.getInstance();
        copyImage(name, GlStateManager.getFrameBuffer(GL30C.GL_DRAW_FRAMEBUFFER),
            minecraft.gameRenderer.mainRenderTarget().width, minecraft.gameRenderer.mainRenderTarget().height);
    }

    private static void copyImage(String name, int framebuffer, int width, int height) {
        Minecraft minecraft = Minecraft.getInstance();
        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        try {
            readCopyPixels(0, 0, width, height, GL11C.GL_RGBA, pixels, framebuffer);
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int offset = (y * width + x) * 4;
                    int color = (pixels.get(offset + 3) & 0xFF) << 24 | (pixels.get(offset) & 0xFF) << 16
                        | (pixels.get(offset + 1) & 0xFF) << 8 | pixels.get(offset + 2) & 0xFF;
                    image.setRGB(x, height - y - 1, color);
                }
            }
            Path directory = minecraft.gameDirectory.toPath().resolve("screenshots");
            Files.createDirectories(directory);
            ImageIO.write(image, "png", directory.resolve(name + ".png").toFile());
        } catch (IOException failure) {
            throw new UncheckedIOException("Unable to retain DH copy framebuffer evidence", failure);
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }

    private static void readCopyPixels(int x, int y, int width, int height, int format, ByteBuffer pixels) {
        readCopyPixels(x, y, width, height, format, pixels, GlStateManager.getFrameBuffer(GL30C.GL_DRAW_FRAMEBUFFER));
    }

    private static void readCopyPixels(int x, int y, int width, int height, int format, ByteBuffer pixels, int source) {
        int framebuffer = GlStateManager.getFrameBuffer(GL30C.GL_READ_FRAMEBUFFER);
        int buffer = GL11C.glGetInteger(GL21C.GL_PIXEL_PACK_BUFFER_BINDING);
        int alignment = GL11C.glGetInteger(GL11C.GL_PACK_ALIGNMENT);
        int rowLength = GL11C.glGetInteger(GL11C.GL_PACK_ROW_LENGTH);
        int skipRows = GL11C.glGetInteger(GL11C.GL_PACK_SKIP_ROWS);
        int skipPixels = GL11C.glGetInteger(GL11C.GL_PACK_SKIP_PIXELS);
        try {
            GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, source);
            GlStateManager._glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, 0);
            GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, 1);
            GL11C.glPixelStorei(GL11C.GL_PACK_ROW_LENGTH, 0);
            GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_ROWS, 0);
            GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_PIXELS, 0);
            GL11C.glReadPixels(x, y, width, height, format, GL11C.GL_UNSIGNED_BYTE, pixels);
        } finally {
            GL11C.glPixelStorei(GL11C.GL_PACK_ALIGNMENT, alignment);
            GL11C.glPixelStorei(GL11C.GL_PACK_ROW_LENGTH, rowLength);
            GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_ROWS, skipRows);
            GL11C.glPixelStorei(GL11C.GL_PACK_SKIP_PIXELS, skipPixels);
            GlStateManager._glBindBuffer(GL21C.GL_PIXEL_PACK_BUFFER, buffer);
            GlStateManager._glBindFramebuffer(GL30C.GL_READ_FRAMEBUFFER, framebuffer);
        }
    }

    private static void verifyCopyClipScopes() {
        IrisClipPlanes.State original = IrisClipPlanes.capture();
        try {
            IrisClipPlanes.State parent = new IrisClipPlanes.State(0x93, -1);
            parent.restore();
            try (DhClipPrograms.Scope scope = DhClipPrograms.open(new Vector4d(0.0D, 0.0D, 1.0D, -20.0D),
                new Vec3d(0.0D, 0.0D, 0.0D))) {
                int outer = DhBlazeClipPrograms.enable();
                try {
                    if (outer != 0x93 || !IrisClipPlanes.capture().equals(new IrisClipPlanes.State(1, -1))) {
                        throw new AssertionError("DH terrain clipping did not capture its parent and enable only its own distance");
                    }
                    IllegalStateException expected = new IllegalStateException("dh-copy-scope-exit");
                    try {
                        int terrain = DhBlazeClipPrograms.suspend();
                        try {
                            if (terrain != 1 || !IrisClipPlanes.capture().equals(new IrisClipPlanes.State(0, -1))) {
                                throw new AssertionError("DH full-screen copy did not suspend all eight clip distances");
                            }
                            throw expected;
                        } finally {
                            DhBlazeClipPrograms.restore(terrain);
                        }
                    } catch (IllegalStateException failure) {
                        if (failure != expected) {
                            throw new AssertionError("DH copy scope failed during its exceptional exit", failure);
                        }
                    }
                    if (!IrisClipPlanes.capture().equals(new IrisClipPlanes.State(1, -1))) {
                        throw new AssertionError("DH full-screen copy did not restore its enclosing terrain clipping");
                    }
                } finally {
                    DhBlazeClipPrograms.restore(outer);
                }
                if (!IrisClipPlanes.capture().equals(parent)) {
                    throw new AssertionError("DH terrain clipping did not restore all parent clip flags");
                }
            }
            if (!IrisClipPlanes.capture().equals(parent)) {
                throw new AssertionError("DH copy scope changed its outer real GL mask or Iris tracker");
            }
            System.out.println("WORMHOLES_DH_BLAZE_COPY_SCOPE_PASS all-eight-bits=true nested=true exceptional=true");
        } finally {
            original.restore();
        }
    }

    private static int beforeDraw(int count, boolean blaze) {
        if (!enabled || rendering == null || offset == null || count <= 0) {
            return 0;
        }
        draws++;
        PortalLayer layer = layer();
        Minecraft minecraft = Minecraft.getInstance();
        if (layer == null && !shadow() && minecraft.player != null && minecraft.level != minecraft.player.level()) {
            virtualMainDraws++;
            virtualMainWorld = minecraft.level.dimension().identifier().toString();
        }
        Key key = key(layer);
        Sample sample = SAMPLES.computeIfAbsent(key, ignored -> new Sample());
        sample.draws++;
        sample.terrainBuffers = System.identityHashCode(rendering.renderBufferHandler);
        sample.indices += count;
        sample.level = rendering.clientLevelWrapper.getDhIdentifier();
        sample.camera = new Vec3(rendering.exactCameraPosition.x, rendering.exactCameraPosition.y, rendering.exactCameraPosition.z);
        sample.projection = matrix(rendering.mcProjectionMatrix).toString();
        sample.modelView = matrix(rendering.mcModelViewMatrix).toString();
        sample.dhProjection = matrix(rendering.dhProjectionMatrix).toString();
        sample.dhModelView = matrix(rendering.dhModelViewMatrix).toString();
        sample.pass = rendering.renderPass.name();
        sample.backend = blaze ? "BLAZE_3D" : "OPEN_GL";
        if (!blaze) {
            verifyDraw(sample, false);
        }
        double dx = offset.x > 0.0F ? offset.x : Math.max(0.0D, -offset.x - bufferWidth);
        double dz = offset.z > 0.0F ? offset.z : Math.max(0.0D, -offset.z - bufferWidth);
        double distance = Math.hypot(dx, dz);
        sample.maxDistance = Math.max(sample.maxDistance, distance);
        if (distance < Math.max(128.0D, Minecraft.getInstance().options.getEffectiveRenderDistance() * 16.0D + 64.0D)
            || shadow() || QUERIES.size() >= 128 || sample.queries >= 24) {
            return 0;
        }
        if (GL15C.glGetQueryi(GL15C.GL_SAMPLES_PASSED, GL15C.GL_CURRENT_QUERY) != 0) {
            fail("DH acceptance cannot nest its GPU samples query inside another active query");
            return 0;
        }
        int query = GL15C.glGenQueries();
        GL15C.glBeginQuery(GL15C.GL_SAMPLES_PASSED, query);
        sample.queries++;
        QUERIES.add(new Pending(query, key, distance));
        return query;
    }

    private static void verifyDraw(Sample sample, boolean blaze) {
        PortalLayer layer = layer();
        int program = GL11C.glGetInteger(GL20C.GL_CURRENT_PROGRAM);
        sample.program = program;
        sample.clipDistances = clipDistances();
        if (program == 0 || !GL20C.glIsProgram(program) || GL20C.glGetProgrami(program, GL20C.GL_LINK_STATUS) == 0) {
            fail("Actual DH draw has no live linked OpenGL program");
            return;
        }
        if (blaze) {
            sample.blazeDraws++;
            verifyBlazeClip(program, layer, sample);
            if (layer != null && !shadow()) {
                sample.clippedDraws++;
            }
            return;
        }
        if (layer != null && !shadow()) {
            verifyClip(program, layer);
            sample.clippedDraws++;
            return;
        }
        for (int distance : CLIP_DISTANCES) {
            if ((sample.clipDistances & (1 << distance)) != 0) {
                fail("Portal DH clip distance leaked into the main or shadow draw");
            }
        }
        if (!shaders) {
            DhClipPrograms.Binding binding = DhClipPrograms.bind(program);
            if (binding.enabled() >= 0 && GL20C.glGetUniformi(program, binding.enabled()) != 0) {
                fail("Destination clip enable uniform leaked into an ordinary DH draw");
            }
        }
    }

    private static void verifyBlazeClip(int program, PortalLayer layer, Sample sample) {
        boolean portal = layer != null && !shadow();
        if (sample.clipDistances != (portal ? 1 : 0)) {
            fail("Actual DH Blaze draw has incorrect state for one or more of the eight clip distances");
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            UniformBlock block = uniformBlock(program, DhBlazeClipTransformation.BLOCK, stack);
            if (block == null) {
                return;
            }
            if (block.data().remaining() != 32
                || uniformOffset(program, DhClipTransformation.PLANE) != 0
                || uniformOffset(program, DhClipTransformation.ENABLED) != 16) {
                fail("Actual DH Blaze draw has a different std140 destination clip layout");
                return;
            }
            ByteBuffer data = block.data();
            int clipEnabled = data.getInt(16);
            Vector4f actual = new Vector4f(data.getFloat(0), data.getFloat(4), data.getFloat(8), data.getFloat(12));
            Vector4f expected = portal ? matrix(rendering.dhInverseMvmProjectionMatrix).transpose().transform(relativePlane(layer))
                : new Vector4f(0.0F);
            sample.clipBuffer = block.buffer();
            sample.clipBufferOffset = block.offset();
            sample.clipEnabled = clipEnabled;
            sample.clipPlane = new float[]{actual.x, actual.y, actual.z, actual.w};
            sample.clipBytes = new byte[32];
            data.get(0, sample.clipBytes);
            if (clipEnabled != (portal ? 1 : 0) || !close(actual, expected)) {
                fail("Actual DH Blaze draw uploaded a different inverse-MVP destination plane or enable value");
            }
            if (data.getInt(20) != 0 || data.getInt(24) != 0 || data.getInt(28) != 0) {
                fail("Actual DH Blaze draw has nonzero std140 destination clip padding");
            }
            verifyBlazeCoordinates(program, stack, sample);
        }
    }

    private static void verifyBlazeCoordinates(int program, MemoryStack stack, Sample sample) {
        UniformBlock block = uniformBlock(program, "vertSharedUniformBlock", stack);
        int cameraOffset = uniformOffset(program, "uCameraPos");
        int matrixOffset = uniformOffset(program, "uCombinedMatrix");
        if (block == null || cameraOffset < 0 || matrixOffset < 0
            || cameraOffset + 12 > block.data().remaining() || matrixOffset + 64 > block.data().remaining()) {
            fail("Actual DH Blaze draw has no readable camera and full LOD MVP uniforms");
            return;
        }
        ByteBuffer data = block.data();
        Vec3 camera = new Vec3(data.getFloat(cameraOffset), data.getFloat(cameraOffset + 4), data.getFloat(cameraOffset + 8));
        Matrix4f full = new Matrix4f(data.asFloatBuffer().position(matrixOffset / Float.BYTES));
        Vec3 expected = new Vec3((float) rendering.exactCameraPosition.x, (float) rendering.exactCameraPosition.y,
            (float) rendering.exactCameraPosition.z);
        sample.boundDhMvp = full.toString();
        sample.boundCamera = camera;
        if (camera.distanceTo(expected) > 0.001D || !full.equals(matrix(rendering.dhMvmProjMatrix), 0.0001F)) {
            fail("Actual DH Blaze draw bound a different camera or full LOD projection/modelview");
        }
        UniformBlock unique = uniformBlock(program, "vertUniqueUniformBlock", stack);
        int modelOffset = uniformOffset(program, "uModelOffset");
        if (unique == null || modelOffset < 0 || modelOffset + 12 > unique.data().remaining()) {
            fail("Actual DH Blaze draw has no readable LOD model offset");
            return;
        }
        ByteBuffer position = unique.data();
        Vec3 relative = new Vec3(position.getFloat(modelOffset), position.getFloat(modelOffset + 4), position.getFloat(modelOffset + 8))
            .subtract(camera);
        sample.boundModelOffset = relative;
        double expectedY = rendering.clientLevelWrapper.getMinHeight() - camera.y;
        if (Math.abs(relative.y - expectedY) > 0.01D) {
            fail("Actual DH Blaze model origin differs from the destination height base: observed=" + relative.y
                + " expected=" + expectedY);
        }
        if (relative.distanceTo(new Vec3(offset.x, offset.y, offset.z)) > 0.01D) {
            fail("Actual DH Blaze draw's bound LOD position differs from the sampled distant buffer");
        }
    }

    private static UniformBlock uniformBlock(int program, String name, MemoryStack stack) {
        String member = switch (name) {
            case DhBlazeClipTransformation.BLOCK -> DhClipTransformation.PLANE;
            case "vertSharedUniformBlock" -> "uCombinedMatrix";
            case "vertUniqueUniformBlock" -> "uModelOffset";
            default -> throw new IllegalArgumentException("Unknown DH uniform block: " + name);
        };
        int uniform = uniformIndex(program, member);
        int block = uniform < 0 ? -1 : GL31C.glGetActiveUniformsi(program, uniform, GL31C.GL_UNIFORM_BLOCK_INDEX);
        if (block < 0) {
            fail("Actual DH Blaze draw has no uniform block member " + member);
            return null;
        }
        int binding = GL31C.glGetActiveUniformBlocki(program, block, GL31C.GL_UNIFORM_BLOCK_BINDING);
        int size = GL31C.glGetActiveUniformBlocki(program, block, GL31C.GL_UNIFORM_BLOCK_DATA_SIZE);
        int buffer = GL30C.glGetIntegeri(GL31C.GL_UNIFORM_BUFFER_BINDING, binding);
        long offset = GL32C.glGetInteger64i(GL31C.GL_UNIFORM_BUFFER_START, binding);
        long range = GL32C.glGetInteger64i(GL31C.GL_UNIFORM_BUFFER_SIZE, binding);
        if (buffer == 0 || size <= 0 || size > 4096 || offset < 0) {
            fail("Actual DH Blaze draw has no valid bound UBO range for " + name);
            return null;
        }
        int previous = GL11C.glGetInteger(GL31C.GL_COPY_READ_BUFFER);
        ByteBuffer data;
        try {
            GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, buffer);
            long available = GL32C.glGetBufferParameteri64(GL31C.GL_COPY_READ_BUFFER, GL15C.GL_BUFFER_SIZE) - offset;
            if (range > 0) {
                available = Math.min(available, range);
            }
            int readable = (int) Math.min(size, available);
            if (readable <= 0) {
                fail("Actual DH Blaze draw UBO range exceeds its buffer for " + name);
                return null;
            }
            data = stack.malloc(readable);
            GL15C.glGetBufferSubData(GL31C.GL_COPY_READ_BUFFER, offset, data);
        } finally {
            GL15C.glBindBuffer(GL31C.GL_COPY_READ_BUFFER, previous);
        }
        return new UniformBlock(buffer, offset, data);
    }

    private static int uniformOffset(int program, String name) {
        int uniform = uniformIndex(program, name);
        return uniform < 0 ? -1 : GL31C.glGetActiveUniformsi(program, uniform, GL31C.GL_UNIFORM_OFFSET);
    }

    private static int uniformIndex(int program, String name) {
        int count = GL20C.glGetProgrami(program, GL20C.GL_ACTIVE_UNIFORMS);
        int found = -1;
        for (int index = 0; index < count; index++) {
            String actual = GL31C.glGetActiveUniformName(program, index);
            if (actual.equals(name) || actual.endsWith("." + name)) {
                if (found >= 0) {
                    fail("Actual DH Blaze draw has duplicate uniform members: " + name);
                    return -1;
                }
                found = index;
            }
        }
        return found;
    }

    private static Vector4f relativePlane(PortalLayer layer) {
        return new Vector4f((float) layer.worldClipPlane().x(), (float) layer.worldClipPlane().y(), (float) layer.worldClipPlane().z(),
            (float) (layer.worldClipPlane().w() + layer.worldClipPlane().x() * layer.camera().pos.x
                + layer.worldClipPlane().y() * layer.camera().pos.y + layer.worldClipPlane().z() * layer.camera().pos.z));
    }

    private static boolean close(Vector4f actual, Vector4f expected) {
        return actual.isFinite() && expected.isFinite()
            && Math.abs(actual.x - expected.x) <= 0.0001F + Math.abs(expected.x) * 0.00001F
            && Math.abs(actual.y - expected.y) <= 0.0001F + Math.abs(expected.y) * 0.00001F
            && Math.abs(actual.z - expected.z) <= 0.0001F + Math.abs(expected.z) * 0.00001F
            && Math.abs(actual.w - expected.w) <= 0.0001F + Math.abs(expected.w) * 0.00001F;
    }

    private static void verifyInverseCoordinates(RenderParams params) {
        Matrix4f full = matrix(params.dhProjectionMatrix).mul(matrix(params.dhModelViewMatrix));
        Matrix4f inverse = matrix(params.dhInverseMvmProjectionMatrix);
        if (!full.equals(matrix(params.dhMvmProjMatrix), 0.0001F)) {
            fail("DH combined projection/modelview differs from its full LOD MVP");
        }
        for (int index = 0; index < 3; index++) {
            Vector4f expected = new Vector4f(17.0F * (index - 1), 3.0F * index, -129.0F - 257.0F * index, 1.0F);
            Vector4f actual = inverse.transform(full.transform(new Vector4f(expected)));
            if (!actual.isFinite() || Math.abs(actual.w) < 0.00001F
                || !close(actual.div(actual.w), expected)) {
                fail("DH inverse full projection/modelview does not recover camera-relative LOD coordinates");
            }
        }
    }

    private static int clipDistances() {
        int distances = 0;
        for (int distance = 0; distance < 8; distance++) {
            if (GL11C.glIsEnabled(GL30C.GL_CLIP_DISTANCE0 + distance)) {
                distances |= 1 << distance;
            }
        }
        return distances;
    }

    private static void verifyClip(int program, PortalLayer layer) {
        if (!shaders) {
            verifyVanillaClip(program, layer);
            return;
        }
        IrisClipPrograms.DistantBinding binding = IrisClipPrograms.bindDistant(program);
        if (binding.clipping().location() < 0
            || !GL11C.glIsEnabled(GL30C.GL_CLIP_DISTANCE0 + binding.clipping().distance())) {
            fail("Actual DH shader draw has no active portal clip binding");
            return;
        }
        CLIP_DISTANCES.add(binding.clipping().distance());
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer data = stack.mallocFloat(4);
            GL20C.glGetUniformfv(program, binding.clipping().location(), data);
            Vector4f plane = new Vector4f(data);
            Vector4f expected = relativePlane(layer);
            if (!close(plane, expected)) {
                fail("Actual DH shader draw uploaded a different camera-relative destination plane");
            }
            for (float distance : new float[]{-256.0F, -1.0F, 1.0F, 256.0F}) {
                float along = distance - expected.w;
                Vector4f position = new Vector4f(expected.x * along, expected.y * along, expected.z * along, 1.0F);
                float actual = plane.dot(position);
                if (!Float.isFinite(actual) || Math.abs(actual - distance) > 0.001F) {
                    fail("Actual DH shader plane changes decoded vertex coordinates: expected " + distance + ", got " + actual);
                }
            }
            retainShader(program, layer, data, binding.clipping().distance(), stack);
        }
    }

    private static void retainShader(int program, PortalLayer layer, FloatBuffer plane, int distance, MemoryStack stack) {
        if (!SHADER_DUMPS.add(program)) {
            return;
        }
        try {
            Path directory = Path.of("dh-shaders");
            Files.createDirectories(directory);
            IntBuffer shaders = stack.mallocInt(GL20C.glGetProgrami(program, GL20C.GL_ATTACHED_SHADERS));
            IntBuffer count = stack.mallocInt(1);
            GL20C.glGetAttachedShaders(program, count, shaders);
            String vertex = null;
            boolean laterStage = false;
            for (int index = 0; index < count.get(0); index++) {
                int shader = shaders.get(index);
                int type = GL20C.glGetShaderi(shader, GL20C.GL_SHADER_TYPE);
                String source = GL20C.glGetShaderSource(shader);
                if (type == GL20C.GL_VERTEX_SHADER) {
                    vertex = source;
                } else if (type != GL20C.GL_FRAGMENT_SHADER) {
                    laterStage = true;
                }
                Files.writeString(directory.resolve(program + "-" + type + ".glsl"), source);
            }
            if (!laterStage && (vertex == null || !vertex.contains("gl_ClipDistance[" + distance
                + "] = dot(getVertexPosition(), " + IrisClipTransformation.uniform(distance) + ")"))) {
                fail("Actual DH vertex program does not clip its decoded camera-relative position");
            }
            StringBuilder state = new StringBuilder("camera=" + layer.camera().pos + "\nworldPlane=" + layer.worldClipPlane()
                + "\nclipPlane=" + new Vector4f(plane)
                + "\nportalProjection=" + layer.camera().projectionMatrix
                + "\ncapturedProjection=" + CapturedRenderingState.INSTANCE.getGbufferProjection()
                + "\napiNear=" + rendering.nearClipPlane + "\napiFar=" + rendering.farClipPlane);
            for (String uniform : List.of("iris_ProjectionMatrix", "iris_ModelViewMatrix", "dhProjection", "gbufferModelView")) {
                int location = GL20C.glGetUniformLocation(program, uniform);
                if (location >= 0) {
                    FloatBuffer matrix = stack.mallocFloat(16);
                    GL20C.glGetUniformfv(program, location, matrix);
                    state.append('\n').append(uniform).append('=').append(new Matrix4f(matrix));
                }
            }
            for (String uniform : List.of("near", "far", "dhNearPlane", "dhFarPlane", "viewWidth", "viewHeight")) {
                int location = GL20C.glGetUniformLocation(program, uniform);
                if (location >= 0) {
                    state.append('\n').append(uniform).append('=').append(GL20C.glGetUniformf(program, location));
                }
            }
            state.append("\ndepthTest=").append(GL11C.glIsEnabled(GL11C.GL_DEPTH_TEST))
                .append("\ndepthFunction=").append(GL11C.glGetInteger(GL11C.GL_DEPTH_FUNC))
                .append("\nstencilTest=").append(GL11C.glIsEnabled(GL11C.GL_STENCIL_TEST))
                .append("\nstencilFunction=").append(GL11C.glGetInteger(GL11C.GL_STENCIL_FUNC))
                .append("\nstencilReference=").append(GL11C.glGetInteger(GL11C.GL_STENCIL_REF));
            IntBuffer viewport = stack.mallocInt(4);
            GL11C.glGetIntegerv(GL11C.GL_VIEWPORT, viewport);
            state.append("\nviewport=").append(viewport.get(0)).append(',').append(viewport.get(1)).append(',')
                .append(viewport.get(2)).append(',').append(viewport.get(3));
            Files.writeString(directory.resolve(program + "-uniforms.txt"), state.toString());
        } catch (IOException failure) {
            throw new UncheckedIOException("Unable to retain DH shader draw evidence", failure);
        }
    }

    private static void verifyVanillaClip(int program, PortalLayer layer) {
        DhClipPrograms.Binding binding = DhClipPrograms.bind(program);
        if (binding.plane() < 0 || binding.inverse() < 0 || binding.enabled() < 0
            || GL20C.glGetUniformi(program, binding.enabled()) != 1 || !GL11C.glIsEnabled(GL30C.GL_CLIP_DISTANCE0)) {
            fail("Actual shader-off DH draw has no active destination-plane clip binding");
            return;
        }
        CLIP_DISTANCES.add(0);
        try (MemoryStack stack = MemoryStack.stackPush()) {
            FloatBuffer plane = stack.mallocFloat(4);
            GL20C.glGetUniformfv(program, binding.plane(), plane);
            double expectedOffset = layer.worldClipPlane().w() + layer.worldClipPlane().x() * layer.camera().pos.x
                + layer.worldClipPlane().y() * layer.camera().pos.y + layer.worldClipPlane().z() * layer.camera().pos.z;
            if (Math.abs(plane.get(0) - layer.worldClipPlane().x()) > 0.0001D
                || Math.abs(plane.get(1) - layer.worldClipPlane().y()) > 0.0001D
                || Math.abs(plane.get(2) - layer.worldClipPlane().z()) > 0.0001D
                || Math.abs(plane.get(3) - expectedOffset) > 0.0001D) {
                fail("Actual shader-off DH draw uploaded a different camera-relative destination plane");
            }
            FloatBuffer inverse = stack.mallocFloat(16);
            GL20C.glGetUniformfv(program, binding.inverse(), inverse);
            if (!new Matrix4f(inverse).equals(matrix(rendering.dhInverseMvmProjectionMatrix), 0.0001F)) {
                fail("Actual shader-off DH draw uploaded a different inverse LOD projection/modelview");
            }
        }
    }

    private static void poll() {
        for (int count = QUERIES.size(); count > 0; count--) {
            Pending pending = QUERIES.remove();
            if (GL15C.glGetQueryObjecti(pending.id(), GL15C.GL_QUERY_RESULT_AVAILABLE) == 0) {
                QUERIES.add(pending);
                continue;
            }
            long samples = Integer.toUnsignedLong(GL15C.glGetQueryObjectui(pending.id(), GL15C.GL_QUERY_RESULT));
            SAMPLES.get(pending.key()).visibleSamples += samples;
            GL15C.glDeleteQueries(pending.id());
        }
    }

    private static Matrix4f matrix(DhApiMat4f matrix) {
        return DhMat4f.createJomlMatrix(matrix);
    }

    private static boolean shadow() {
        return shaders && ShadowRenderingState.areShadowsCurrentlyBeingRendered();
    }

    private static PortalLayer layer() {
        return LAYERS.isEmpty() ? null : LAYERS.peek().layer;
    }

    private static Key key(PortalLayer layer) {
        return layer == null ? new Key(BlockPos.ZERO, 0) : new Key(new BlockPos(layer.view().surface().geometry().originX(),
            layer.view().surface().geometry().originY(), layer.view().surface().geometry().originZ()), layer.depth());
    }

    private static void fail(String message) {
        if (failure == null) {
            failure = message;
        }
    }

    public record HistoryStep(PortalView.Kind kind, Object key, ClientLevel source, ClientLevel destination, Similarity transform) {
    }

    public record HistoryPath(List<HistoryStep> cameras) {
        public HistoryPath {
            cameras = List.copyOf(cameras);
        }
    }

    private record HistorySample(Key key, History history) {
    }

    public record History(Object pipeline, int first, int second, int renders, int frame, BlazeHistoryResources resources) {
    }

    public static final class BlazeHistoryResources {
        private final List<BlazeResource> resources;

        private BlazeHistoryResources(List<BlazeResource> resources) {
            this.resources = resources;
        }

        public boolean released() {
            for (BlazeResource resource : resources) {
                if (!resource.released()) {
                    return false;
                }
            }
            return true;
        }

        public String describe() {
            return evidence().toString();
        }

        private List<ResourceEvidence> evidence() {
            List<ResourceEvidence> evidence = new ArrayList<>(resources.size());
            for (BlazeResource resource : resources) {
                evidence.add(resource.evidence());
            }
            return List.copyOf(evidence);
        }
    }

    private static final class BlazeResource {
        private final BlazeTextureWrapper owner;
        private final GlTexture texture;
        private final GpuTextureView view;
        private final GlSampler sampler;
        private final int textureId;
        private final int samplerId;
        private boolean releaseObserved;

        private BlazeResource(BlazeTextureWrapper owner) {
            this.owner = owner;
            texture = (GlTexture) owner.getTexture();
            view = owner.getTextureView();
            sampler = (GlSampler) owner.getTextureSampler();
            textureId = texture.glId();
            samplerId = sampler.getId();
        }

        private boolean matches(BlazeTextureWrapper wrapper) {
            return owner == wrapper && texture == wrapper.getTexture() && view == wrapper.getTextureView()
                && sampler == wrapper.getTextureSampler();
        }

        private boolean released() {
            return releaseObserved && texture.isClosed() && view.isClosed() && sampler.isClosed();
        }

        private ResourceEvidence evidence() {
            return new ResourceEvidence(owner.getName(), System.identityHashCode(owner), System.identityHashCode(texture),
                System.identityHashCode(view), System.identityHashCode(sampler), textureId, samplerId,
                texture.isClosed(), view.isClosed(), sampler.isClosed(), releaseObserved);
        }
    }

    private record ResourceEvidence(String name, int owner, int textureObject, int viewObject, int samplerObject,
                                    int textureId, int samplerId, boolean textureClosed, boolean viewClosed,
                                    boolean samplerClosed, boolean releaseObserved) {
    }

    private record Key(BlockPos origin, int depth) {
    }

    private record Pending(int id, Key key, double distance) {
    }

    private record CopyState(boolean stencilEnabled, int function, int reference, int mask, int framebuffer, int stencilAttachment,
                             int clipDistances, int stencilPixel, int rgbaPixel, int inputTexture, int mainTexture,
                             int program, int samplerUnit, int samplerTexture, int colorWriteMask) {
    }

    private record CopyPending(Key key, int ordinal, boolean image, int inputTexture, CopyState before) {
    }

    private record CopyEvidence(Key key, int ordinal, long samples, CopyState before, CopyState after) {
    }

    private record UniformBlock(int buffer, long offset, ByteBuffer data) {
    }

    private record RenderState(float partialTicks, boolean fog, boolean deferred,
                               double magnification, double coneAngle, double lookX, double lookZ) {
        private static RenderState capture() {
            CameraZoom zoom = CameraZoom.INSTANCE;
            return new RenderState(ClientApi.RENDER_STATE.partialTickTime, ClientApi.RENDER_STATE.vanillaFogEnabled,
                DhApiRenderProxy.INSTANCE.getDeferTransparentRendering(),
                zoom.magnification, zoom.coneTanHalfAngle, zoom.lookDirectionX, zoom.lookDirectionZ);
        }
    }

    private record HistoryEvidence(int pipeline, int mainTexture, int altTexture, int renders, int frame,
                                   List<ResourceEvidence> resources) {
    }

    private record Checkpoint(String name, IrisPipelineCostTap.Counts costs, Map<String, Sample> samples, Map<String, HistoryEvidence> histories,
                              long virtualMainDraws, String virtualMainWorld, IrisViewPipelines.CameraSnapshots cameraPaths,
                              IrisViewPipelines.Diagnostics pipelines, Map<String, HistoryEvidence> fullPathHistories, Map<String, Map<String, String>> mirrorProofs) {
    }

    private record Evidence(boolean shaders, String failure, List<Checkpoint> checkpoints, List<CopyEvidence> copies) {
    }

    private static final class Scope {
        private final ClientLevel level;
        private final Object wrapper;
        private final DhMat4f projection;
        private final DhMat4f modelView;
        private final DhMat4f projectionReference = ClientApi.RENDER_STATE.mcProjectionMatrix;
        private final DhMat4f modelViewReference = ClientApi.RENDER_STATE.mcModelViewMatrix;
        private final ParameterState parameters = ParameterState.capture(DhPortalClientApiAccess.wormholes$renderParams());
        private final DistantHorizonsIrisState iris = shaders ? new DistantHorizonsIrisState() : null;
        private final DistantHorizonsClipState clipping = new DistantHorizonsClipState();
        private final int distances = clipDistances();
        private final RenderState state = RenderState.capture();
        private PortalLayer layer;

        private Scope(ClientLevel level, Object wrapper, DhMat4f projection, DhMat4f modelView) {
            this.level = level;
            this.wrapper = wrapper;
            this.projection = projection;
            this.modelView = modelView;
        }
    }

    private record ParameterState(Object[] references, String values) {
        private static ParameterState capture(RenderParams params) {
            return new ParameterState(new Object[]{params.dhClientWorld, params.dhClientLevel, params.clientLevelWrapper,
                params.lightmap, params.renderBufferHandler, params.genericRenderer, params.exactCameraPosition,
                ((DhApiRenderParam) params).clientLevelWrapper, params.apiCopy.clientLevelWrapper, params.apiCopy,
                params.mcProjectionMatrix, params.mcModelViewMatrix, params.mcInverseMvmProjectionMatrix,
                params.dhProjectionMatrix, params.dhModelViewMatrix, params.dhMvmProjMatrix, params.dhInverseMvmProjectionMatrix,
                params.apiCopy.mcProjectionMatrix, params.apiCopy.mcModelViewMatrix, params.apiCopy.mcInverseMvmProjectionMatrix,
                params.apiCopy.dhProjectionMatrix, params.apiCopy.dhModelViewMatrix, params.apiCopy.dhMvmProjMatrix,
                params.apiCopy.dhInverseMvmProjectionMatrix},
                values(params) + values(params.apiCopy) + params.vanillaFogEnabled + ":" + params.hasBeenValidated
                    + ":" + params.exactCameraPosition);
        }

        private boolean matches(RenderParams params) {
            ParameterState current = capture(params);
            if (!values.equals(current.values)) {
                return false;
            }
            for (int index = 0; index < references.length; index++) {
                if (references[index] != current.references[index]) {
                    return false;
                }
            }
            return true;
        }

        private static String values(DhApiRenderParam params) {
            return params.renderPass + ":" + params.partialTicks + ":" + params.nearClipPlane + ":" + params.farClipPlane
                + ":" + params.worldYOffset + ":" + matrix(params.mcProjectionMatrix) + matrix(params.mcModelViewMatrix)
                + matrix(params.mcInverseMvmProjectionMatrix) + matrix(params.dhProjectionMatrix) + matrix(params.dhModelViewMatrix)
                + matrix(params.dhMvmProjMatrix) + matrix(params.dhInverseMvmProjectionMatrix);
        }
    }

    private static final class Sample {
        private int draws;
        private int terrainBuffers;
        private long indices;
        private long visibleSamples;
        private int clippedDraws;
        private int queries;
        private int program;
        private int blazeDraws;
        private int clipDistances;
        private int clipBuffer;
        private long clipBufferOffset;
        private int clipEnabled;
        private float[] clipPlane;
        private byte[] clipBytes;
        private String backend;
        private String boundDhMvp;
        private Vec3 boundCamera;
        private Vec3 boundModelOffset;
        private String level;
        private Vec3 camera;
        private String projection;
        private String modelView;
        private String dhProjection;
        private String dhModelView;
        private String pass;
        private double maxDistance;

        private Sample copy() {
            Sample copy = new Sample();
            copy.draws = draws;
            copy.terrainBuffers = terrainBuffers;
            copy.indices = indices;
            copy.visibleSamples = visibleSamples;
            copy.clippedDraws = clippedDraws;
            copy.program = program;
            copy.blazeDraws = blazeDraws;
            copy.clipDistances = clipDistances;
            copy.clipBuffer = clipBuffer;
            copy.clipBufferOffset = clipBufferOffset;
            copy.clipEnabled = clipEnabled;
            copy.clipPlane = clipPlane == null ? null : clipPlane.clone();
            copy.clipBytes = clipBytes == null ? null : clipBytes.clone();
            copy.backend = backend;
            copy.boundDhMvp = boundDhMvp;
            copy.boundCamera = boundCamera;
            copy.boundModelOffset = boundModelOffset;
            copy.level = level;
            copy.camera = camera;
            copy.projection = projection;
            copy.modelView = modelView;
            copy.dhProjection = dhProjection;
            copy.dhModelView = dhModelView;
            copy.pass = pass;
            copy.maxDistance = maxDistance;
            return copy;
        }

        @Override
        public String toString() {
            return level + " draws=" + draws + " visibleSamples=" + visibleSamples + " clipped=" + clippedDraws
                + " maxDistance=" + maxDistance + " camera=" + camera;
        }
    }
}
