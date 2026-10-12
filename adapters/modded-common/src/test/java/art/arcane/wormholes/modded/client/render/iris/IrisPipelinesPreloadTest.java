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
import art.arcane.wormholes.modded.client.render.stencil.PortalViewSelection;
import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.phys.AABB;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

public class IrisPipelinesPreloadTest extends MinecraftTestBase {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String END = "minecraft:the_end";
    private static final Vec3d EYE = new Vec3d(1.0D, 65.0D, 4.0D);

    @Test
    public void offscreenSourceOwnersFollowNineRequiredPathsWithoutBecomingActualOrTravelRoots() {
        try (Scene scene = new Scene()) {
            PortalView first = scene.arm(0);
            PortalView second = scene.arm(3);
            PortalView visible = scene.sourceMirror("visible", geometry(6, 6, true));
            PortalView hiddenA = scene.sourceMirror("hidden-a", geometry(20, 6, true));
            PortalView hiddenB = scene.sourceMirror("hidden-b", geometry(24, 6, true));
            when(hiddenB.kind()).thenReturn(PortalView.Kind.ARM);
            when(hiddenB.surface()).thenReturn(PortalSurface.of(geometry(24, 0, false)));
            doAnswer(invocation -> invocation.<AABB>getArgument(0).minX < 10.0D)
                .when(scene.frustum).isVisible(any());
            IrisPipelines.Preparation preparation = scene.preparation(List.of(first, second, visible, hiddenB, hiddenA));
            assertEquals(3, preparation.actual().size());
            assertEquals(11, preparation.paths().size());
            assertEquals(8, preparation.anchors().size());
            assertEquals(preparation.actual(), preparation.paths().subList(0, 3));
            assertTrue(preparation.paths().subList(3, 9).stream().allMatch(path -> path.cameras().getFirst().kind().equals("CROSSING")
                || path.cameras().getFirst().kind().equals("RETURN")));
            IrisViewPipelinePath pathA = nativePreparation(List.of(hiddenA), OVERWORLD).path();
            IrisViewPipelinePath pathB = nativePreparation(List.of(hiddenB), OVERWORLD).path();
            assertEquals(List.of(pathA, pathB), preparation.paths().subList(9, 11));
            assertFalse(preparation.actual().contains(pathA));
            assertFalse(preparation.actual().contains(pathB));
            assertTrue(preparation.paths().stream().allMatch(path -> path.cameras().size() == 1));
            scene.prepared.add(pathB);
            assertEquals(List.of(pathB, pathA), scene.preparation(List.of(hiddenA, visible, second, hiddenB, first)).paths().subList(9, 11));
        }
    }

    @Test
    public void offscreenPreparationRequiresAvailableUniqueServedReachablePhysicalRootsAndRecursion() {
        for (String rejection : List.of("realm", "unavailable", "side", "range", "reach", "recursion", "ambiguous", "departure-depth")) {
            try (Scene scene = new Scene()) {
                PortalView arm = scene.arm(0);
                PortalView hidden = scene.sourceMirror("hidden", geometry(20, 6, true));
                doAnswer(invocation -> invocation.<AABB>getArgument(0).minX < 10.0D)
                    .when(scene.frustum).isVisible(any());
                List<PortalView> views = new ArrayList<>(List.of(arm, hidden));
                switch (rejection) {
                    case "realm" -> when(hidden.source()).thenReturn(scene.destination);
                    case "unavailable" -> {
                        when(hidden.destination()).thenReturn(mock(ClientLevel.class));
                    }
                    case "side" -> when(hidden.surface()).thenReturn(PortalSurface.of(geometry(20, 2, true)));
                    case "range" -> when(hidden.surface()).thenReturn(PortalSurface.of(geometry(256, 6, true)));
                    case "reach" -> when(hidden.toDestination()).thenReturn(Similarity.of(OpticTransform.translation(256.0D, 0.0D, 0.0D), 1.0D));
                    case "recursion" -> when(hidden.recursion()).thenReturn(1);
                    case "ambiguous" -> views.add(scene.sourceMirror("duplicate", geometry(20, 6, true)));
                    case "departure-depth" -> when(arm.recursion()).thenReturn(1);
                    default -> throw new IllegalStateException(rejection);
                }
                IrisPipelines.Preparation preparation = scene.preparation(views);
                assertFalse(rejection, preparation.paths().stream().anyMatch(path -> path.cameras().size() == 1
                    && path.cameras().getFirst().key().equals("hidden")));
            }
        }
    }

