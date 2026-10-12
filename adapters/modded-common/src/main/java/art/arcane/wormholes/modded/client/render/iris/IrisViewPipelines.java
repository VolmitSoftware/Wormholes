package art.arcane.wormholes.modded.client.render.iris;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.PortalFramebufferScope;
import art.arcane.wormholes.modded.client.render.PortalTextureScope;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import art.arcane.wormholes.modded.client.render.stencil.PortalViews;
import art.arcane.wormholes.modded.mixin.client.IrisPortalRenderingAccess;
import art.arcane.wormholes.modded.mixin.client.IrisPortalPipelineAccess;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.gl.program.ProgramSamplers;
import net.irisshaders.iris.gl.program.ProgramUniforms;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.targets.RenderTarget;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.uniforms.SystemTimeUniforms;
import net.minecraft.client.multiplayer.ClientLevel;
import org.lwjgl.opengl.GL11C;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

public final class IrisViewPipelines {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_VIEWS = 12;
    private static final long MAX_IDLE_NANOS = 60_000_000_000L;
    private static final ArrayDeque<Scope> SCOPES = new ArrayDeque<>();
    private static final Set<IrisViewPipelinePath> PENDING = new LinkedHashSet<>();
    private static final Set<IrisViewPipelinePath> PLANNED = new LinkedHashSet<>();
    private static final Set<IrisViewPipelinePath> ACTUAL = new LinkedHashSet<>();
    private static final Set<IrisViewPipelinePath> MESH_PLANNED = new LinkedHashSet<>();
    private static final Set<IrisViewPipelinePath> FAILED = new LinkedHashSet<>();
    private static final Map<IrisViewPipelinePath, IrisViewPipelinePath> CONTINUING = new HashMap<>();
    private static final Set<IrisViewPipelinePath> TRAVERSAL_CONTINUING = new HashSet<>();
    private static final Set<IrisViewPipelinePath> AWAITING = new HashSet<>();
    private static final Map<IrisViewPipelinePath, PortalView> PREPARATION_ANCHORS = new LinkedHashMap<>();
    private static PortalViews continuingViews;
    private static long continuityEpoch;
    private static long epoch;
    private static long creationNanos;
    private static long preparationNanos;
    private static long drawCreationNanos;
    private static long destructions;
    private static long destructionNanos;
    private static long drawMisses;
    private static long drawCreations;
    private static long failedCreations;
    private static int capacityMisses;
    private static final IrisViewPipelineCache<IrisViewPipelinePath, Pipeline> CACHE = new IrisViewPipelineCache<>(
        new IrisViewPipelineCache.Options<>(MAX_VIEWS, IrisViewPipelines::create, IrisViewPipelines::destroy, System::nanoTime));

    private IrisViewPipelines() {
    }

    static Scope open(PortalView view) {
        Scope scope = new Scope(view);
        SCOPES.push(scope);
        return scope;
    }

    static boolean prepared(IrisViewPipelinePath path) {
        return CACHE.contains(path);
    }

    public static void beginMeshPreparation() {
        RenderSystem.assertOnRenderThread();
        MESH_PLANNED.clear();
        planCache();
    }

    public static void prepareMesh(IrisMeshCamera camera) {
        RenderSystem.assertOnRenderThread();
        IrisViewPipelinePath path = meshPath(camera);
        if (MESH_PLANNED.size() < MAX_VIEWS) {
            MESH_PLANNED.add(path);
        }
        planCache();
        if (CACHE.retain(path)) {
            return;
        }
        if (FAILED.contains(path)) {
            throw new IllegalStateException("The Iris portal pipeline failed to compile for " + path);
        }
        IrisCapturedState captured = IrisCapturedState.capture();
        IrisWorldSettings settings = IrisWorldSettings.capture();
        WorldRenderingPipeline selected = Iris.getPipelineManager().getPipelineNullable();
        long started = System.nanoTime();
        try (PortalTextureScope textures = new PortalTextureScope();
             PortalFramebufferScope framebuffer = PortalFramebufferScope.capture()) {
            int frame = SystemTimeUniforms.COUNTER.getAsInt();
            if (SCOPES.isEmpty()) {
                applyContinuations(new ArrayList<>(ACTUAL));
                if (CACHE.adopt(path, frame, preparationAdoption())) {
                    return;
                }
            }
            CACHE.prepare(path, frame, actualCameras(), IrisViewPipelineCache.Preparation.ACTUAL);
        } finally {
            captured.restore();
            settings.apply();
            restoreSelection(selected);
            preparationNanos += System.nanoTime() - started;
        }
    }

