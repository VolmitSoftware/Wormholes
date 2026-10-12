package art.arcane.wormholes.modded.client.render.iris;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.modded.client.render.stencil.PortalLayerMath;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import art.arcane.wormholes.modded.client.render.stencil.PortalViews;
import art.arcane.wormholes.modded.client.render.stencil.PortalSurface;
import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import art.arcane.wormholes.modded.client.render.stencil.PortalViewSelection;
import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import art.arcane.wormholes.modded.mixin.client.IrisPortalPipelineAccess;
import art.arcane.wormholes.modded.mixin.client.IrisPortalRenderingAccess;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.logging.LogUtils;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.PipelineManager;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.DimensionId;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

public final class IrisPipelines {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_PREPARATION_DEPTH = 8;
    private static final int MAX_PREPARATION_VIEWS = 12;
    private static final double NEAR_CULL_BLOCKS = 0.5D;
    private static final Set<String> FAILED = new HashSet<>();

    private IrisPipelines() {
    }

    public static boolean loadingStep() {
        List<String> pending = pending();
        if (!pending.isEmpty()) {
            create(pending.getFirst());
            return false;
        }
        if (Iris.getCurrentPack().isPresent()) {
            IrisMeshFrame.warm();
        }
        return true;
    }

    public static void createAll() {
        FAILED.clear();
        for (String dimension : pending()) {
            create(dimension);
        }
        if (Iris.getCurrentPack().isPresent()) {
            IrisMeshFrame.warm();
        }
    }

    public static void requestViews(CameraRenderState camera, Matrix4fc projection) {
        requestViews(new PreparationCamera(new Vec3d(camera.pos.x, camera.pos.y, camera.pos.z),
            camera.viewRotationMatrix, projection, camera.cullFrustum, PortalViewSelection.Window.FULL, null,
            RenderSystem.getDevice().getDeviceInfo().isZZeroToOne()));
    }

    private static void requestViews(PreparationCamera camera) {
        Minecraft minecraft = Minecraft.getInstance();
        WormholesClient client = WormholesClient.instance();
        ClientPacketListener connection = minecraft.getConnection();
        Optional<ShaderPack> pack = Iris.getCurrentPack();
        if (client == null || connection == null || minecraft.level == null || pack.isEmpty()) {
            return;
        }
        int chunks = minecraft.options.getEffectiveRenderDistance();
        PreparationOptions options = new PreparationOptions(client.portalViews().current(), client.portalViews()::children,
            IrisViewPipelines::prepared, minecraft.level, camera.eye(), chunks, pack.get(), camera.frustum() == null ? 1 : MAX_PREPARATION_DEPTH);
        Preparation preparation = preparation(options, camera, demand -> streamed(options, client.portalViews(), demand));
        IrisViewPipelines.continueTraversals(client.portalViews().takeTraversalContinuations(), preparation.actual());
        IrisViewPipelines.continueCameras(client.portalViews().takeCameraContinuations(), options.views(), preparation.actual());
        IrisViewPipelines.retainPreparations(preparation.anchors(), client.portalViews(), options.home(), camera.eye(),
            chunks * 16.0D, (chunks - 1) * 16.0D);
        PortalStencilRenderer.instance().preparedViews(preparation.actual().size());
        IrisViewPipelines.beginPreparationPlan(preparation.paths(), preparation.actual(), preparation.capacityMisses());
    }

    private static List<IrisMeshCamera> streamed(PreparationOptions options, PortalViews views, NativeDemand demand) {
        int parentKey = views.streamKey(demand.parent());
        if (parentKey == 0 || demand.camera().view() == null || demand.camera().projection() == null) {
            return List.of();
        }
        Set<Integer> nativeChildren = new HashSet<>();
        PreparationPlan selection = new PreparationPlan(options, ignored -> List.of());
        for (PortalView child : nearby(selection, demand.parent().destination(), demand.camera(), demand.depth(),
            demand.parent(), demand.ceiling())) {
            nativeChildren.add(views.streamKey(child));
        }
        return ClientPortalRenderer.instance().streamedCameras(parentKey, options.home(), demand.depth(), demand.ceiling(), nativeChildren,
            new ClientPortalRenderer.StreamSelection(demand.camera().selection(),
                Minecraft.getInstance().gameRenderer.mainRenderTarget().width,
                Minecraft.getInstance().gameRenderer.mainRenderTarget().height));
    }