    @Test
    public void requiredTravelAndStreamedDemandConsumeCapacityBeforeOffscreenOwners() {
        try (Scene scene = new Scene()) {
            List<PortalView> views = new ArrayList<>();
            for (int index = 0; index < 4; index++) {
                views.add(scene.arm(index * 2));
            }
            views.add(scene.sourceMirror("hidden", geometry(24, 6, true)));
            doAnswer(invocation -> invocation.<AABB>getArgument(0).minX < 10.0D)
                .when(scene.frustum).isVisible(any());
            IrisMeshCamera stream = new IrisMeshCamera(List.of(geometry(0, -8, true)), OVERWORLD, END, Similarity.IDENTITY);
            IrisPipelines.Preparation preparation = IrisPipelines.preparation(scene.options(views), scene.camera(), ignored -> List.of(stream));
            assertEquals(8, preparation.actual().size());
            assertEquals(12, preparation.paths().size());
            assertEquals(8, preparation.capacityMisses());
            assertFalse(preparation.paths().stream().anyMatch(path -> path.cameras().getFirst().key().equals("hidden")));
        }
    }

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
    public void boundedFuturePlanKeepsAnAlreadyPreparedArrivalAheadOfNewSpeculation() {
        try (Scene scene = new Scene()) {
            List<PortalView> arms = List.of(scene.arm(0), scene.arm(3), scene.arm(6), scene.arm(9));
            PortalView returning = future(arms.getLast(), PortalView.Kind.RETURN, true);
            IrisViewPipelinePath retained = new IrisViewPipelinePath(List.of(IrisViewPipelines.camera(returning)), OVERWORLD);
            scene.prepared.add(retained);
            List<IrisViewPipelinePath> paths = scene.paths(arms);
            assertEquals(12, paths.size());
            assertTrue(paths.contains(retained));
            for (PortalView arm : arms) {
                assertTrue(paths.contains(new IrisViewPipelinePath(List.of(IrisViewPipelines.camera(arm)), END)));
            }
            assertEquals(4, scene.preparation(arms).capacityMisses());
        }
    }

    @Test
    public void knownStreamedChildrenPrepareBeforeSpeculativeTravelWithExactParentPaths() {
        try (Scene scene = new Scene()) {
            List<PortalView> arms = List.of(scene.arm(0), scene.arm(3), scene.arm(6), scene.arm(9));
            Map<PortalView, IrisMeshCamera> streams = new HashMap<>();
            for (PortalView arm : arms) {
                streams.put(arm, new IrisMeshCamera(List.of(geometry(0, -4, true)), OVERWORLD, END, Similarity.IDENTITY));
            }
            IrisPipelines.Preparation combined = IrisPipelines.preparation(scene.options(arms), scene.camera(),
                demand -> List.of(streams.get(demand.parent())));
            assertEquals(12, combined.paths().size());
            assertEquals(8, combined.actual().size());
            assertEquals(combined.actual(), combined.paths().subList(0, combined.actual().size()));
            for (int index = 0; index < arms.size(); index++) {
                IrisViewPipelinePath path = streams.get(arms.get(index)).append(List.of(IrisViewPipelines.camera(arms.get(index))), END);
                assertTrue(combined.actual().contains(path));
                assertEquals(END, path.dimension());
            }
            assertEquals(8, combined.capacityMisses());
        }
    }

