package art.arcane.wormholes.modded.client.render.iris;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.render.stencil.PortalSurface;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import art.arcane.wormholes.modded.client.render.stencil.PortalViews;
import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

public class IrisPipelinesPreloadTest extends MinecraftTestBase {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String END = "minecraft:the_end";
    private static final Vec3d EYE = new Vec3d(1.0D, 65.0D, 4.0D);

    @Test
    public void visibleResidentApproachPreparesBothCrossingRootsAndTheHiddenReturnRoot() {
        try (Scene scene = new Scene()) {
            PortalView arm = scene.arm(0);
            assertFalse(arm.exit().servesEye(arm.toDestination().point(EYE)));
            List<IrisViewPipelinePath> paths = scene.paths(List.of(arm));
            assertEquals(4, paths.size());
            assertTrue(paths.contains(new IrisViewPipelinePath(List.of(IrisViewPipelines.camera(arm)), END)));
            assertTrue(paths.contains(new IrisViewPipelinePath(
                List.of(IrisViewPipelines.camera(future(arm, PortalView.Kind.CROSSING, false))), END)));
            assertTrue(paths.contains(new IrisViewPipelinePath(
                List.of(IrisViewPipelines.camera(future(arm, PortalView.Kind.CROSSING, true))), OVERWORLD)));
            assertTrue(paths.contains(new IrisViewPipelinePath(
                List.of(IrisViewPipelines.camera(future(arm, PortalView.Kind.RETURN, true))), OVERWORLD)));
        }
    }

    @Test
    public void culledOrUnavailableApproachesDoNotWarmTheirDestinationWorlds() {
        try (Scene scene = new Scene()) {
            PortalView arm = scene.arm(0);
            when(scene.frustum.isVisible(any())).thenReturn(false);
            assertTrue(scene.paths(List.of(arm)).isEmpty());
            when(scene.frustum.isVisible(any())).thenReturn(true);
            scene.worlds.when(() -> ClientWorldLoader.residentRenderer(scene.destination)).thenReturn(null);
            assertTrue(scene.paths(List.of(arm)).isEmpty());
        }
    }

    @Test
    public void nearbyApproachesKeepThePreparationBudgetAtTwelve() {
        try (Scene scene = new Scene()) {
            List<PortalView> views = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                views.add(scene.arm(index * 2));
            }
            List<IrisViewPipelinePath> paths = scene.paths(views);
            assertEquals(12, paths.size());
            assertEquals(12, paths.stream().distinct().count());
            assertTrue(paths.stream().allMatch(path -> path.cameras().getFirst().kind().equals(PortalView.Kind.ARM.name())));
            assertEquals(60, scene.preparation(views).capacityMisses());
        }
    }

    @Test
    public void futureReturnRootPreparesReachableSourceSideCamerasWithTheirOwnPrefixes() {
        try (Scene scene = new Scene()) {
            PortalView arm = scene.arm(0);
            PortalView mirror = mock(PortalView.class);
            PortalSurface surface = PortalSurface.of(geometry(0, 4, true));
            when(mirror.kind()).thenReturn(PortalView.Kind.MIRROR);
            when(mirror.key()).thenReturn("source-mirror");
            when(mirror.source()).thenReturn(scene.source);
            when(mirror.destination()).thenReturn(scene.source);
            when(mirror.surface()).thenReturn(surface);
            when(mirror.exit()).thenReturn(surface);
            when(mirror.toDestination()).thenReturn(Similarity.IDENTITY);
            when(mirror.recursion()).thenReturn(2);
            List<IrisViewPipelinePath> paths = scene.paths(List.of(arm, mirror));
            assertTrue(paths.contains(new IrisViewPipelinePath(List.of(
                IrisViewPipelines.camera(future(arm, PortalView.Kind.RETURN, true)), IrisViewPipelines.camera(mirror)), OVERWORLD)));
            assertTrue(paths.contains(new IrisViewPipelinePath(List.of(
                IrisViewPipelines.camera(future(arm, PortalView.Kind.CROSSING, true)), IrisViewPipelines.camera(mirror)), OVERWORLD)));
        }
    }

    private static PortalView future(PortalView arm, PortalView.Kind kind, boolean reverse) {
        ClientLevel source = reverse ? arm.destination() : arm.source();
        return PortalViews.futureTravelViews(arm).stream()
            .filter(view -> view.kind() == kind && view.source() == source).findFirst().orElseThrow();
    }

    private static ApertureDescriptor geometry(int x, int z, boolean mirror) {
        return new ApertureDescriptor(x, 64, z, mirror ? Face.N.ordinal() : Face.S.ordinal(), true, 0, mirror, 2, 3, new long[]{63L}, ShapeDescriptor.FULL,
            0.0F, 0.0F, 0.0F, 16, 2, 0, 0, 0, 0, 0, 0, 0.0D, 0, 0L, List.of());
    }

    private static final class Scene implements AutoCloseable {
        private final ClientLevel source = mock(ClientLevel.class);
        private final ClientLevel destination = mock(ClientLevel.class);
        private final ShaderPack pack = mock(ShaderPack.class);
        private final Frustum frustum = mock(Frustum.class);
        private final MockedStatic<ClientWorldLoader> worlds = mockStatic(ClientWorldLoader.class);

        private Scene() {
            when(source.dimension()).thenReturn(Level.OVERWORLD);
            when(destination.dimension()).thenReturn(Level.END);
            DimensionType sourceType = mock(DimensionType.class);
            DimensionType destinationType = mock(DimensionType.class);
            when(sourceType.skybox()).thenReturn(DimensionType.Skybox.OVERWORLD);
            when(destinationType.skybox()).thenReturn(DimensionType.Skybox.END);
            when(source.dimensionType()).thenReturn(sourceType);
            when(destination.dimensionType()).thenReturn(destinationType);
            when(pack.getDimensionMap()).thenReturn(Map.of());
            when(frustum.isVisible(any())).thenReturn(true);
            worlds.when(ClientWorldLoader::mainLevel).thenReturn(source);
            LevelRenderer renderer = mock(LevelRenderer.class);
            worlds.when(() -> ClientWorldLoader.residentRenderer(destination)).thenReturn(renderer);
        }

        private PortalView arm(int x) {
            PortalView view = mock(PortalView.class);
            PortalSurface surface = PortalSurface.of(geometry(x, 0, false));
            Similarity transform = Similarity.of(OpticTransform.IDENTITY, 1.0D);
            when(view.kind()).thenReturn(PortalView.Kind.ARM);
            when(view.key()).thenReturn("arm-" + x);
            when(view.source()).thenReturn(source);
            when(view.destination()).thenReturn(destination);
            when(view.surface()).thenReturn(surface);
            when(view.exit()).thenReturn(surface.through(transform));
            when(view.toDestination()).thenReturn(transform);
            when(view.recursion()).thenReturn(2);
            return view;
        }

        private List<IrisViewPipelinePath> paths(List<PortalView> views) {
            return preparation(views).paths();
        }

        private IrisPipelines.Preparation preparation(List<PortalView> views) {
            IrisPipelines.PreparationOptions options = new IrisPipelines.PreparationOptions(views, source, EYE, 8, pack, 2);
            return IrisPipelines.preparation(options, new IrisPipelines.PreparationCamera(EYE, null, null, frustum));
        }

        @Override
        public void close() {
            worlds.close();
        }
    }
}