    static Preparation preparation(PreparationOptions options, PreparationCamera camera) {
        return preparation(options, camera, ignored -> List.of());
    }

    static Preparation preparation(PreparationOptions options, PreparationCamera camera, Function<NativeDemand, List<IrisMeshCamera>> streams) {
        PreparationPlan plan = new PreparationPlan(options, streams);
        List<PortalView> roots = nearby(plan, options.home(), camera, 0, null, options.depth());
        for (PortalView view : roots) {
            plan.addNative(List.of(view), true);
            if (!plan.full()) {
                requestChildren(plan, new ArrayList<>(List.of(view)), view, through(camera, view), view.recursion(), true);
            }
        }
        List<IrisViewPipelinePath> actual = List.copyOf(plan.paths);
        List<FutureCamera> future = new ArrayList<>(roots.size() * 3);
        for (PortalView view : roots) {
            for (PortalView travel : PortalViews.futureTravelViews(view)) {
                IrisViewPipelinePath.Camera identity = IrisViewPipelines.camera(travel);
                PreparationCamera render = travel.destination() == view.source() ? past(camera, view) : through(camera, travel);
                future.add(new FutureCamera(travel, view, render, plan.path(List.of(identity), travel.destination())));
            }
        }
        future.sort(Comparator.comparing((FutureCamera arrival) -> !options.prepared().test(arrival.path()))
            .thenComparingDouble(arrival -> arrival.anchor().surface().distance(camera.eye()))
            .thenComparing(arrival -> arrival.path().toString()));
        int capacityMisses = 0;
        List<FutureCamera> admitted = new ArrayList<>(MAX_PREPARATION_VIEWS);
        for (FutureCamera arrival : future) {
            if (plan.add(arrival.path())) {
                admitted.add(arrival);
                plan.anchors.put(arrival.path(), arrival.anchor());
            } else {
                capacityMisses++;
            }
        }
        prepareSourceRoots(plan, roots, camera, options.prepared());
        for (FutureCamera arrival : admitted) {
            requestChildren(plan, new ArrayList<>(List.of(arrival.view())), arrival.view(), arrival.render(),
                arrival.view().recursion(), false);
        }
        return new Preparation(List.copyOf(plan.paths), capacityMisses, actual, Map.copyOf(plan.anchors));
    }

    private static void prepareSourceRoots(PreparationPlan plan, List<PortalView> roots, PreparationCamera camera,
                                           Predicate<IrisViewPipelinePath> prepared) {
        if (plan.full() || plan.depth < 2) {
            return;
        }
        boolean departure = false;
        for (PortalView root : roots) {
            if (root.kind() == PortalView.Kind.ARM && root.source() == plan.home && root.destination() != plan.home
                && root.recursion() > 1 && plan.paths.contains(plan.path(cameras(List.of(root)), root.destination()))
                && PortalViews.uniqueSourceCamera(root, plan.views)) {
                departure = true;
                break;
            }
        }
        if (!departure) {
            return;
        }
        List<NativePreparation> candidates = new ArrayList<>();
        for (PortalView view : plan.views) {
            if (view.source() != plan.home || view.kind() != PortalView.Kind.ARM && view.kind() != PortalView.Kind.MIRROR
                || view.recursion() < 2 || !view.surface().servesEye(camera.eye())
                || view.surface().distance(camera.eye()) > plan.range || !available(plan, view)
                || !PortalViews.uniqueSourceCamera(view, plan.views)) {
                continue;
            }
            IrisViewPipelinePath path = plan.path(cameras(List.of(view)), view.destination());
            if (!plan.paths.contains(path)) {
                candidates.add(new NativePreparation(path, List.of(view)));
            }
        }
        candidates.sort(Comparator.comparing((NativePreparation candidate) -> !prepared.test(candidate.path()))
            .thenComparingDouble(candidate -> candidate.traversal().getFirst().surface().distance(camera.eye()))
            .thenComparing(candidate -> candidate.path().toString()));
        for (NativePreparation candidate : candidates) {
            if (!plan.add(candidate.path())) {
                break;
            }
            plan.anchors.put(candidate.path(), candidate.traversal().getFirst());
            plan.sourceNative.add(candidate);
        }
    }