    @Test
    public void futureReturnRootsKeepTheActualMirrorInsteadOfPreparingItsDuplicatePrefixes() {
        try (Scene scene = new Scene()) {
            PortalView arm = scene.arm(0);
            PortalView mirror = mock(PortalView.class);
            PortalSurface surface = PortalSurface.of(geometry(0, 4, true));
            when(mirror.kind()).thenReturn(PortalView.Kind.MIRROR);
            when(mirror.key()).thenReturn("source-mirror");
            when(mirror.historyKey()).thenReturn("source-mirror");
            when(mirror.source()).thenReturn(scene.source);
            when(mirror.destination()).thenReturn(scene.source);
            when(mirror.surface()).thenReturn(surface);
            when(mirror.exit()).thenReturn(surface);
            when(mirror.toDestination()).thenReturn(Similarity.IDENTITY);
            when(mirror.recursion()).thenReturn(2);
            List<IrisViewPipelinePath> paths = scene.paths(List.of(arm, mirror));
            assertTrue(paths.contains(new IrisViewPipelinePath(List.of(IrisViewPipelines.camera(mirror)), OVERWORLD)));
            assertFalse(paths.contains(new IrisViewPipelinePath(List.of(
                IrisViewPipelines.camera(future(arm, PortalView.Kind.RETURN, true)), IrisViewPipelines.camera(mirror)), OVERWORLD)));
            assertFalse(paths.contains(new IrisViewPipelinePath(List.of(
                IrisViewPipelines.camera(future(arm, PortalView.Kind.CROSSING, true)), IrisViewPipelines.camera(mirror)), OVERWORLD)));
        }
    }

    @Test
    public void repeatedNinePathPlansExcludeAllFourSourceFloorAndCeilingForecasts() {
        try (Scene scene = new Scene()) {
            PortalView arm = scene.arm(0);
            PortalView floor = scene.sourceMirror("floor", horizontal(64, Face.U));
            PortalView ceiling = scene.sourceMirror("ceiling", horizontal(68, Face.D));
            PortalView child = scene.child("destination-child", 0);
            List<PortalView> views = List.of(arm, floor, ceiling, child);
            IrisMeshCamera stream = new IrisMeshCamera(List.of(geometry(0, -8, true)), OVERWORLD, END, Similarity.IDENTITY);
            IrisPipelines.Preparation preparation = IrisPipelines.preparation(scene.options(views), scene.camera(),
                demand -> demand.parent() == arm ? List.of(stream) : List.of());
            assertEquals(9, preparation.paths().size());
            assertEquals(5, preparation.actual().size());
            assertEquals(preparation.actual(), preparation.paths().subList(0, preparation.actual().size()));
            for (PortalView mirror : List.of(floor, ceiling)) {
                assertTrue(preparation.actual().contains(new IrisViewPipelinePath(List.of(IrisViewPipelines.camera(mirror)), OVERWORLD)));
                for (PortalView.Kind kind : List.of(PortalView.Kind.CROSSING, PortalView.Kind.RETURN)) {
                    assertFalse(preparation.paths().contains(new IrisViewPipelinePath(List.of(
                        IrisViewPipelines.camera(future(arm, kind, true)), IrisViewPipelines.camera(mirror)), OVERWORLD)));
                }
            }
            IrisViewPipelinePath unmatched = new IrisViewPipelinePath(List.of(
                IrisViewPipelines.camera(future(arm, PortalView.Kind.CROSSING, false)), IrisViewPipelines.camera(child)), END);
            assertTrue(preparation.paths().contains(unmatched));
            assertTrue(preparation.actual().contains(stream.append(List.of(IrisViewPipelines.camera(arm)), END)));
            for (int index = 0; index < 20; index++) {
                scene.prepared.addAll(preparation.paths());
                assertEquals(preparation, IrisPipelines.preparation(scene.options(views), scene.camera(),
                    demand -> demand.parent() == arm ? List.of(stream) : List.of()));
            }
        }
    }

    @Test
    public void sourceSideCameraVisibleOnlyAfterTravelKeepsBothForecastPrefixes() {
        try (Scene scene = new Scene()) {
            PortalView arm = scene.arm(0);
            PortalView mirror = scene.sourceMirror("unmatched", geometry(0, 2, true));
            IrisPipelines.Preparation preparation = scene.preparation(List.of(arm, mirror));
            assertFalse(preparation.actual().contains(new IrisViewPipelinePath(List.of(IrisViewPipelines.camera(mirror)), OVERWORLD)));
            for (PortalView.Kind kind : List.of(PortalView.Kind.CROSSING, PortalView.Kind.RETURN)) {
                assertTrue(preparation.paths().contains(new IrisViewPipelinePath(List.of(
                    IrisViewPipelines.camera(future(arm, kind, true)), IrisViewPipelines.camera(mirror)), OVERWORLD)));
            }
        }
    }

