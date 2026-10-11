package art.arcane.wormholes.modded.client.render.iris;

import art.arcane.optics.math.Box;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.stencil.PortalLayerMath;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import art.arcane.wormholes.modded.client.render.stencil.PortalViews;
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
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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
            camera.viewRotationMatrix, projection, camera.cullFrustum));
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
        PreparationOptions options = new PreparationOptions(client.portalViews().current(), minecraft.level,
            camera.eye(), chunks, pack.get(), camera.frustum() == null ? 1 : MAX_PREPARATION_DEPTH);
        Preparation preparation = preparation(options, camera);
        IrisViewPipelines.continueCameras(client.portalViews().takeCameraContinuations(), options.views(), preparation.paths());
        IrisViewPipelines.beginPreparationPlan(preparation.paths(), preparation.capacityMisses());
    }

    static Preparation preparation(PreparationOptions options, PreparationCamera camera) {
        PreparationPlan plan = new PreparationPlan(options);
        List<PortalView> roots = nearby(plan, options.home(), camera, 0);
        for (PortalView view : roots) {
            plan.add(List.of(IrisViewPipelines.camera(view)), view.destination());
            if (!plan.full()) {
                requestChildren(plan, new ArrayList<>(List.of(IrisViewPipelines.camera(view))), view.destination(), through(camera, view));
            }
        }
        List<FutureCamera> future = new ArrayList<>(MAX_PREPARATION_VIEWS);
        int capacityMisses = 0;
        for (PortalView view : roots) {
            for (PortalView travel : PortalViews.futureTravelViews(view)) {
                IrisViewPipelinePath.Camera identity = IrisViewPipelines.camera(travel);
                if (!plan.add(List.of(identity), travel.destination())) {
                    capacityMisses++;
                    continue;
                }
                PreparationCamera render = travel.destination() == view.source() ? past(camera, view) : through(camera, travel);
                future.add(new FutureCamera(identity, travel.destination(), render));
            }
        }
        for (FutureCamera arrival : future) {
            requestChildren(plan, new ArrayList<>(List.of(arrival.camera())), arrival.level(), arrival.render());
        }
        return new Preparation(List.copyOf(plan.paths), capacityMisses);
    }

    private static void requestChildren(PreparationPlan plan, List<IrisViewPipelinePath.Camera> traversal, ClientLevel level,
                                        PreparationCamera camera) {
        if (plan.full() || traversal.size() >= plan.depth) {
            return;
        }
        for (PortalView view : nearby(plan, level, camera, traversal.size())) {
            if (plan.full()) {
                return;
            }
            traversal.add(IrisViewPipelines.camera(view));
            plan.add(traversal, view.destination());
            requestChildren(plan, traversal, view.destination(), through(camera, view));
            traversal.removeLast();
        }
    }

    private static List<PortalView> nearby(PreparationPlan plan, ClientLevel level, PreparationCamera camera, int depth) {
        List<PortalView> nearby = new ArrayList<>();
        for (PortalView view : plan.views) {
            if (view.source() == level && depth < view.recursion() && available(plan, view)
                && view.surface().servesEye(camera.eye()) && view.surface().distance(camera.eye()) <= plan.range
                && visible(view, camera)) {
                nearby.add(view);
            }
        }
        nearby.sort(Comparator.comparingDouble(view -> view.surface().distance(camera.eye())));
        return nearby;
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

    private static boolean visible(PortalView view, PreparationCamera camera) {
        if (camera.frustum() == null || view.surface().distance(camera.eye()) <= NEAR_CULL_BLOCKS) {
            return true;
        }
        Box area = view.surface().area();
        return camera.frustum().isVisible(new AABB(area.getXa(), area.getYa(), area.getZa(),
            area.getXb(), area.getYb(), area.getZb()).inflate(0.01D));
    }

    private static PreparationCamera through(PreparationCamera camera, PortalView view) {
        Vec3d eye = PortalLayerMath.innerCamera(view.toDestination(), camera.eye());
        if (camera.view() == null || camera.projection() == null) {
            return new PreparationCamera(eye, null, null, null);
        }
        Matrix4f rotation = PortalLayerMath.innerView(camera.view(), view.toDestination());
        return positioned(camera, eye, rotation);
    }

    private static PreparationCamera past(PreparationCamera camera, PortalView view) {
        double distance = view.surface().signedDistance(camera.eye()) + NEAR_CULL_BLOCKS;
        Vec3d eye = camera.eye().subtract(view.surface().servedNormal().multiply(distance));
        return positioned(camera, eye, camera.view());
    }

    private static PreparationCamera positioned(PreparationCamera camera, Vec3d eye, Matrix4fc rotation) {
        if (rotation == null || camera.projection() == null) {
            return new PreparationCamera(eye, null, null, null);
        }
        Frustum frustum = new Frustum(new Matrix4f(rotation), PortalLayerMath.cullingProjection(camera.projection(),
            RenderSystem.getDevice().getDeviceInfo().isZZeroToOne()));
        frustum.prepare(eye.x(), eye.y(), eye.z());
        return new PreparationCamera(eye, rotation, camera.projection(), frustum);
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

    record PreparationCamera(Vec3d eye, Matrix4fc view, Matrix4fc projection, Frustum frustum) {
    }

    record PreparationOptions(List<PortalView> views, ClientLevel home, Vec3d homeEye, int chunks,
                              ShaderPack pack, int depth) {
    }

    record Preparation(List<IrisViewPipelinePath> paths, int capacityMisses) {
    }

    private record FutureCamera(IrisViewPipelinePath.Camera camera, ClientLevel level, PreparationCamera render) {
    }

    private static final class PreparationPlan {
        private final List<PortalView> views;
        private final ClientLevel home;
        private final Vec3d homeEye;
        private final double range;
        private final double reach;
        private final long now = System.currentTimeMillis();
        private final ShaderPack pack;
        private final int depth;
        private final Set<IrisViewPipelinePath> paths = new LinkedHashSet<>();

        private PreparationPlan(PreparationOptions options) {
            views = options.views();
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

        private boolean add(List<IrisViewPipelinePath.Camera> traversal, ClientLevel destination) {
            IrisViewPipelinePath path = new IrisViewPipelinePath(traversal, text(resolve(destination, pack)));
            if (paths.contains(path)) {
                return true;
            }
            return !full() && paths.add(path);
        }
    }
}