    private static void requestChildren(PreparationPlan plan, List<PortalView> traversal, PortalView parent,
                                        PreparationCamera camera, int recursion, boolean actual) {
        if (plan.full() || traversal.size() >= Math.min(plan.depth, recursion)) {
            return;
        }
        for (PortalView view : nearby(plan, parent.destination(), camera, traversal.size(), parent, recursion)) {
            if (plan.full()) {
                return;
            }
            traversal.add(view);
            plan.addNative(traversal, actual);
            requestChildren(plan, traversal, view, through(camera, view), Math.min(recursion, view.recursion()), actual);
            traversal.removeLast();
        }
        if (actual && !plan.full()) {
            for (IrisMeshCamera stream : plan.streams.apply(new NativeDemand(parent, camera, traversal.size(), Math.min(plan.depth, recursion)))) {
                plan.add(stream.append(cameras(traversal), text(resolve(parent.destination(), plan.pack))));
                if (plan.full()) {
                    return;
                }
            }
        }
    }

    private static List<PortalView> nearby(PreparationPlan plan, ClientLevel level, PreparationCamera camera, int depth,
                                           PortalView parent, int ceiling) {
        return PortalViewSelection.collect(parent == null ? List.of() : plan.children.apply(parent), plan.views,
            new PortalViewSelection.Options(level, depth, Math.min(plan.depth, ceiling), plan.range, view -> available(plan, view)),
            camera.selection());
    }

    private static boolean available(PreparationPlan plan, PortalView view) {
        if (view.failing(plan.now)) {
            return false;
        }
        ClientLevel destination = view.destination();
        if (destination != plan.home) {
            return destination == ClientWorldLoader.mainLevel() || ClientWorldLoader.residentRenderer(destination) != null;
        }
        Vec3d mapped = view.toDestination().point(view.surface().planePoint());
        double dx = mapped.x() - plan.homeEye.x();
        double dz = mapped.z() - plan.homeEye.z();
        return dx * dx + dz * dz <= plan.reach * plan.reach;
    }

    private static PreparationCamera through(PreparationCamera camera, PortalView view) {
        PortalViewSelection.Camera inner = PortalViewSelection.through(camera.selection(), view);
        return new PreparationCamera(inner.eye(), inner.view(), inner.projection(), inner.frustum(), inner.window(), inner.exit(), inner.zeroToOne());
    }

    private static List<IrisViewPipelinePath.Camera> cameras(List<PortalView> traversal) {
        List<IrisViewPipelinePath.Camera> cameras = new ArrayList<>(traversal.size());
        for (PortalView view : traversal) {
            cameras.add(IrisViewPipelines.camera(view));
        }
        return cameras;
    }

    private static PreparationCamera past(PreparationCamera camera, PortalView view) {
        double distance = view.surface().signedDistance(camera.eye()) + NEAR_CULL_BLOCKS;
        Vec3d eye = camera.eye().subtract(view.surface().servedNormal().multiply(distance));
        return positioned(camera, eye, camera.view());
    }