    @Test
    public void skippingADuplicateForecastStillPreparesItsUnmatchedDeeperDescendants() {
        try (Scene scene = new Scene()) {
            scene.depth = 3;
            PortalView arm = scene.arm(0);
            PortalView first = scene.sourceMirror("first", geometry(0, 4, true));
            PortalView second = scene.sourceMirror("second", geometry(0, 2, true));
            when(arm.recursion()).thenReturn(3);
            when(first.recursion()).thenReturn(3);
            when(second.recursion()).thenReturn(3);
            PortalSurface exit = first.surface();
            when(first.exit()).thenReturn(exit);
            IrisPipelines.Preparation preparation = scene.preparation(List.of(arm, first, second));
            assertTrue(preparation.actual().contains(new IrisViewPipelinePath(List.of(IrisViewPipelines.camera(first)), OVERWORLD)));
            assertFalse(preparation.actual().contains(new IrisViewPipelinePath(List.of(
                IrisViewPipelines.camera(first), IrisViewPipelines.camera(second)), OVERWORLD)));
            for (PortalView.Kind kind : List.of(PortalView.Kind.CROSSING, PortalView.Kind.RETURN)) {
                IrisViewPipelinePath.Camera root = IrisViewPipelines.camera(future(arm, kind, true));
                assertFalse(preparation.paths().contains(new IrisViewPipelinePath(List.of(root, IrisViewPipelines.camera(first)), OVERWORLD)));
                assertTrue(preparation.paths().contains(new IrisViewPipelinePath(List.of(
                    root, IrisViewPipelines.camera(first), IrisViewPipelines.camera(second)), OVERWORLD)));
            }
        }
    }

    @Test
    public void exactSuffixCanCrossPhysicalWorldsWithoutDroppingAnyCamera() {
        try (Scene scene = new Scene()) {
            PortalView root = future(scene.arm(0), PortalView.Kind.RETURN, true);
            PortalView first = scene.arm(4);
            PortalView second = scene.child("second", 4);
            IrisPipelines.NativePreparation actual = nativePreparation(List.of(first, second), END);
            assertTrue(actual.coversForecast(nativePreparation(List.of(root, first, second), END), scene.source));
            assertFalse(actual.coversForecast(nativePreparation(List.of(root, first), END), scene.source));
            assertFalse(nativePreparation(List.of(first), END).coversForecast(nativePreparation(List.of(root, first, second), END), scene.source));
        }
    }

    @Test
    public void forecastSuffixMustMatchTheEntireTraversalIncludingEveryCameraIdentityField() {
        try (Scene scene = new Scene()) {
            PortalView root = future(scene.arm(0), PortalView.Kind.RETURN, true);
            PortalView first = scene.sourceMirror("first", geometry(0, 4, true));
            PortalView second = scene.sourceMirror("second", geometry(4, 4, true));
            IrisPipelines.NativePreparation actual = nativePreparation(List.of(first, second), OVERWORLD);
            IrisPipelines.NativePreparation forecast = nativePreparation(List.of(root, first, second), OVERWORLD);
            assertTrue(actual.coversForecast(forecast, scene.source));
            assertFalse(actual.coversForecast(nativePreparation(List.of(root, second), OVERWORLD), scene.source));
            assertFalse(actual.coversForecast(nativePreparation(List.of(root, second, first), OVERWORLD), scene.source));
            assertFalse(actual.coversForecast(nativePreparation(List.of(root, first, second), END), scene.source));
            IrisViewPipelinePath.Camera camera = IrisViewPipelines.camera(first);
            List<IrisViewPipelinePath.Camera> different = List.of(
                new IrisViewPipelinePath.Camera(PortalView.Kind.ARM.name(), camera.key(), camera.source(), camera.destination(), camera.transform()),
                new IrisViewPipelinePath.Camera(camera.kind(), List.of(camera.key(), "different-parent"), camera.source(), camera.destination(), camera.transform()),
                new IrisViewPipelinePath.Camera(camera.kind(), camera.key(), END, camera.destination(), camera.transform()),
                new IrisViewPipelinePath.Camera(camera.kind(), camera.key(), camera.source(), END, camera.transform()),
                new IrisViewPipelinePath.Camera(camera.kind(), camera.key(), camera.source(), camera.destination(), Similarity.of(OpticTransform.IDENTITY, 2.0D)),
                new IrisViewPipelinePath.Camera(camera.kind(), camera.key(), camera.source(), camera.destination(), Similarity.of(OpticTransform.translation(1.0D, 0.0D, 0.0D), 1.0D)));
            for (IrisViewPipelinePath.Camera replacement : different) {
                IrisViewPipelinePath path = new IrisViewPipelinePath(List.of(IrisViewPipelines.camera(root), replacement,
                    IrisViewPipelines.camera(second)), OVERWORLD);
                assertFalse(actual.coversForecast(new IrisPipelines.NativePreparation(path, forecast.traversal()), scene.source));
            }
            IrisViewPipelinePath.Camera streamed = new IrisViewPipelinePath.Camera("STREAMED", camera.key(), camera.source(), camera.destination(), camera.transform());
            assertFalse(actual.coversForecast(new IrisPipelines.NativePreparation(new IrisViewPipelinePath(List.of(
                IrisViewPipelines.camera(root), streamed, IrisViewPipelines.camera(second)), OVERWORLD), forecast.traversal()), scene.source));
        }
    }