    static Scope openMesh(IrisMeshCamera camera) {
        RenderSystem.assertOnRenderThread();
        IrisViewPipelinePath path = meshPath(camera);
        Scope scope = new Scope(null, Iris.getPipelineManager().getPipelineNullable());
        SCOPES.push(scope);
        try {
            scope.enter(path);
            ((IrisPortalPipelineAccess) Iris.getPipelineManager()).wormholes$pipeline(scope.pipeline.rendering());
            return scope;
        } catch (RuntimeException | Error failure) {
            try {
                scope.close();
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    private static IrisViewPipelinePath meshPath(IrisMeshCamera camera) {
        WorldRenderingPipeline selected = Iris.getPipelineManager().getPipelineNullable();
        if (!(selected instanceof IrisRenderingPipeline)) {
            throw new IllegalStateException("No Iris shader pipeline is selected for the streamed camera");
        }
        Scope enclosing = SCOPES.peek();
        if (enclosing != null && enclosing.path != null && enclosing.pipeline.rendering() == selected) {
            return camera.append(enclosing.path.cameras(), enclosing.path.dimension());
        }
        for (Map.Entry<NamespacedId, WorldRenderingPipeline> entry :
            ((IrisPortalPipelineAccess) Iris.getPipelineManager()).wormholes$pipelines().entrySet()) {
            if (entry.getValue() == selected) {
                return camera.append(List.of(), text(entry.getKey()));
            }
        }
        throw new IllegalStateException("The enclosing Iris camera has no selected shader dimension");
    }

    private static void restoreSelection(WorldRenderingPipeline selected) {
        ((IrisPortalPipelineAccess) Iris.getPipelineManager()).wormholes$pipeline(selected);
        if (selected instanceof IrisRenderingPipeline rendering) {
            ShaderStorageBufferHolder buffers = ((IrisPortalRenderingAccess) rendering).wormholes$storageBuffers();
            if (buffers != null) {
                buffers.setupBuffers();
            }
        }
        ProgramUniforms.clearActiveUniforms();
        ProgramSamplers.clearActiveSamplers();
    }

    private static Set<IrisViewPipelinePath> plannedCameras() {
        return preparationAdmission(ACTUAL, MESH_PLANNED, PLANNED, PREPARATION_ANCHORS.keySet());
    }

    static Set<IrisViewPipelinePath> preparationAdmission(Set<IrisViewPipelinePath> actual, Set<IrisViewPipelinePath> mesh,
                                                         Set<IrisViewPipelinePath> planned, Set<IrisViewPipelinePath> anchors) {
        Set<IrisViewPipelinePath> travel = new LinkedHashSet<>();
        Set<IrisViewPipelinePath> source = new LinkedHashSet<>();
        for (IrisViewPipelinePath path : planned) {
            if (!anchors.contains(path)) {
                continue;
            }
            String kind = path.cameras().getFirst().kind();
            if (kind.equals("CROSSING") || kind.equals("RETURN")) {
                travel.add(path);
            } else {
                source.add(path);
            }
        }
        Set<IrisViewPipelinePath> carried = new LinkedHashSet<>(anchors);
        carried.removeAll(planned);
        return IrisCameraAdmission.plan(MAX_VIEWS, List.of(actual, mesh, travel, carried, source, planned));
    }

    private static void planCache() {
        PREPARATION_ANCHORS.keySet().retainAll(plannedCameras());
        CACHE.plan(actualCameras(), CONTINUING, protectedPreparations());
    }

    private static Set<IrisViewPipelinePath> actualCameras() {
        Set<IrisViewPipelinePath> actual = new LinkedHashSet<>(ACTUAL);
        actual.addAll(MESH_PLANNED);
        return actual;
    }

    private static IrisViewPipelineCache.Adoption<IrisViewPipelinePath> preparationAdoption() {
        return new IrisViewPipelineCache.Adoption<>(path -> ACTUAL.contains(path) || MESH_PLANNED.contains(path)
            || PLANNED.contains(path) || PREPARATION_ANCHORS.containsKey(path),
            (source, target) -> source.dimension().equals(target.dimension()));
    }

    static void retainPreparations(Map<IrisViewPipelinePath, PortalView> admitted, PortalViews views, ClientLevel home,
                                   Vec3d eye, double range, double reach) {
        RenderSystem.assertOnRenderThread();
        if (!SCOPES.isEmpty()) {
            throw new IllegalStateException("Portal preparation ownership cannot change while rendering");
        }
        retainPreparations(CACHE, PREPARATION_ANCHORS, admitted, anchor -> views.retainsPreparation(anchor, home, eye, range, reach));
        for (IrisViewPipelinePath path : AWAITING) {
            List<PortalView> traversal = views.crossingTraversal(cameraKeys(nativeCameras(path)));
            if (!traversal.isEmpty() && views.retainsPreparation(traversal.getFirst(), home, eye, range, reach)) {
                CACHE.retain(path);
            }
        }
    }

    static <V> void retainPreparations(IrisViewPipelineCache<IrisViewPipelinePath, V> cache,
                                       Map<IrisViewPipelinePath, PortalView> anchors,
                                       Map<IrisViewPipelinePath, PortalView> admitted, Predicate<PortalView> valid) {
        Map<IrisViewPipelinePath, PortalView> retained = new LinkedHashMap<>();
        List<IrisViewPipelinePath> current = new ArrayList<>(admitted.keySet());
        current.sort(Comparator.comparing(IrisViewPipelinePath::toString));
        for (IrisViewPipelinePath path : current) {
            PortalView anchor = admitted.get(path);
            if (retained.size() < MAX_VIEWS && valid.test(anchor)) {
                retained.put(path, anchor);
            }
        }
        List<IrisViewPipelinePath> previous = new ArrayList<>(anchors.keySet());
        previous.sort(Comparator.comparing(IrisViewPipelinePath::toString));
        for (IrisViewPipelinePath path : previous) {
            PortalView anchor = anchors.get(path);
            if (retained.size() < MAX_VIEWS && !retained.containsKey(path) && cache.contains(path) && valid.test(anchor)) {
                retained.put(path, anchor);
            }
        }
        anchors.clear();
        anchors.putAll(retained);
        for (IrisViewPipelinePath path : anchors.keySet()) {
            cache.retain(path);
        }
    }

    private static Set<IrisViewPipelinePath> protectedPreparations() {
        Set<IrisViewPipelinePath> protectedPaths = new HashSet<>(AWAITING);
        protectedPaths.addAll(PREPARATION_ANCHORS.keySet());
        return protectedPaths;
    }

    public static IrisRenderingPipeline select(NamespacedId dimension) {
        Scope scope = SCOPES.peek();
        if (scope == null) {
            expire();
            return null;
        }
        if (scope.path == null) {
            List<IrisViewPipelinePath.Camera> cameras = new ArrayList<>(SCOPES.size());
            Iterator<Scope> iterator = SCOPES.descendingIterator();
            while (iterator.hasNext()) {
                Scope entered = iterator.next();
                if (entered.view == null) {
                    cameras.clear();
                    cameras.addAll(entered.path.cameras());
                } else {
                    cameras.add(camera(entered.view));
                }
            }
            scope.enter(new IrisViewPipelinePath(cameras, text(dimension)));
        } else if (!scope.path.dimension().equals(text(dimension))) {
            throw new IllegalStateException("A portal pipeline changed dimension while rendering");
        }
        IrisRenderingPipeline rendering = scope.pipeline.rendering();
        ShaderStorageBufferHolder buffers = ((IrisPortalRenderingAccess) rendering).wormholes$storageBuffers();
        if (buffers != null) {
            buffers.setupBuffers();
        }
        return rendering;
    }

    static void beginPreparationPlan(List<IrisViewPipelinePath> paths, List<IrisViewPipelinePath> actual, int unavailableTravelPaths) {
        RenderSystem.assertOnRenderThread();
        if (unavailableTravelPaths > 0 && capacityMisses == 0) {
            LOGGER.warn("Iris portal preparation reached its {}-camera cache limit; {} camera paths remain unprepared",
                MAX_VIEWS, unavailableTravelPaths);
        }
        capacityMisses = unavailableTravelPaths;
        PLANNED.clear();
        ACTUAL.clear();
        MESH_PLANNED.clear();
        if (!Iris.isPackInUseQuick()) {
            PENDING.clear();
            PREPARATION_ANCHORS.clear();
            CACHE.plan(Set.of(), Map.of(), Set.of());
            return;
        }
        ACTUAL.addAll(actual);
        for (IrisViewPipelinePath path : paths) {
            if (PLANNED.size() == MAX_VIEWS) {
                break;
            }
            PLANNED.add(path);
        }
        Set<IrisViewPipelinePath> admitted = plannedCameras();
        for (IrisViewPipelinePath path : admitted) {
            if (CACHE.retain(path) || FAILED.contains(path)) {
                PENDING.remove(path);
            } else {
                PENDING.add(path);
            }
        }
        PENDING.retainAll(admitted);
        planCache();
    }

    static void continueCameras(List<PortalViews.CameraContinuation> continuations, List<PortalView> current,
                                List<IrisViewPipelinePath> actual) {
        if (continuations.isEmpty()) {
            applyContinuations(actual);
            return;
        }
        RenderSystem.assertOnRenderThread();
        if (!SCOPES.isEmpty()) {
            throw new IllegalStateException("Portal camera ownership cannot change while rendering");
        }
        Set<IrisViewPipelinePath.Camera> live = new HashSet<>();
        for (PortalView view : current) {
            live.add(camera(view));
        }
        Map<IrisViewPipelinePath.Camera, IrisViewPipelinePath.Camera> replacements = new HashMap<>();
        for (PortalViews.CameraContinuation continuation : continuations) {
            IrisViewPipelinePath.Camera previous = camera(continuation.previous());
            if (!live.contains(previous)) {
                replacements.put(previous, camera(continuation.next()));
            }
        }
        if (replacements.isEmpty()) {
            applyContinuations(actual);
            return;
        }
        Map<Object, Object> keys = replacementKeys(replacements);
        for (IrisViewPipelinePath path : CACHE.keys()) {
            IrisViewPipelinePath next = continuedPath(path, replacements, keys);
            if (!path.equals(next)) {
                CONTINUING.putIfAbsent(path, next);
            }
        }
        applyContinuations(actual);
    }

    static void continueTraversals(List<PortalViews.TraversalContinuation> continuations, List<IrisViewPipelinePath> actual) {
        RenderSystem.assertOnRenderThread();
        if (!SCOPES.isEmpty()) {
            throw new IllegalStateException("Portal camera ownership cannot change while rendering");
        }
        WormholesClient client = WormholesClient.instance();
        PortalViews views = client == null ? null : client.portalViews();
        synchronizeContinuations(views);
        for (IrisViewPipelinePath path : TRAVERSAL_CONTINUING) {
            CONTINUING.remove(path);
        }
        TRAVERSAL_CONTINUING.clear();
        AWAITING.clear();
        if (views != null && !views.hasTraversalHistory() || views == null && continuations.isEmpty()) {
            applyContinuations(actual);
            return;
        }
        List<TraversalTransfer> transfers = new ArrayList<>(continuations.size());
        for (PortalViews.TraversalContinuation continuation : continuations) {
            transfers.add(new TraversalTransfer(cameras(continuation.previous()), cameras(continuation.next())));
        }
        for (IrisViewPipelinePath path : CACHE.keys()) {
            IrisViewPipelinePath next = views == null ? continuedTraversalPath(path, transfers) : continuedTraversalPath(path, views);
            if (!path.equals(next)) {
                CONTINUING.put(path, next);
                TRAVERSAL_CONTINUING.add(path);
            } else if (views != null && awaitingTraversal(path, views)) {
                AWAITING.add(path);
            }
        }
        applyContinuations(actual);
    }

    private static void synchronizeContinuations(PortalViews views) {
        long currentEpoch = views == null ? 0L : views.continuityEpoch();
        if (continuingViews != views || continuityEpoch != currentEpoch) {
            CONTINUING.clear();
            TRAVERSAL_CONTINUING.clear();
            AWAITING.clear();
            continuingViews = views;
            continuityEpoch = currentEpoch;
        }
    }

    private static void applyContinuations(List<IrisViewPipelinePath> actual) {
        if (CONTINUING.isEmpty()) {
            return;
        }
        RenderSystem.assertOnRenderThread();
        if (!SCOPES.isEmpty()) {
            throw new IllegalStateException("Portal camera ownership cannot change while rendering");
        }
        Set<IrisViewPipelinePath> retained = new HashSet<>(actual);
        retained.addAll(MESH_PLANNED);
        try (PortalTextureScope textures = new PortalTextureScope();
             PortalFramebufferScope framebuffer = PortalFramebufferScope.capture()) {
            continueOwnership(CACHE, CONTINUING, retained, protectedPreparations(), SystemTimeUniforms.COUNTER.getAsInt());
        }
    }

    static <K, V> void continueOwnership(IrisViewPipelineCache<K, V> cache, Map<K, K> continuations, Set<K> actual, Set<K> awaiting, int frame) {
        cache.transfer(path -> continuations.getOrDefault(path, path), actual::contains, frame);
        continuations.entrySet().removeIf(entry -> !cache.contains(entry.getKey()) || cache.rendered(entry.getValue()));
        cache.plan(actual, continuations, awaiting);
    }

    static IrisViewPipelinePath continuedPath(IrisViewPipelinePath path,
                                              Map<IrisViewPipelinePath.Camera, IrisViewPipelinePath.Camera> replacements) {
        return continuedPath(path, replacements, replacementKeys(replacements));
    }

    private static IrisViewPipelinePath continuedPath(IrisViewPipelinePath path,
                                                      Map<IrisViewPipelinePath.Camera, IrisViewPipelinePath.Camera> replacements,
                                                      Map<Object, Object> keys) {
        List<IrisViewPipelinePath.Camera> cameras = new ArrayList<>(path.cameras().size());
        for (IrisViewPipelinePath.Camera camera : path.cameras()) {
            cameras.add(replacements.containsKey(camera) ? replacements.get(camera) : continuedCamera(camera, keys));
        }
        return new IrisViewPipelinePath(cameras, path.dimension());
    }

    static IrisViewPipelinePath continuedTraversalPath(IrisViewPipelinePath path, List<TraversalTransfer> transfers) {
        TraversalTransfer matched = null;
        boolean ambiguous = false;
        for (TraversalTransfer transfer : transfers) {
            if (!startsWith(path.cameras(), transfer.previous())) {
                continue;
            }
            if (matched == null || transfer.previous().size() > matched.previous().size()) {
                matched = transfer;
                ambiguous = false;
            } else if (transfer.previous().size() == matched.previous().size() && !transfer.next().equals(matched.next())) {
                ambiguous = true;
            }
        }
        if (matched == null || ambiguous) {
            return path;
        }
        Map<Object, Object> keys = new HashMap<>();
        int shared = Math.min(matched.previous().size(), matched.next().size());
        for (int offset = 1; offset <= shared; offset++) {
            keys.put(matched.previous().get(matched.previous().size() - offset).key(),
                matched.next().get(matched.next().size() - offset).key());
        }
        List<IrisViewPipelinePath.Camera> cameras = new ArrayList<>(matched.next().size() + path.cameras().size() - matched.previous().size());
        cameras.addAll(matched.next());
        for (int index = matched.previous().size(); index < path.cameras().size(); index++) {
            cameras.add(continuedCamera(path.cameras().get(index), keys));
        }
        return new IrisViewPipelinePath(cameras, path.dimension());
    }

    static IrisViewPipelinePath continuedTraversalPath(IrisViewPipelinePath path, PortalViews views) {
        if (!views.hasTraversalHistory()) {
            return path;
        }
        List<IrisViewPipelinePath.Camera> nativeCameras = nativeCameras(path);
        if (nativeCameras.isEmpty()) {
            return path;
        }
        List<Object> keys = cameraKeys(nativeCameras);
        PortalViews.TraversalContinuation continuation = views.continuedTraversal(keys);
        if (continuation == null || !nativeCameras.equals(cameras(continuation.previous()))) {
            return path;
        }
        List<IrisViewPipelinePath.Camera> next = cameras(continuation.next());
        if (nativeCameras.size() < path.cameras().size()) {
            next.add(path.cameras().getLast());
        }
        return new IrisViewPipelinePath(next, path.dimension());
    }

    static boolean awaitingTraversal(IrisViewPipelinePath path, PortalViews views) {
        List<IrisViewPipelinePath.Camera> nativeCameras = nativeCameras(path);
        if (nativeCameras.isEmpty()) {
            return false;
        }
        List<Object> keys = cameraKeys(nativeCameras);
        return nativeCameras.equals(cameras(views.crossingTraversal(keys))) && views.continuedTraversal(keys) == null;
    }

    private static List<IrisViewPipelinePath.Camera> nativeCameras(IrisViewPipelinePath path) {
        int depth = path.cameras().size();
        IrisViewPipelinePath.Camera tail = path.cameras().getLast();
        if (tail.kind().equals("STREAMED") && tail.key() instanceof IrisMeshCamera) {
            depth--;
        }
        return path.cameras().subList(0, depth);
    }

    private static List<Object> cameraKeys(List<IrisViewPipelinePath.Camera> cameras) {
        List<Object> keys = new ArrayList<>(cameras.size());
        for (IrisViewPipelinePath.Camera camera : cameras) {
            keys.add(camera.key());
        }
        return keys;
    }

    private static boolean startsWith(List<IrisViewPipelinePath.Camera> cameras, List<IrisViewPipelinePath.Camera> prefix) {
        if (cameras.size() < prefix.size()) {
            return false;
        }
        for (int index = 0; index < prefix.size(); index++) {
            if (!cameras.get(index).equals(prefix.get(index))) {
                return false;
            }
        }
        return true;
    }

    private static Map<Object, Object> replacementKeys(Map<IrisViewPipelinePath.Camera, IrisViewPipelinePath.Camera> replacements) {
        Map<Object, Object> keys = new HashMap<>();
        Set<Object> ambiguous = new HashSet<>();
        for (Map.Entry<IrisViewPipelinePath.Camera, IrisViewPipelinePath.Camera> replacement : replacements.entrySet()) {
            Object previous = replacement.getKey().key();
            Object next = replacement.getValue().key();
            if (ambiguous.contains(previous)) {
                continue;
            }
            Object existing = keys.putIfAbsent(previous, next);
            if (existing != null && !existing.equals(next)) {
                keys.remove(previous);
                ambiguous.add(previous);
            }
        }
        return keys;
    }

    private static IrisViewPipelinePath.Camera continuedCamera(IrisViewPipelinePath.Camera camera, Map<Object, Object> keys) {
        Object key = PortalViews.continuedKey(camera.key(), keys);
        return key.equals(camera.key()) ? camera : new IrisViewPipelinePath.Camera(camera.kind(), key, camera.source(), camera.destination(), camera.transform());
    }

    private static List<IrisViewPipelinePath.Camera> cameras(List<PortalView> views) {
        List<IrisViewPipelinePath.Camera> cameras = new ArrayList<>(views.size());
        for (PortalView view : views) {
            cameras.add(camera(view));
        }
        return cameras;
    }

    static void preparePlannedViews() {
        int attempts = PENDING.size();
        for (int attempt = 0; attempt < attempts; attempt++) {
            if (preparationStep()) {
                return;
            }
        }
    }

    public static boolean preparationStep() {
        RenderSystem.assertOnRenderThread();
        if (!SCOPES.isEmpty()) {
            throw new IllegalStateException("Portal pipelines must be prepared outside portal rendering");
        }
        if (!Iris.isPackInUseQuick()) {
            PENDING.clear();
            PREPARATION_ANCHORS.clear();
            return true;
        }
        PENDING.retainAll(plannedCameras());
        expire();
        applyContinuations(new ArrayList<>(ACTUAL));
        Iterator<IrisViewPipelinePath> iterator = PENDING.iterator();
        if (!iterator.hasNext()) {
            return true;
        }
        IrisViewPipelinePath path = iterator.next();
        long started = System.nanoTime();
        IrisCapturedState captured = IrisCapturedState.capture();
        try (PortalTextureScope textures = new PortalTextureScope();
             PortalFramebufferScope framebuffer = PortalFramebufferScope.capture()) {
            Set<IrisViewPipelinePath> actual = actualCameras();
            int frame = SystemTimeUniforms.COUNTER.getAsInt();
            if (!CACHE.adoptNext(PENDING, frame, actual, preparationAdoption())) {
                CACHE.prepareNext(PENDING, frame, actual, actual);
            }
        } catch (RuntimeException failure) {
            LOGGER.error("Unable to prepare the Iris portal pipeline for {}", path, failure);
        } finally {
            captured.restore();
            ProgramUniforms.clearActiveUniforms();
            ProgramSamplers.clearActiveSamplers();
            preparationNanos += System.nanoTime() - started;
        }
        return PENDING.isEmpty();
    }

    public static Diagnostics diagnostics() {
        IrisViewPipelineCache.Statistics statistics = CACHE.statistics();
        return new Diagnostics(statistics.hits(), statistics.creations(), statistics.preparations(), statistics.evictions(),
            statistics.expirations(), destructions, drawMisses, drawCreations, failedCreations, creationNanos, preparationNanos,
            drawCreationNanos, destructionNanos, statistics.size(), PENDING.size(), capacityMisses, statistics.adoptions());
    }

    public static CameraSnapshots cameraSnapshots() {
        RenderSystem.assertOnRenderThread();
        long observedNanos = System.nanoTime();
        Set<IrisViewPipelinePath> admitted = plannedCameras();
        Map<IrisViewPipelinePath, IrisViewPipelineCache.Snapshot<IrisViewPipelinePath>> cached = new HashMap<>();
        Set<IrisViewPipelinePath> paths = new LinkedHashSet<>();
        for (IrisViewPipelineCache.Snapshot<IrisViewPipelinePath> snapshot : CACHE.snapshots()) {
            cached.put(snapshot.key(), snapshot);
            paths.add(snapshot.key());
        }
        paths.addAll(ACTUAL);
        paths.addAll(PLANNED);
        paths.addAll(MESH_PLANNED);
        paths.addAll(PENDING);
        paths.addAll(FAILED);
        paths.addAll(CONTINUING.keySet());
        paths.addAll(CONTINUING.values());
        paths.addAll(AWAITING);
        List<CameraSnapshot> cameras = new ArrayList<>(paths.size());
        for (IrisViewPipelinePath path : paths) {
            cameras.add(cameraSnapshot(path, cached.get(path), admitted));
        }
        List<ContinuationSnapshot> continuations = new ArrayList<>(CONTINUING.size());
        for (Map.Entry<IrisViewPipelinePath, IrisViewPipelinePath> continuation : CONTINUING.entrySet()) {
            continuations.add(new ContinuationSnapshot(continuation.getKey().toString(), continuation.getValue().toString()));
        }
        WorldRenderingPipeline selected = Iris.getPipelineManager().getPipelineNullable();
        return new CameraSnapshots(epoch, SystemTimeUniforms.COUNTER.getAsInt(), observedNanos, List.copyOf(cameras), List.copyOf(continuations),
            SCOPES.isEmpty() && selected instanceof IrisRenderingPipeline rendering ? textureHistory(rendering) : null);
    }

    private static CameraSnapshot cameraSnapshot(IrisViewPipelinePath path,
                                                 IrisViewPipelineCache.Snapshot<IrisViewPipelinePath> cached,
                                                 Set<IrisViewPipelinePath> admitted) {
        List<String> roles = new ArrayList<>(9);
        if (cached != null) {
            roles.add("cached");
        }
        if (admitted.contains(path)) {
            roles.add("planned");
        }
        if (ACTUAL.contains(path)) {
            roles.add("actual");
        }
        if (MESH_PLANNED.contains(path)) {
            roles.add("mesh");
        }
        if (PLANNED.contains(path) && !ACTUAL.contains(path) && !MESH_PLANNED.contains(path)) {
            roles.add("future");
        }
        if (PENDING.contains(path)) {
            roles.add("pending");
        }
        if (FAILED.contains(path)) {
            roles.add("failed");
        }
        if (CONTINUING.containsKey(path)) {
            roles.add("continuation-source");
        }
        if (CONTINUING.containsValue(path)) {
            roles.add("continuation-target");
        }
        if (AWAITING.contains(path)) {
            roles.add("continuation-awaiting");
        }
        List<CameraIdentity> traversal = new ArrayList<>(path.cameras().size());
        for (IrisViewPipelinePath.Camera camera : path.cameras()) {
            traversal.add(new CameraIdentity(camera.kind(), camera.key().toString(), camera.source(), camera.destination(), camera.transform().toString()));
        }
        Pipeline pipeline = cached == null ? null : CACHE.peek(path);
        return new CameraSnapshot(path.toString(), path.dimension(), List.copyOf(traversal), List.copyOf(roles),
            cached == null ? null : new CachedCamera(cached.identity(), cached.frame(), cached.users(), cached.rendered(), cached.lastUsedNanos(),
                pipeline == null ? null : textureHistory(pipeline.rendering())));
    }

    private static TextureHistory textureHistory(IrisRenderingPipeline pipeline) {
        RenderTargets targets = ((IrisPortalRenderingAccess) pipeline).wormholes$renderTargets();
        if (targets == null || targets.getRenderTargetCount() <= 5) {
            return null;
        }
        RenderTarget history = targets.get(5);
        if (history == null) {
            return null;
        }
        int main = history.getMainTexture();
        int alternate = history.getAltTexture();
        return new TextureHistory(System.identityHashCode(pipeline), main, alternate, GL11C.glIsTexture(main), GL11C.glIsTexture(alternate));
    }

    public static void forget(PortalView view) {
        IrisViewPipelinePath.Camera camera = camera(view);
        PENDING.removeIf(path -> path.cameras().contains(camera));
        PLANNED.removeIf(path -> path.cameras().contains(camera));
    }

    public static void clear() {
        epoch++;
        PENDING.clear();
        PLANNED.clear();
        ACTUAL.clear();
        MESH_PLANNED.clear();
        FAILED.clear();
        CONTINUING.clear();
        TRAVERSAL_CONTINUING.clear();
        AWAITING.clear();
        PREPARATION_ANCHORS.clear();
        continuingViews = null;
        continuityEpoch = 0L;
        capacityMisses = 0;
        if (CACHE.size() == 0) {
            return;
        }
        try (PortalTextureScope textures = new PortalTextureScope();
             PortalFramebufferScope framebuffer = PortalFramebufferScope.capture()) {
            CACHE.close();
        }
    }

    private static void expire() {
        long cutoff = System.nanoTime() - MAX_IDLE_NANOS;
        if (!CACHE.hasExpired(cutoff)) {
            return;
        }
        try (PortalTextureScope textures = new PortalTextureScope();
             PortalFramebufferScope framebuffer = PortalFramebufferScope.capture()) {
            CACHE.expire(cutoff);
        }
    }

    static IrisViewPipelinePath.Camera camera(PortalView view) {
        return new IrisViewPipelinePath.Camera(view.kind().name(), view.historyKey(), view.source().dimension().identifier().toString(),
            view.destination().dimension().identifier().toString(), view.toDestination());
    }

    private static String text(NamespacedId dimension) {
        return dimension.getNamespace() + ":" + dimension.getName();
    }

    private static Pipeline create(IrisViewPipelinePath path) {
        ShaderPack pack = Iris.getCurrentPack().orElseThrow(() -> new IllegalStateException("No shader pack is available for a portal view"));
        IrisWorldSettings previous = IrisWorldSettings.capture();
        long started = System.nanoTime();
        try {
            IrisRenderingPipeline rendering = new IrisRenderingPipeline(pack.getProgramSet(new NamespacedId(path.dimension())));
            ((IrisPortalRenderingAccess) rendering).wormholes$initializedBlockIds(previous.blocks() != null);
            return new Pipeline(rendering, IrisWorldSettings.capture());
        } catch (RuntimeException | Error failure) {
            failedCreations++;
            if (FAILED.size() == MAX_VIEWS) {
                FAILED.remove(FAILED.iterator().next());
            }
            FAILED.add(path);
            throw failure;
        } finally {
            creationNanos += System.nanoTime() - started;
            previous.apply();
        }
    }

    private static void destroy(Pipeline pipeline) {
        long started = System.nanoTime();
        try {
            pipeline.rendering().destroy();
            destructions++;
        } finally {
            destructionNanos += System.nanoTime() - started;
        }
    }

    public record Diagnostics(long hits, long creations, long preparations, long evictions, long expirations,
                              long destructions, long drawMisses, long drawCreations, long failedCreations, long creationNanos,
                              long preparationNanos, long drawCreationNanos, long destructionNanos, int cached, int pending,
                              int capacityMisses, long adoptions) {
    }

    public record CameraSnapshots(long epoch, int frame, long observedNanos, List<CameraSnapshot> cameras,
                                  List<ContinuationSnapshot> continuations, TextureHistory selectedMain) {
    }

    public record CameraSnapshot(String path, String dimension, List<CameraIdentity> traversal, List<String> roles,
                                 CachedCamera cached) {
    }

    public record CameraIdentity(String kind, String key, String source, String destination, String transform) {
    }

    public record CachedCamera(long identity, int frame, int users, boolean rendered, long lastUsedNanos, TextureHistory colortex5) {
    }

    public record TextureHistory(int pipelineIdentity, int mainTexture, int alternateTexture, boolean mainLive, boolean alternateLive) {
    }

    public record ContinuationSnapshot(String source, String target) {
    }

    private record Pipeline(IrisRenderingPipeline rendering, IrisWorldSettings settings) {
    }

    record TraversalTransfer(List<IrisViewPipelinePath.Camera> previous, List<IrisViewPipelinePath.Camera> next) {
        TraversalTransfer {
            previous = List.copyOf(previous);
            next = List.copyOf(next);
            if (previous.isEmpty() || next.isEmpty()) {
                throw new IllegalArgumentException("Camera continuation cannot contain the main camera");
            }
        }
    }

    static final class Scope implements AutoCloseable {
        private final PortalView view;
        private final IrisWorldSettings settings;
        private final IrisCapturedState captured;
        private final PortalTextureScope textures;
        private final PortalFramebufferScope framebuffer;
        private final WorldRenderingPipeline selected;
        private IrisViewPipelinePath path;
        private Pipeline pipeline;

        private Scope(PortalView view) {
            this(view, null);
        }

        private Scope(PortalView view, WorldRenderingPipeline selected) {
            this.view = view;
            this.selected = selected;
            settings = IrisWorldSettings.capture();
            captured = IrisCapturedState.capture();
            framebuffer = PortalFramebufferScope.capture();
            textures = new PortalTextureScope();
        }

        IrisRenderingPipeline rendering() {
            return pipeline.rendering();
        }

        private void enter(IrisViewPipelinePath path) {
            PENDING.remove(path);
            boolean missing = !CACHE.contains(path);
            if (missing && FAILED.contains(path)) {
                throw new IllegalStateException("The Iris portal pipeline failed to compile for " + path);
            }
            long started = missing ? System.nanoTime() : 0L;
            if (missing) {
                drawMisses++;
            }
            pipeline = CACHE.enter(path, SystemTimeUniforms.COUNTER.getAsInt());
            this.path = path;
            if (missing) {
                drawCreations++;
                drawCreationNanos += System.nanoTime() - started;
            }
            pipeline.settings().apply();
            ShaderStorageBufferHolder buffers = ((IrisPortalRenderingAccess) pipeline.rendering()).wormholes$storageBuffers();
            if (buffers != null) {
                buffers.setupBuffers();
            }
        }

        @Override
        public void close() {
            if (SCOPES.peek() != this) {
                throw new IllegalStateException("Portal pipeline scope order");
            }
            SCOPES.pop();
            try {
                if (path != null) {
                    CACHE.exit(path);
                }
                captured.restore();
                settings.apply();
                ProgramUniforms.clearActiveUniforms();
                ProgramSamplers.clearActiveSamplers();
            } finally {
                try {
                    if (selected != null) {
                        restoreSelection(selected);
                    }
                } finally {
                    try {
                        textures.close();
                    } finally {
                        if (framebuffer != null) {
                            framebuffer.close();
                        }
                    }
                }
            }
        }
    }
}