    private static PreparationCamera positioned(PreparationCamera camera, Vec3d eye, Matrix4fc rotation) {
        if (rotation == null || camera.projection() == null) {
            return new PreparationCamera(eye, null, null, null, PortalViewSelection.Window.FULL, null, camera.zeroToOne());
        }
        Frustum frustum = new Frustum(new Matrix4f(rotation), PortalLayerMath.cullingProjection(camera.projection(),
            camera.zeroToOne()));
        frustum.prepare(eye.x(), eye.y(), eye.z());
        return new PreparationCamera(eye, rotation, camera.projection(), frustum, PortalViewSelection.Window.FULL, null, camera.zeroToOne());
    }

    private static List<String> pending() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        Optional<ShaderPack> pack = Iris.getCurrentPack();
        if (connection == null || minecraft.level == null || pack.isEmpty()) {
            return List.of();
        }
        Registry<DimensionType> types = connection.registryAccess().lookupOrThrow(Registries.DIMENSION_TYPE);
        List<String> dimensions = new ArrayList<>(connection.levels().size());
        for (ResourceKey<Level> level : connection.levels()) {
            dimensions.add(text(resolve(level.identifier(), types, pack.get())));
        }
        Map<NamespacedId, WorldRenderingPipeline> existing = ((IrisPortalPipelineAccess) Iris.getPipelineManager()).wormholes$pipelines();
        Set<String> created = new HashSet<>(existing.size());
        for (NamespacedId dimension : existing.keySet()) {
            created.add(text(dimension));
        }
        List<String> order = IrisPipelineSchedule.order(text(Iris.getCurrentDimension()), dimensions);
        return IrisPipelineSchedule.pending(order, created, FAILED);
    }

    private static void create(String dimension) {
        PipelineManager manager = Iris.getPipelineManager();
        NamespacedId target = new NamespacedId(dimension);
        boolean current = target.equals(Iris.getCurrentDimension());
        WorldRenderingPipeline selected = manager.getPipelineNullable();
        long started = System.nanoTime();
        try {
            WorldRenderingPipeline pipeline = manager.preparePipeline(target);
            if (!current && pipeline instanceof IrisRenderingPipeline) {
                ((IrisPortalRenderingAccess) pipeline).wormholes$initializedBlockIds(true);
            }
            LOGGER.info("Iris pipeline {} ready in {} ms", dimension, (System.nanoTime() - started) / 1_000_000L);
        } catch (RuntimeException failure) {
            FAILED.add(dimension);
            LOGGER.error("Unable to create the Iris pipeline for {}; portal views into it compile it on first use", dimension, failure);
        } finally {
            if (!current) {
                ((IrisPortalPipelineAccess) manager).wormholes$pipeline(selected);
            }
        }
    }

    private static NamespacedId resolve(Identifier level, Registry<DimensionType> types, ShaderPack pack) {
        DimensionType.Skybox skybox = types.get(ResourceKey.create(Registries.DIMENSION_TYPE, level))
            .map(Holder::value).map(DimensionType::skybox).orElse(DimensionType.Skybox.OVERWORLD);
        return resolve(level, skybox, pack);
    }

    private static NamespacedId resolve(ClientLevel level, ShaderPack pack) {
        return resolve(level.dimension().identifier(), level.dimensionType().skybox(), pack);
    }

    private static NamespacedId resolve(Identifier level, DimensionType.Skybox skybox, ShaderPack pack) {
        NamespacedId dimension = new NamespacedId(level.getNamespace(), level.getPath());
        if (pack.getDimensionMap().containsKey(dimension)) {
            return dimension;
        }
        return switch (skybox) {
            case END -> DimensionId.END;
            case OVERWORLD -> DimensionId.OVERWORLD;
            case NONE -> dimension;
        };
    }

    private static String text(NamespacedId dimension) {
        return dimension.getNamespace() + ":" + dimension.getName();
    }

    record PreparationCamera(Vec3d eye, Matrix4fc view, Matrix4fc projection, Frustum frustum, PortalViewSelection.Window window,
                             PortalSurface exit, boolean zeroToOne) {
        PortalViewSelection.Camera selection() {
            return new PortalViewSelection.Camera(eye, view, projection, frustum, window, exit, zeroToOne);
        }
    }

    record NativeDemand(PortalView parent, PreparationCamera camera, int depth, int ceiling) {
    }

    record PreparationOptions(List<PortalView> views, Function<PortalView, List<PortalView>> children,
                              Predicate<IrisViewPipelinePath> prepared, ClientLevel home, Vec3d homeEye, int chunks, ShaderPack pack, int depth) {
    }

    record Preparation(List<IrisViewPipelinePath> paths, int capacityMisses, List<IrisViewPipelinePath> actual,
                       Map<IrisViewPipelinePath, PortalView> anchors) {
    }

    record NativePreparation(IrisViewPipelinePath path, List<PortalView> traversal) {
        NativePreparation {
            traversal = List.copyOf(traversal);
        }

        boolean coversForecast(NativePreparation forecast, ClientLevel home) {
            PortalView root = forecast.traversal().getFirst();
            if (root.kind() != PortalView.Kind.CROSSING && root.kind() != PortalView.Kind.RETURN
                || root.destination() != home || forecast.traversal().size() != traversal.size() + 1
                || !path.dimension().equals(forecast.path().dimension())) {
                return false;
            }
            ClientLevel source = home;
            for (int index = 0; index < traversal.size(); index++) {
                PortalView expected = traversal.get(index);
                PortalView candidate = forecast.traversal().get(index + 1);
                if (!path.cameras().get(index).equals(forecast.path().cameras().get(index + 1))
                    || expected.source() != source || candidate.source() != expected.source()
                    || candidate.destination() != expected.destination()) {
                    return false;
                }
                source = expected.destination();
            }
            return true;
        }
    }

    private record FutureCamera(PortalView view, PortalView anchor, PreparationCamera render, IrisViewPipelinePath path) {
    }

    private static final class PreparationPlan {
        private final List<PortalView> views;
        private final Function<PortalView, List<PortalView>> children;
        private final ClientLevel home;
        private final Vec3d homeEye;
        private final double range;
        private final double reach;
        private final long now = System.currentTimeMillis();
        private final ShaderPack pack;
        private final int depth;
        private final Set<IrisViewPipelinePath> paths = new LinkedHashSet<>();
        private final List<NativePreparation> sourceNative = new ArrayList<>(MAX_PREPARATION_VIEWS);
        private final Map<IrisViewPipelinePath, PortalView> anchors = new LinkedHashMap<>();
        private final Function<NativeDemand, List<IrisMeshCamera>> streams;

        private PreparationPlan(PreparationOptions options, Function<NativeDemand, List<IrisMeshCamera>> streams) {
            this.streams = streams;
            views = options.views();
            children = options.children();
            home = options.home();
            homeEye = options.homeEye();
            range = options.chunks() * 16.0D;
            reach = (options.chunks() - 1) * 16.0D;
            pack = options.pack();
            depth = options.depth();
        }

        private boolean full() {
            return paths.size() == MAX_PREPARATION_VIEWS;
        }

        private void addNative(List<PortalView> traversal, boolean actual) {
            NativePreparation preparation = new NativePreparation(path(cameras(traversal), traversal.getLast().destination()), traversal);
            if (!actual) {
                for (NativePreparation existing : sourceNative) {
                    if (existing.coversForecast(preparation, home)) {
                        return;
                    }
                }
            }
            if (add(preparation.path()) && actual) {
                sourceNative.add(preparation);
            }
        }

        private IrisViewPipelinePath path(List<IrisViewPipelinePath.Camera> traversal, ClientLevel destination) {
            return new IrisViewPipelinePath(traversal, text(resolve(destination, pack)));
        }

        private boolean add(IrisViewPipelinePath path) {
            if (paths.contains(path)) {
                return true;
            }
            return !full() && paths.add(path);
        }
    }
}