    @Test
    public void equalDimensionNamesCannotReplacePhysicalRealmProofAtAnySuffixDepth() {
        try (Scene scene = new Scene()) {
            PortalView root = future(scene.arm(0), PortalView.Kind.RETURN, true);
            PortalView first = scene.sourceMirror("first", geometry(0, 4, true));
            PortalView second = scene.sourceMirror("second", geometry(4, 4, true));
            IrisPipelines.NativePreparation actual = nativePreparation(List.of(first, second), OVERWORLD);
            ClientLevel alias = mock(ClientLevel.class);
            when(alias.dimension()).thenReturn(Level.OVERWORLD);
            PortalView otherSource = copy(second);
            when(otherSource.source()).thenReturn(alias);
            assertFalse(actual.coversForecast(nativePreparation(List.of(root, first, otherSource), OVERWORLD), scene.source));
            PortalView otherFirstSource = copy(first);
            when(otherFirstSource.source()).thenReturn(alias);
            assertFalse(actual.coversForecast(nativePreparation(List.of(root, otherFirstSource, second), OVERWORLD), scene.source));
            PortalView otherDestination = copy(second);
            when(otherDestination.destination()).thenReturn(alias);
            assertFalse(actual.coversForecast(nativePreparation(List.of(root, first, otherDestination), OVERWORLD), scene.source));
            PortalView otherRoot = copy(root);
            when(otherRoot.destination()).thenReturn(alias);
            assertFalse(actual.coversForecast(nativePreparation(List.of(otherRoot, first, second), OVERWORLD), scene.source));
            assertFalse(actual.coversForecast(nativePreparation(List.of(root, first, second), OVERWORLD), alias));
        }
    }

    @Test
    public void onlySyntheticCrossingAndReturnPrefixesCanBeRemoved() {
        try (Scene scene = new Scene()) {
            PortalView mirror = scene.sourceMirror("mirror", geometry(0, 4, true));
            IrisPipelines.NativePreparation actual = nativePreparation(List.of(mirror), OVERWORLD);
            for (PortalView.Kind kind : PortalView.Kind.values()) {
                PortalView root = copy(future(scene.arm(0), PortalView.Kind.RETURN, true));
                when(root.kind()).thenReturn(kind);
                assertEquals(kind == PortalView.Kind.CROSSING || kind == PortalView.Kind.RETURN,
                    actual.coversForecast(nativePreparation(List.of(root, mirror), OVERWORLD), scene.source));
            }
            assertFalse(actual.coversForecast(nativePreparation(List.of(
                future(scene.arm(0), PortalView.Kind.CROSSING, false), mirror), OVERWORLD), scene.source));
        }
    }

    @Test
    public void branchOnlyChildIsPreparedWithItsActualParentTraversal() {
        try (Scene scene = new Scene()) {
            PortalView arm = scene.arm(0);
            PortalView child = scene.child("branch-child", 0);
            scene.children.put(arm, List.of(child));
            List<IrisViewPipelinePath> paths = scene.paths(List.of(arm));
            assertTrue(paths.contains(new IrisViewPipelinePath(List.of(IrisViewPipelines.camera(arm), IrisViewPipelines.camera(child)), END)));
            assertFalse(paths.contains(new IrisViewPipelinePath(List.of(IrisViewPipelines.camera(child)), END)));
        }
    }

    @Test
    public void nativeBranchWinsOverADuplicateRootOpeningDuringPreparation() {
        try (Scene scene = new Scene()) {
            PortalView arm = scene.arm(0);
            PortalView child = scene.child("branch-child", 0);
            PortalView duplicate = scene.child("root-alias", 0);
            scene.children.put(arm, List.of(child));
            List<IrisViewPipelinePath> paths = scene.paths(List.of(arm, duplicate));
            assertTrue(paths.contains(new IrisViewPipelinePath(List.of(IrisViewPipelines.camera(arm), IrisViewPipelines.camera(child)), END)));
            assertFalse(paths.contains(new IrisViewPipelinePath(List.of(IrisViewPipelines.camera(arm), IrisViewPipelines.camera(duplicate)), END)));
        }
    }

    @Test
    public void childRecursionCannotExceedItsParentsCeiling() {
        try (Scene scene = new Scene()) {
            scene.depth = 8;
            PortalView arm = scene.arm(0);
            PortalView child = scene.child("child", 0);
            PortalView grandchild = scene.child("grandchild", 6);
            when(child.recursion()).thenReturn(8);
            when(grandchild.recursion()).thenReturn(8);
            scene.children.put(arm, List.of(child));
            scene.children.put(child, List.of(grandchild));
            List<IrisViewPipelinePath> paths = scene.paths(List.of(arm));
            assertTrue(paths.contains(new IrisViewPipelinePath(List.of(IrisViewPipelines.camera(arm), IrisViewPipelines.camera(child)), END)));
            assertFalse(paths.stream().anyMatch(path -> path.cameras().contains(IrisViewPipelines.camera(grandchild))));
        }
    }

    @Test
    public void streamedDemandConsumesTheSharedBudgetBeforeTheNextNativeRoot() {
        try (Scene scene = new Scene()) {
            List<PortalView> arms = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                arms.add(scene.arm(index * 2));
            }
            IrisMeshCamera stream = new IrisMeshCamera(List.of(geometry(0, -4, true)), OVERWORLD, END, Similarity.IDENTITY);
            IrisPipelines.Preparation preparation = IrisPipelines.preparation(scene.options(arms), scene.camera(), ignored -> List.of(stream));
            assertEquals(12, preparation.actual().size());
            for (int index = 0; index < 6; index++) {
                IrisViewPipelinePath.Camera parent = IrisViewPipelines.camera(arms.get(index));
                assertEquals(new IrisViewPipelinePath(List.of(parent), END), preparation.actual().get(index * 2));
                assertEquals(stream.append(List.of(parent), END), preparation.actual().get(index * 2 + 1));
            }
            assertFalse(preparation.actual().contains(new IrisViewPipelinePath(List.of(IrisViewPipelines.camera(arms.get(6))), END)));
        }
    }

    @Test
    public void twelveSelectedPathsStayStableWhenUnopenedCandidatesChange() {
        try (Scene scene = new Scene()) {
            List<PortalView> arms = List.of(scene.arm(0), scene.arm(2), scene.arm(4), scene.arm(6), scene.arm(8), scene.arm(10));
            IrisMeshCamera stream = new IrisMeshCamera(List.of(geometry(0, -4, true)), OVERWORLD, END, Similarity.IDENTITY);
            List<IrisViewPipelinePath> selected = IrisPipelines.preparation(scene.options(arms), scene.camera(), ignored -> List.of(stream)).actual();
            assertEquals(12, selected.size());
            assertEquals(12, new HashSet<>(selected).size());
            for (int index = 0; index < 40; index++) {
                List<PortalView> candidates = new ArrayList<>(arms);
                candidates.add(scene.arm(256 + index));
                assertEquals(selected, IrisPipelines.preparation(scene.options(candidates), scene.camera(), ignored -> List.of(stream)).actual());
            }
        }
    }

    @Test
    public void childrenOnTheClippedSideOfTheirAncestorDoNotBecomeActualDemand() {
        try (Scene scene = new Scene()) {
            PortalView arm = scene.arm(0);
            PortalView child = scene.child("clipped", 0);
            when(child.surface()).thenReturn(PortalSurface.of(geometry(0, 2, false)));
            scene.children.put(arm, List.of(child));
            IrisPipelines.Preparation preparation = scene.preparation(List.of(arm));
            assertEquals(List.of(new IrisViewPipelinePath(List.of(IrisViewPipelines.camera(arm)), END)), preparation.actual());
        }
    }

    private static PortalView future(PortalView arm, PortalView.Kind kind, boolean reverse) {
        ClientLevel source = reverse ? arm.destination() : arm.source();
        return PortalViews.futureTravelViews(arm).stream()
            .filter(view -> view.kind() == kind && view.source() == source).findFirst().orElseThrow();
    }

    private static IrisPipelines.NativePreparation nativePreparation(List<PortalView> traversal, String dimension) {
        List<IrisViewPipelinePath.Camera> cameras = new ArrayList<>(traversal.size());
        for (PortalView view : traversal) {
            cameras.add(IrisViewPipelines.camera(view));
        }
        return new IrisPipelines.NativePreparation(new IrisViewPipelinePath(cameras, dimension), traversal);
    }

    private static PortalView copy(PortalView original) {
        PortalView.Kind kind = original.kind();
        Object key = original.key();
        Object historyKey = original.historyKey();
        ClientLevel source = original.source();
        ClientLevel destination = original.destination();
        Similarity transform = original.toDestination();
        PortalView view = mock(PortalView.class);
        when(view.kind()).thenReturn(kind);
        when(view.key()).thenReturn(key);
        when(view.historyKey()).thenReturn(historyKey);
        when(view.source()).thenReturn(source);
        when(view.destination()).thenReturn(destination);
        when(view.toDestination()).thenReturn(transform);
        return view;
    }

    private static ApertureDescriptor horizontal(int y, Face facing) {
        return new ApertureDescriptor(0, y, 3, facing.ordinal(), true, 0, true, 2, 3, new long[]{63L}, ShapeDescriptor.FULL,
            0.0F, 0.0F, 0.0F, 16, 2, 0, 0, 0, 0, 0, 0, 0.0D, 0, 0L, List.of());
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
        private final Map<PortalView, List<PortalView>> children = new HashMap<>();
        private final Set<IrisViewPipelinePath> prepared = new HashSet<>();
        private int depth = 2;

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
            when(view.historyKey()).thenReturn("arm-" + x);
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

        private PortalView child(String key, int x) {
            PortalView view = mock(PortalView.class);
            PortalSurface surface = PortalSurface.of(geometry(x, -4, false));
            when(view.kind()).thenReturn(PortalView.Kind.MIRROR);
            when(view.key()).thenReturn(key);
            when(view.historyKey()).thenReturn(key);
            when(view.source()).thenReturn(destination);
            when(view.destination()).thenReturn(destination);
            when(view.surface()).thenReturn(surface);
            when(view.exit()).thenReturn(surface);
            when(view.toDestination()).thenReturn(Similarity.IDENTITY);
            when(view.recursion()).thenReturn(2);
            return view;
        }

        private PortalView sourceMirror(String key, ApertureDescriptor geometry) {
            PortalView view = child(key, 0);
            PortalSurface exit = mock(PortalSurface.class);
            when(exit.signedDistance(any())).thenReturn(-1.0D);
            when(view.source()).thenReturn(source);
            when(view.destination()).thenReturn(source);
            when(view.surface()).thenReturn(PortalSurface.of(geometry));
            when(view.exit()).thenReturn(exit);
            return view;
        }

        private IrisPipelines.Preparation preparation(List<PortalView> views) {
            return IrisPipelines.preparation(options(views), camera());
        }

        private IrisPipelines.PreparationCamera camera() {
            return new IrisPipelines.PreparationCamera(EYE, null, null, frustum, PortalViewSelection.Window.FULL, null, false);
        }

        private IrisPipelines.PreparationOptions options(List<PortalView> views) {
            return new IrisPipelines.PreparationOptions(views,
                view -> children.getOrDefault(view, List.of()), prepared::contains, source, EYE, 8, pack, depth);
        }

        @Override
        public void close() {
            worlds.close();
        }
    }
}
