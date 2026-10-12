package art.arcane.wormholes.modded.client.render.iris;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.optics.stream.EnvironmentState;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.ClientPortal;
import art.arcane.wormholes.modded.client.ClientViewSession;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.ResidentLevels;
import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import art.arcane.wormholes.modded.client.render.stencil.PipelineBackend;
import art.arcane.wormholes.modded.client.render.stencil.PortalBackends;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import art.arcane.wormholes.modded.client.render.stencil.PortalSurface;
import art.arcane.wormholes.modded.client.render.stencil.PortalViews;
import art.arcane.wormholes.modded.client.render.stencil.PortalViewsCrossingGraphTest;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.level.Level;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

public class IrisViewPipelinePathTest extends MinecraftTestBase {
    private static final String OVERWORLD = "minecraft:overworld";

    @Test
    public void preparationAdmissionKeepsDemandAndRequiredTravelAheadOfSourceAndOptionalPaths() {
        Set<IrisViewPipelinePath> actual = new LinkedHashSet<>();
        for (int index = 0; index < 8; index++) {
            actual.add(new IrisViewPipelinePath(List.of(camera("actual-" + index)), OVERWORLD));
        }
        IrisViewPipelinePath mesh = new IrisViewPipelinePath(List.of(new IrisViewPipelinePath.Camera("STREAMED", "mesh",
            OVERWORLD, OVERWORLD, Similarity.IDENTITY)), OVERWORLD);
        IrisViewPipelinePath travel = new IrisViewPipelinePath(List.of(new IrisViewPipelinePath.Camera("CROSSING", "travel",
            OVERWORLD, OVERWORLD, Similarity.IDENTITY)), OVERWORLD);
        IrisViewPipelinePath carried = new IrisViewPipelinePath(List.of(new IrisViewPipelinePath.Camera("RETURN", "carried",
            OVERWORLD, OVERWORLD, Similarity.IDENTITY)), OVERWORLD);
        IrisViewPipelinePath source = new IrisViewPipelinePath(List.of(camera("offscreen")), OVERWORLD);
        IrisViewPipelinePath optional = new IrisViewPipelinePath(List.of(camera("optional")), OVERWORLD);
        Set<IrisViewPipelinePath> plan = new LinkedHashSet<>(List.of(optional, source, travel));
        Set<IrisViewPipelinePath> anchors = new LinkedHashSet<>(List.of(source, carried, travel));
        Set<IrisViewPipelinePath> admitted = IrisViewPipelines.preparationAdmission(actual, Set.of(mesh), plan, anchors);
        List<IrisViewPipelinePath> expected = new ArrayList<>(actual);
        expected.addAll(List.of(mesh, travel, carried, source));
        assertEquals(expected, new ArrayList<>(admitted));
        actual.add(new IrisViewPipelinePath(List.of(camera("actual-extra")), OVERWORLD));
        admitted = IrisViewPipelines.preparationAdmission(actual, Set.of(mesh), plan, anchors);
        assertEquals(12, admitted.size());
        assertTrue(admitted.containsAll(actual));
        assertTrue(admitted.containsAll(List.of(mesh, travel, carried)));
        assertFalse(admitted.contains(source));
        assertFalse(admitted.contains(optional));
    }

    @Test
    public void retiredPreparationClaimsStopRenewingAndExpireWithoutDoubleRelease() {
        AtomicLong clock = new AtomicLong(1L);
        List<AtomicInteger> released = new ArrayList<>();
        IrisViewPipelineCache<IrisViewPipelinePath, AtomicInteger> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(12, path -> new AtomicInteger(), released::add, clock::get));
        IrisViewPipelinePath path = new IrisViewPipelinePath(List.of(camera("reserved")), OVERWORLD);
        PortalView anchor = mock(PortalView.class);
        Map<IrisViewPipelinePath, PortalView> anchors = new HashMap<>();
        cache.prepare(path, 1, Set.of(), IrisViewPipelineCache.Preparation.SPECULATIVE);
        AtomicInteger value = cache.peek(path);
        clock.set(30_000_000_001L);
        IrisViewPipelines.retainPreparations(cache, anchors, Map.of(path, anchor), candidate -> true);
        assertEquals(30_000_000_001L, cache.snapshots().getFirst().lastUsedNanos());
        clock.set(60_000_000_001L);
        IrisViewPipelines.retainPreparations(cache, anchors, Map.of(), candidate -> false);
        assertTrue(anchors.isEmpty());
        assertEquals(30_000_000_001L, cache.snapshots().getFirst().lastUsedNanos());
        cache.expire(clock.get() - 60_000_000_000L);
        assertSame(value, cache.peek(path));
        clock.set(90_000_000_002L);
        cache.expire(clock.get() - 60_000_000_000L);
        assertEquals(List.of(value), released);
        cache.close();
        cache.close();
        assertEquals(1, released.size());
    }

    @Test
    public void currentPreparationClaimsReplaceRetiredClaimsWithinTwelveOwners() {
        IrisViewPipelineCache<IrisViewPipelinePath, AtomicInteger> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(12, path -> new AtomicInteger(), value -> {}, System::nanoTime));
        PortalView current = mock(PortalView.class);
        PortalView retired = mock(PortalView.class);
        Map<IrisViewPipelinePath, PortalView> previous = new HashMap<>();
        Map<IrisViewPipelinePath, PortalView> admitted = new HashMap<>();
        for (int index = 0; index < 12; index++) {
            IrisViewPipelinePath path = new IrisViewPipelinePath(List.of(camera("old-" + index)), OVERWORLD);
            cache.prepare(path, 1, Set.of(), IrisViewPipelineCache.Preparation.SPECULATIVE);
            previous.put(path, retired);
            admitted.put(new IrisViewPipelinePath(List.of(camera("new-" + index)), OVERWORLD), current);
        }
        IrisViewPipelines.retainPreparations(cache, previous, admitted, anchor -> anchor == current);
        assertEquals(admitted, previous);
        assertEquals(12, previous.size());
        assertEquals(12, cache.size());
        cache.close();
    }

    @Test
    public void elevenPreparedOwnersKeepSixTravelReservationsAndTwoLateSourceHistoriesForTwelvePhysicalCrossings() throws Exception {
        try (PortalViewsCrossingGraphTest.CrossingGraph graph = new PortalViewsCrossingGraphTest.CrossingGraph(Similarity.of(
            OpticTransform.translation(0.0D, 0.0D, 200.0D), 1.0D))) {
            PortalView departure = graph.views.current().getFirst();
            List<PortalView> sourceViews = List.of(graph.homePaths().get(0).getFirst(), graph.homePaths().get(4).getFirst());
            List<IrisViewPipelinePath> sources = new ArrayList<>();
            for (PortalView view : sourceViews) {
                sources.add(new IrisViewPipelinePath(List.of(camera(view)), OVERWORLD));
            }
            List<IrisViewPipelinePath> initial = new ArrayList<>();
            for (int index = 0; index < 3; index++) {
                initial.add(new IrisViewPipelinePath(List.of(camera("actual-" + index)), OVERWORLD));
            }
            Map<IrisViewPipelinePath, PortalView> admitted = new HashMap<>();
            for (int index = 0; index < 6; index++) {
                IrisViewPipelinePath path = new IrisViewPipelinePath(List.of(new IrisViewPipelinePath.Camera("CROSSING",
                    "reserved-" + index, OVERWORLD, OVERWORLD, Similarity.IDENTITY)), OVERWORLD);
                initial.add(path);
                admitted.put(path, departure);
            }
            for (int index = 0; index < sources.size(); index++) {
                admitted.put(sources.get(index), sourceViews.get(index));
            }
            AtomicInteger created = new AtomicInteger();
            AtomicLong clock = new AtomicLong(1L);
            List<AtomicInteger> released = new ArrayList<>();
            IrisViewPipelineCache<IrisViewPipelinePath, AtomicInteger> cache = new IrisViewPipelineCache<>(
                new IrisViewPipelineCache.Options<>(12, path -> new AtomicInteger(created.incrementAndGet()), released::add, clock::get));
            for (IrisViewPipelinePath path : initial) {
                assertTrue(cache.prepare(path, 1, Set.copyOf(initial), IrisViewPipelineCache.Preparation.SPECULATIVE));
            }
            for (IrisViewPipelinePath path : sources) {
                assertTrue(cache.prepare(path, 1, Set.copyOf(initial), IrisViewPipelineCache.Preparation.SPECULATIVE));
                assertFalse(cache.rendered(path));
            }
            Map<IrisViewPipelinePath, AtomicInteger> reservations = new HashMap<>();
            for (IrisViewPipelinePath path : initial.subList(3, 9)) {
                reservations.put(path, cache.peek(path));
            }
            List<AtomicInteger> histories = List.of(cache.peek(sources.get(0)), cache.peek(sources.get(1)));
            assertNotSame(histories.get(0), histories.get(1));
            Map<IrisViewPipelinePath, PortalView> anchors = new HashMap<>();
            IrisViewPipelines.retainPreparations(cache, anchors, admitted,
                anchor -> graph.views.retainsPreparation(anchor, graph.home, graph.eye, 128.0D, 112.0D));
            assertEquals(8, anchors.size());
            for (int trip = 0; trip < 6; trip++) {
                int frame = 2 + trip * 4;
                graph.crossOut();
                graph.arrive(false, false);
                clock.addAndGet(61_000_000_000L);
                IrisViewPipelines.retainPreparations(cache, anchors, Map.of(),
                    anchor -> graph.views.retainsPreparation(anchor, graph.destination, graph.arrivalEye(), 128.0D, 112.0D));
                Set<IrisViewPipelinePath> awaiting = new LinkedHashSet<>(anchors.keySet());
                for (IrisViewPipelinePath source : sources) {
                    assertTrue(IrisViewPipelines.awaitingTraversal(source, graph.views));
                    awaiting.add(source);
                }
                Map<IrisViewPipelinePath, IrisViewPipelinePath> continuations = new HashMap<>();
                IrisViewPipelines.continueOwnership(cache, continuations, Set.of(), awaiting, frame);
                IrisViewPipelinePath unrelated = new IrisViewPipelinePath(List.of(camera("unrelated-" + trip)), OVERWORLD);
                assertFalse(cache.adopt(unrelated, frame, new IrisViewPipelineCache.Adoption<>(initial::contains, (source, target) -> true)));
                for (IrisViewPipelinePath path : initial.subList(0, 3)) {
                    cache.retain(path);
                }
                cache.expire(clock.get() - 60_000_000_000L);
                assertTrue(released.stream().noneMatch(histories::contains));
                assertTrue(released.stream().noneMatch(reservations.values()::contains));
                graph.arrive(false, true);
                List<IrisViewPipelinePath> returning = new ArrayList<>();
                for (IrisViewPipelinePath source : sources) {
                    IrisViewPipelinePath target = IrisViewPipelines.continuedTraversalPath(source, graph.views);
                    assertNotEquals(source, target);
                    returning.add(target);
                    continuations.put(source, target);
                }
                IrisViewPipelines.continueOwnership(cache, continuations, Set.copyOf(returning), anchors.keySet(), frame + 1);
                for (int index = 0; index < returning.size(); index++) {
                    assertSame(histories.get(index), cache.enter(returning.get(index), frame + 1));
                    cache.peek(returning.get(index)).set(100 + index);
                    cache.exit(returning.get(index));
                }
                graph.arrive(true, true);
                Map<IrisViewPipelinePath.Camera, IrisViewPipelinePath.Camera> replacements = new HashMap<>();
                for (PortalViews.CameraContinuation continuation : graph.views.takeCameraContinuations()) {
                    replacements.put(camera(continuation.previous()), camera(continuation.next()));
                }
                List<IrisViewPipelinePath> armed = new ArrayList<>();
                for (IrisViewPipelinePath path : returning) {
                    IrisViewPipelinePath target = IrisViewPipelines.continuedPath(path, replacements);
                    armed.add(target);
                    continuations.put(path, target);
                }
                IrisViewPipelines.continueOwnership(cache, continuations, Set.copyOf(armed), anchors.keySet(), frame + 2);
                graph.crossBack();
                for (int index = 0; index < armed.size(); index++) {
                    IrisViewPipelinePath path = armed.get(index);
                    IrisViewPipelinePath target = IrisViewPipelines.continuedTraversalPath(path, graph.views);
                    assertEquals(sources.get(index), target);
                    continuations.put(path, target);
                }
                IrisViewPipelines.continueOwnership(cache, continuations, Set.copyOf(sources), Set.of(), frame + 3);
                IrisViewPipelines.retainPreparations(cache, anchors, admitted,
                    anchor -> graph.views.retainsPreparation(anchor, graph.home, graph.eye, 128.0D, 112.0D));
                for (int index = 0; index < sources.size(); index++) {
                    assertSame(histories.get(index), cache.enter(sources.get(index), frame + 3));
                    assertEquals(100 + index, cache.peek(sources.get(index)).get());
                    cache.exit(sources.get(index));
                }
                for (Map.Entry<IrisViewPipelinePath, AtomicInteger> reservation : reservations.entrySet()) {
                    assertSame(reservation.getValue(), cache.peek(reservation.getKey()));
                }
                assertEquals(11, created.get());
                assertEquals(0L, cache.statistics().adoptions());
                assertEquals(0L, cache.statistics().evictions());
                assertEquals(0L, cache.statistics().expirations());
                assertEquals(11, cache.size());
            }
            cache.close();
            cache.close();
            assertEquals(11, released.size());
            assertEquals(11, new HashSet<>(released).size());
        }
    }

    @Test
    public void recordedNinePathPlanAdoptsThreeDepartedSideFuturesForActualAndSpeculativeChildren() {
        String nether = "minecraft:the_nether";
        IrisViewPipelinePath.Camera approach = new IrisViewPipelinePath.Camera("ARM", "nether-approach", OVERWORLD, nether, Similarity.IDENTITY);
        IrisViewPipelinePath.Camera side = new IrisViewPipelinePath.Camera("ARM", "side-approach", OVERWORLD, OVERWORLD, Similarity.IDENTITY);
        IrisViewPipelinePath.Camera reverse = new IrisViewPipelinePath.Camera("ARM", "nether-return", nether, OVERWORLD, Similarity.IDENTITY);
        IrisViewPipelinePath.Camera crossing = new IrisViewPipelinePath.Camera("CROSSING", "entered-nether-approach", nether, OVERWORLD, Similarity.IDENTITY);
        IrisViewPipelinePath floor = new IrisViewPipelinePath(List.of(reverse,
            new IrisViewPipelinePath.Camera("ARM", "return-child-floor", OVERWORLD, OVERWORLD, Similarity.IDENTITY)), OVERWORLD);
        IrisViewPipelinePath ceiling = new IrisViewPipelinePath(List.of(reverse,
            new IrisViewPipelinePath.Camera("ARM", "return-child-ceiling", OVERWORLD, OVERWORLD, Similarity.IDENTITY)), OVERWORLD);
        IrisViewPipelinePath futureFloor = new IrisViewPipelinePath(List.of(crossing,
            new IrisViewPipelinePath.Camera("ARM", "source-floor", OVERWORLD, OVERWORLD, Similarity.IDENTITY)), OVERWORLD);
        List<IrisViewPipelinePath> initial = List.of(
            new IrisViewPipelinePath(List.of(approach), nether),
            new IrisViewPipelinePath(List.of(camera("source-mirror")), OVERWORLD),
            new IrisViewPipelinePath(List.of(side), OVERWORLD),
            new IrisViewPipelinePath(List.of(new IrisViewPipelinePath.Camera("CROSSING", "forward", OVERWORLD, nether, Similarity.IDENTITY)), nether),
            new IrisViewPipelinePath(List.of(crossing), OVERWORLD),
            new IrisViewPipelinePath(List.of(new IrisViewPipelinePath.Camera("RETURN", "return", nether, OVERWORLD, Similarity.IDENTITY)), OVERWORLD),
            new IrisViewPipelinePath(List.of(new IrisViewPipelinePath.Camera("CROSSING", "side-forward", OVERWORLD, OVERWORLD, Similarity.IDENTITY)), OVERWORLD),
            new IrisViewPipelinePath(List.of(new IrisViewPipelinePath.Camera("CROSSING", "side-reverse", OVERWORLD, OVERWORLD, Similarity.IDENTITY)), OVERWORLD),
            new IrisViewPipelinePath(List.of(new IrisViewPipelinePath.Camera("RETURN", "side-return", OVERWORLD, OVERWORLD, Similarity.IDENTITY)), OVERWORLD));
        AtomicInteger created = new AtomicInteger();
        List<AtomicInteger> released = new ArrayList<>();
        IrisViewPipelineCache<IrisViewPipelinePath, AtomicInteger> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(12, path -> new AtomicInteger(created.incrementAndGet()), released::add, System::nanoTime));
        Set<IrisViewPipelinePath> protectedPaths = new LinkedHashSet<>(initial);
        IrisViewPipelineCache.Adoption<IrisViewPipelinePath> adoption = new IrisViewPipelineCache.Adoption<>(protectedPaths::contains,
            (source, target) -> source.dimension().equals(target.dimension()));
        for (IrisViewPipelinePath path : initial) {
            assertTrue(cache.prepare(path, 1, protectedPaths, IrisViewPipelineCache.Preparation.SPECULATIVE));
        }
        for (IrisViewPipelinePath path : initial.subList(0, 3)) {
            cache.enter(path, 2);
            cache.exit(path);
        }
        assertFalse(cache.adopt(floor, 3, adoption));
        List<AtomicInteger> sidePreparations = new ArrayList<>(3);
        List<Long> sideIdentities = new ArrayList<>(3);
        for (IrisViewPipelinePath path : initial.subList(6, 9)) {
            sidePreparations.add(cache.peek(path));
        }
        for (IrisViewPipelineCache.Snapshot<IrisViewPipelinePath> snapshot : cache.snapshots()) {
            if (initial.subList(6, 9).contains(snapshot.key())) {
                sideIdentities.add(snapshot.identity());
            }
        }
        protectedPaths.removeAll(initial.subList(6, 9));
        Set<IrisViewPipelinePath> actual = Set.of(floor, ceiling);
        protectedPaths.addAll(actual);
        cache.plan(actual, Map.of(), Set.of());
        Set<IrisViewPipelinePath> pending = new LinkedHashSet<>(List.of(floor, ceiling));
        assertTrue(cache.adoptNext(pending, 3, actual, adoption));
        assertTrue(cache.adoptNext(pending, 3, actual, adoption));
        assertTrue(pending.isEmpty());
        assertSame(sidePreparations.get(0), cache.enter(floor, 3));
        assertSame(sidePreparations.get(1), cache.enter(ceiling, 3));
        assertNotSame(cache.peek(floor), cache.peek(ceiling));
        cache.peek(floor).set(101);
        cache.peek(ceiling).set(202);
        cache.exit(floor);
        cache.exit(ceiling);
        protectedPaths.add(futureFloor);
        pending.add(futureFloor);
        assertTrue(cache.adoptNext(pending, 4, actual, adoption));
        assertTrue(pending.isEmpty());
        assertSame(sidePreparations.get(2), cache.peek(futureFloor));
        assertFalse(cache.rendered(futureFloor));
        List<IrisViewPipelinePath> adopted = List.of(floor, ceiling, futureFloor);
        for (int index = 0; index < adopted.size(); index++) {
            IrisViewPipelinePath path = adopted.get(index);
            assertFalse(cache.contains(initial.get(6 + index)));
            for (IrisViewPipelineCache.Snapshot<IrisViewPipelinePath> snapshot : cache.snapshots()) {
                if (snapshot.key().equals(path)) {
                    assertEquals(sideIdentities.get(index).longValue(), snapshot.identity());
                }
            }
        }
        for (int frame = 5; frame < 20; frame++) {
            assertSame(sidePreparations.get(0), cache.enter(floor, frame));
            assertSame(sidePreparations.get(1), cache.enter(ceiling, frame));
            assertEquals(101, cache.peek(floor).get());
            assertEquals(202, cache.peek(ceiling).get());
            cache.exit(floor);
            cache.exit(ceiling);
        }
        assertEquals(9, created.get());
        assertEquals(9, cache.size());
        assertEquals(3L, cache.statistics().adoptions());
        assertEquals(0L, cache.statistics().evictions());
        assertTrue(released.isEmpty());
        cache.close();
        assertEquals(9, released.size());
    }

    @Test
    public void adoptionUsesResolvedShaderDimensionAndCannotCrossAClearedPackEpoch() {
        IrisViewPipelinePath source = new IrisViewPipelinePath(List.of(camera("unused")), "example:alternate-programs");
        IrisViewPipelinePath target = new IrisViewPipelinePath(List.of(camera("actual")), OVERWORLD);
        AtomicInteger created = new AtomicInteger();
        List<AtomicInteger> released = new ArrayList<>();
        IrisViewPipelineCache<IrisViewPipelinePath, AtomicInteger> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(12, path -> new AtomicInteger(created.incrementAndGet()), released::add, System::nanoTime));
        IrisViewPipelineCache.Adoption<IrisViewPipelinePath> adoption = new IrisViewPipelineCache.Adoption<>(path -> false,
            (previous, next) -> previous.dimension().equals(next.dimension()));
        cache.prepare(source, 1, Set.of(), IrisViewPipelineCache.Preparation.SPECULATIVE);
        AtomicInteger previousEpoch = cache.peek(source);
        assertFalse(cache.adopt(target, 2, adoption));
        assertSame(previousEpoch, cache.peek(source));
        cache.close();
        assertEquals(List.of(previousEpoch), released);
        assertFalse(cache.adopt(target, 3, adoption));
        IrisViewPipelinePath newSource = new IrisViewPipelinePath(List.of(camera("new-unused")), OVERWORLD);
        cache.prepare(newSource, 3, Set.of(), IrisViewPipelineCache.Preparation.SPECULATIVE);
        assertTrue(cache.adopt(target, 4, adoption));
        assertNotSame(previousEpoch, cache.peek(target));
        assertEquals(2, created.get());
        cache.close();
        assertEquals(2, released.size());
    }

    @Test
    public void cameraContinuationMovesEveryTraversalPrefixAndLeavesOtherHistoriesIndependent() {
        IrisViewPipelinePath.Camera previous = camera("return-a");
        IrisViewPipelinePath.Camera next = camera("arm-b");
        IrisViewPipelinePath.Camera mirror = camera("mirror");
        List<IrisViewPipelinePath> paths = List.of(
            new IrisViewPipelinePath(List.of(previous), OVERWORLD),
            new IrisViewPipelinePath(List.of(previous, mirror), OVERWORLD),
            new IrisViewPipelinePath(List.of(mirror, previous), OVERWORLD),
            new IrisViewPipelinePath(List.of(mirror), OVERWORLD));
        AtomicInteger created = new AtomicInteger();
        IrisViewPipelineCache<IrisViewPipelinePath, AtomicInteger> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(4, key -> new AtomicInteger(created.incrementAndGet()), value -> {}, System::nanoTime));
        List<AtomicInteger> histories = new ArrayList<>();
        for (IrisViewPipelinePath path : paths) {
            histories.add(cache.enter(path, 1));
            cache.exit(path);
        }
        Map<IrisViewPipelinePath.Camera, IrisViewPipelinePath.Camera> replacements = Map.of(previous, next);
        cache.transfer(path -> IrisViewPipelines.continuedPath(path, replacements), path -> false, 2);
        for (int index = 0; index < paths.size(); index++) {
            IrisViewPipelinePath continued = IrisViewPipelines.continuedPath(paths.get(index), replacements);
            assertEquals(OVERWORLD, continued.dimension());
            assertSame(histories.get(index), cache.enter(continued, 2));
            cache.exit(continued);
        }
        assertEquals(4, created.get());
        assertEquals(4, cache.size());
        cache.close();
    }

    @Test
    public void arrivalDropsDepartedPrefixAndRetainsTheActualNestedHistoryWithoutSharing() {
        IrisViewPipelinePath.Camera departed = camera("departed");
        IrisViewPipelinePath.Camera child = camera("nested-mirror");
        IrisViewPipelinePath.Camera root = camera("physical-mirror");
        IrisViewPipelinePath.Camera independent = camera("other-mirror");
        IrisViewPipelinePath previous = new IrisViewPipelinePath(List.of(departed, child), OVERWORLD);
        IrisViewPipelinePath next = new IrisViewPipelinePath(List.of(root), OVERWORLD);
        IrisViewPipelinePath other = new IrisViewPipelinePath(List.of(independent), OVERWORLD);
        AtomicInteger created = new AtomicInteger();
        IrisViewPipelineCache<IrisViewPipelinePath, AtomicInteger> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(3, key -> new AtomicInteger(created.incrementAndGet()), value -> {}, System::nanoTime));
        AtomicInteger history = cache.enter(previous, 1);
        history.set(100);
        cache.exit(previous);
        AtomicInteger separate = cache.enter(other, 1);
        cache.exit(other);
        List<IrisViewPipelines.TraversalTransfer> transfers = List.of(new IrisViewPipelines.TraversalTransfer(previous.cameras(), next.cameras()));
        cache.transfer(path -> IrisViewPipelines.continuedTraversalPath(path, transfers), path -> false, 2);
        assertEquals(next, IrisViewPipelines.continuedTraversalPath(previous, transfers));
        assertSame(history, cache.enter(next, 2));
        assertEquals(100, history.get());
        cache.exit(next);
        assertSame(separate, cache.enter(other, 2));
        cache.exit(other);
        assertNotSame(history, separate);
        assertEquals(2, created.get());
        cache.close();
    }

    @Test
    public void fullCacheRoundTripsKeepTwelveIndependentRootAndRecursiveHistories() {
        Similarity crossing = Similarity.of(OpticTransform.between(Frame.canonical(Face.S), Vec3d.ZERO,
            Frame.canonical(Face.E), new Vec3d(128.0D, 32.0D, -64.0D)), 2.0D);
        IrisViewPipelinePath.Camera returning = new IrisViewPipelinePath.Camera("RETURN", "physical-return",
            "minecraft:the_nether", OVERWORLD, crossing.inverse());
        List<IrisViewPipelinePath> sources = new ArrayList<>(12);
        List<IrisViewPipelinePath> targets = new ArrayList<>(12);
        List<IrisViewPipelines.TraversalTransfer> outbound = new ArrayList<>(12);
        List<IrisViewPipelines.TraversalTransfer> inbound = new ArrayList<>(12);
        for (int index = 0; index < 6; index++) {
            Similarity mirror = Similarity.of(PortalSurface.mirrorTransform(mirrorGeometry(index * 16)), 1.0D);
            IrisViewPipelinePath.Camera root = new IrisViewPipelinePath.Camera("MIRROR", "root-" + index, OVERWORLD, OVERWORLD, mirror);
            IrisViewPipelinePath.Camera child = new IrisViewPipelinePath.Camera("MIRROR", "child-" + index, OVERWORLD, OVERWORLD, mirror);
            IrisViewPipelinePath.Camera other = new IrisViewPipelinePath.Camera("MIRROR", "root-" + ((index + 1) % 6), OVERWORLD, OVERWORLD, mirror);
            IrisViewPipelinePath.Camera grandchild = new IrisViewPipelinePath.Camera("MIRROR", "grandchild-" + index, OVERWORLD, OVERWORLD, mirror);
            sources.add(new IrisViewPipelinePath(List.of(root), OVERWORLD));
            sources.add(new IrisViewPipelinePath(List.of(root, other), OVERWORLD));
            targets.add(new IrisViewPipelinePath(List.of(returning, child), OVERWORLD));
            targets.add(new IrisViewPipelinePath(List.of(returning, child, grandchild), OVERWORLD));
        }
        for (int index = 0; index < sources.size(); index++) {
            outbound.add(new IrisViewPipelines.TraversalTransfer(sources.get(index).cameras(), targets.get(index).cameras()));
            inbound.add(new IrisViewPipelines.TraversalTransfer(targets.get(index).cameras(), sources.get(index).cameras()));
        }
        Vec3d eye = new Vec3d(3.0D, 65.0D, 7.0D);
        assertNotEquals(OpticTransform.IDENTITY.permutation(), crossing.rigid().permutation());
        assertEquals(2.0D, crossing.scale(), 0.0D);
        for (int index = 0; index < sources.size(); index++) {
            assertEquals(mappedPoint(sources.get(index), eye), mappedPoint(targets.get(index), crossing.point(eye)));
            for (Vec3d direction : List.of(new Vec3d(1.0D, 0.0D, 0.0D), new Vec3d(0.0D, 1.0D, 0.0D), new Vec3d(0.0D, 0.0D, 1.0D))) {
                assertEquals(mappedDirection(sources.get(index), direction), mappedDirection(targets.get(index), crossing.direction(direction)));
            }
        }
        AtomicInteger created = new AtomicInteger();
        List<AtomicInteger> released = new ArrayList<>();
        IrisViewPipelineCache<IrisViewPipelinePath, AtomicInteger> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(12, key -> new AtomicInteger(created.incrementAndGet()), released::add, System::nanoTime));
        List<AtomicInteger> histories = new ArrayList<>(12);
        for (IrisViewPipelinePath source : sources) {
            AtomicInteger history = cache.enter(source, 1);
            history.set(100 + histories.size());
            for (AtomicInteger other : histories) {
                assertNotSame(other, history);
            }
            histories.add(history);
            cache.exit(source);
        }
        List<Long> identities = new ArrayList<>(12);
        for (IrisViewPipelineCache.Snapshot<IrisViewPipelinePath> snapshot : cache.snapshots()) {
            identities.add(snapshot.identity());
        }
        for (int trip = 0; trip < 3; trip++) {
            int frame = 2 + trip * 2;
            Map<IrisViewPipelinePath, IrisViewPipelinePath> outward = new HashMap<>();
            for (IrisViewPipelinePath source : sources) {
                outward.put(source, IrisViewPipelines.continuedTraversalPath(source, outbound));
            }
            IrisViewPipelines.continueOwnership(cache, outward, Set.copyOf(targets), Set.of(), frame);
            assertTrue(outward.isEmpty());
            assertHistories(cache, targets, sources, histories, frame);
            Set<IrisViewPipelinePath> speculative = new LinkedHashSet<>();
            speculative.add(new IrisViewPipelinePath(List.of(camera("turn-candidate-" + trip)), OVERWORLD));
            assertFalse(cache.prepareNext(speculative, frame, Set.copyOf(targets), Set.copyOf(targets)));
            Map<IrisViewPipelinePath, IrisViewPipelinePath> inward = new HashMap<>();
            for (IrisViewPipelinePath target : targets) {
                inward.put(target, IrisViewPipelines.continuedTraversalPath(target, inbound));
            }
            IrisViewPipelines.continueOwnership(cache, inward, Set.copyOf(sources), Set.of(), frame + 1);
            assertTrue(inward.isEmpty());
            assertHistories(cache, sources, targets, histories, frame + 1);
            assertEquals(12, cache.size());
            assertEquals(12, created.get());
            assertTrue(released.isEmpty());
            for (int index = 0; index < cache.snapshots().size(); index++) {
                assertEquals(identities.get(index).longValue(), cache.snapshots().get(index).identity());
            }
        }
        cache.close();
        assertEquals(12, released.size());
    }

    @Test
    public void physicalGraphRoundTripsKeepAllTwelveHistoriesThroughLateInvisibleAndArmedTargets() throws Exception {
        for (double scale : List.of(1.0D, 2.0D)) {
            Similarity crossing = Similarity.between(Frame.canonical(Face.S), Vec3d.ZERO,
                Frame.canonical(Face.E), new Vec3d(128.0D, 32.0D, 200.0D), scale);
            try (PortalViewsCrossingGraphTest.CrossingGraph graph = new PortalViewsCrossingGraphTest.CrossingGraph(crossing)) {
                List<IrisViewPipelinePath> sources = new ArrayList<>(12);
                for (List<PortalView> traversal : graph.homePaths()) {
                    List<IrisViewPipelinePath.Camera> cameras = new ArrayList<>(traversal.size());
                    for (PortalView view : traversal) {
                        cameras.add(camera(view));
                    }
                    sources.add(new IrisViewPipelinePath(cameras, OVERWORLD));
                }
                assertEquals(12, sources.size());
                AtomicInteger created = new AtomicInteger();
                List<AtomicInteger> released = new ArrayList<>();
                IrisViewPipelineCache<IrisViewPipelinePath, AtomicInteger> cache = new IrisViewPipelineCache<>(
                    new IrisViewPipelineCache.Options<>(12, key -> new AtomicInteger(created.incrementAndGet()), released::add, System::nanoTime));
                List<AtomicInteger> histories = new ArrayList<>(12);
                for (IrisViewPipelinePath source : sources) {
                    AtomicInteger history = cache.enter(source, 1);
                    history.set(100 + histories.size());
                    for (AtomicInteger other : histories) {
                        assertNotSame(other, history);
                    }
                    histories.add(history);
                    cache.exit(source);
                }
                for (int trip = 0; trip < 3; trip++) {
                    int frame = 2 + trip * 3;
                    graph.crossOut();
                    graph.arrive(false, false);
                    Set<IrisViewPipelinePath> awaiting = new LinkedHashSet<>();
                    for (IrisViewPipelinePath source : sources) {
                        assertEquals(source, IrisViewPipelines.continuedTraversalPath(source, graph.views));
                        assertTrue(IrisViewPipelines.awaitingTraversal(source, graph.views));
                        awaiting.add(source);
                    }
                    Map<IrisViewPipelinePath, IrisViewPipelinePath> continuations = new HashMap<>();
                    IrisViewPipelines.continueOwnership(cache, continuations, Set.of(), awaiting, frame);
                    IrisViewPipelinePath candidate = new IrisViewPipelinePath(List.of(camera("turn-" + trip)), OVERWORLD);
                    assertFalse(cache.prepare(candidate, frame, Set.of(), IrisViewPipelineCache.Preparation.SPECULATIVE));
                    graph.arrive(false, true);
                    List<IrisViewPipelinePath> targets = new ArrayList<>(12);
                    for (IrisViewPipelinePath source : sources) {
                        assertFalse(IrisViewPipelines.awaitingTraversal(source, graph.views));
                        IrisViewPipelinePath target = IrisViewPipelines.continuedTraversalPath(source, graph.views);
                        assertNotEquals(source, target);
                        targets.add(target);
                        continuations.put(source, target);
                    }
                    IrisViewPipelines.continueOwnership(cache, continuations, Set.of(), Set.of(), frame);
                    assertTrue(continuations.isEmpty());
                    assertHistories(cache, targets, sources, histories, frame);
                    if (trip != 2) {
                        graph.arrive(true, true);
                        Map<IrisViewPipelinePath.Camera, IrisViewPipelinePath.Camera> replacements = new HashMap<>();
                        for (PortalViews.CameraContinuation continuation : graph.views.takeCameraContinuations()) {
                            replacements.put(camera(continuation.previous()), camera(continuation.next()));
                        }
                        List<IrisViewPipelinePath> armed = new ArrayList<>(12);
                        for (IrisViewPipelinePath target : targets) {
                            IrisViewPipelinePath next = IrisViewPipelines.continuedPath(target, replacements);
                            armed.add(next);
                            continuations.put(target, next);
                        }
                        IrisViewPipelines.continueOwnership(cache, continuations, Set.copyOf(armed), Set.of(), frame + 1);
                        assertTrue(continuations.isEmpty());
                        assertHistories(cache, armed, targets, histories, frame + 1);
                        targets = armed;
                    }
                    assertFalse(cache.prepare(candidate, frame + 1, Set.copyOf(targets), IrisViewPipelineCache.Preparation.SPECULATIVE));
                    graph.crossBack();
                    for (int index = 0; index < targets.size(); index++) {
                        IrisViewPipelinePath target = targets.get(index);
                        IrisViewPipelinePath next = IrisViewPipelines.continuedTraversalPath(target, graph.views);
                        assertEquals(sources.get(index), next);
                        continuations.put(target, next);
                    }
                    IrisViewPipelines.continueOwnership(cache, continuations, Set.copyOf(sources), Set.of(), frame + 2);
                    assertTrue(continuations.isEmpty());
                    assertHistories(cache, sources, targets, histories, frame + 2);
                    assertEquals(12, cache.size());
                    assertEquals(12, created.get());
                    assertEquals(0L, cache.statistics().evictions());
                    assertEquals(0L, cache.statistics().expirations());
                    assertTrue(released.isEmpty());
                }
                cache.close();
                assertEquals(12, released.size());
            }
        }
    }

    @Test
    public void physicalCrossingResolverRetriesBlockedOwnershipAndStopsAfterRollback() {
        try (PhysicalCrossing fixture = new PhysicalCrossing()) {
            IrisViewPipelinePath source = fixture.source;
            AtomicInteger created = new AtomicInteger();
            List<AtomicInteger> released = new ArrayList<>();
            IrisViewPipelineCache<IrisViewPipelinePath, AtomicInteger> cache = new IrisViewPipelineCache<>(
                new IrisViewPipelineCache.Options<>(2, key -> new AtomicInteger(created.incrementAndGet()), released::add, System::nanoTime));
            AtomicInteger history = cache.enter(source, 1);
            history.set(99);
            fixture.cross();
            fixture.frame(false);
            assertEquals(source, IrisViewPipelines.continuedTraversalPath(source, fixture.views));
            assertTrue(IrisViewPipelines.awaitingTraversal(source, fixture.views));
            cache.exit(source);
            Set<IrisViewPipelinePath> awaiting = Set.of(source);
            IrisViewPipelinePath candidate = new IrisViewPipelinePath(List.of(camera("unrelated-candidate")), OVERWORLD);
            IrisViewPipelinePath required = new IrisViewPipelinePath(List.of(camera("required-camera")), OVERWORLD);
            assertTrue(cache.prepare(candidate, 1, Set.of(), IrisViewPipelineCache.Preparation.SPECULATIVE));
            Map<IrisViewPipelinePath, IrisViewPipelinePath> continuations = new HashMap<>();
            IrisViewPipelines.continueOwnership(cache, continuations, Set.of(required), awaiting, 2);
            assertTrue(cache.prepare(required, 2, Set.of(required), IrisViewPipelineCache.Preparation.ACTUAL));
            assertEquals(1, released.size());
            assertFalse(released.contains(history));
            assertSame(history, cache.enter(source, 2));
            fixture.frame(true);
            IrisViewPipelinePath target = IrisViewPipelines.continuedTraversalPath(source, fixture.views);
            assertFalse(IrisViewPipelines.awaitingTraversal(source, fixture.views));
            assertEquals(2, target.cameras().size());
            assertEquals(source.dimension(), target.dimension());
            continuations.put(source, target);
            IrisViewPipelines.continueOwnership(cache, continuations, Set.of(required), Set.of(), 3);
            assertTrue(cache.contains(source));
            assertFalse(cache.contains(target));
            assertFalse(cache.prepare(target, 3, Set.of(required, target), IrisViewPipelineCache.Preparation.ACTUAL));
            cache.exit(source);
            IrisViewPipelines.continueOwnership(cache, continuations, Set.of(required), Set.of(), 3);
            assertTrue(continuations.isEmpty());
            assertFalse(cache.contains(source));
            assertSame(history, cache.enter(target, 3));
            assertEquals(99, history.get());
            cache.exit(target);
            assertEquals(3, created.get());
            assertEquals(1, released.size());
            fixture.views.resetReturn(fixture.destination);
            fixture.frame(true);
            assertEquals(source, IrisViewPipelines.continuedTraversalPath(source, fixture.views));
            assertEquals(target, IrisViewPipelines.continuedTraversalPath(target, fixture.views));
            assertFalse(IrisViewPipelines.awaitingTraversal(source, fixture.views));
            assertFalse(IrisViewPipelines.awaitingTraversal(target, fixture.views));
            cache.close();
            assertEquals(3, released.size());
            assertTrue(released.contains(history));
        }
    }

    @Test
    public void unchangedStreamedTailMovesOnlyToItsExactAdmittedPath() {
        try (PhysicalCrossing fixture = new PhysicalCrossing()) {
            IrisMeshCamera stream = new IrisMeshCamera(List.of(mirrorGeometry(32)), OVERWORLD, "example:remote", Similarity.IDENTITY);
            IrisViewPipelinePath source = stream.append(fixture.source.cameras(), OVERWORLD);
            fixture.cross();
            fixture.frame(true);
            IrisViewPipelinePath target = IrisViewPipelines.continuedTraversalPath(source, fixture.views);
            assertEquals(3, target.cameras().size());
            assertEquals(source.cameras().getLast(), target.cameras().getLast());
            IrisMeshCamera changed = new IrisMeshCamera(List.of(mirrorGeometry(48), mirrorGeometry(32)), OVERWORLD,
                "example:remote", Similarity.IDENTITY);
            IrisViewPipelinePath differentAncestry = changed.append(target.cameras().subList(0, 2), OVERWORLD);
            AtomicInteger created = new AtomicInteger();
            IrisViewPipelineCache<IrisViewPipelinePath, AtomicInteger> cache = new IrisViewPipelineCache<>(
                new IrisViewPipelineCache.Options<>(2, key -> new AtomicInteger(created.incrementAndGet()), value -> {}, System::nanoTime));
            AtomicInteger history = cache.enter(source, 1);
            cache.exit(source);
            cache.transfer(path -> {
                IrisViewPipelinePath next = IrisViewPipelines.continuedTraversalPath(path, fixture.views);
                return differentAncestry.equals(next) ? next : path;
            }, differentAncestry::equals, 2);
            assertTrue(cache.contains(source));
            assertFalse(cache.contains(differentAncestry));
            cache.transfer(path -> IrisViewPipelines.continuedTraversalPath(path, fixture.views), target::equals, 2);
            assertFalse(cache.contains(source));
            assertSame(history, cache.enter(target, 2));
            cache.exit(target);
            assertEquals(1, created.get());
            cache.close();
        }
    }

    @Test
    public void ambiguousArrivalKeepsExistingTraversalAndNeverClaimsTheMainCamera() {
        IrisViewPipelinePath.Camera departed = camera("departed");
        IrisViewPipelinePath.Camera child = camera("child");
        IrisViewPipelinePath previous = new IrisViewPipelinePath(List.of(departed, child), OVERWORLD);
        List<IrisViewPipelines.TraversalTransfer> transfers = List.of(
            new IrisViewPipelines.TraversalTransfer(previous.cameras(), List.of(camera("a"))),
            new IrisViewPipelines.TraversalTransfer(previous.cameras(), List.of(camera("b"))));
        assertEquals(previous, IrisViewPipelines.continuedTraversalPath(previous, transfers));
        assertThrows(IllegalArgumentException.class, () -> new IrisViewPipelines.TraversalTransfer(previous.cameras(), List.of()));
    }

    @Test
    public void deepestArrivalMappingWinsOverAnAmbiguousShorterPrefix() {
        IrisViewPipelinePath.Camera departed = camera("departed");
        IrisViewPipelinePath.Camera child = camera("child");
        IrisViewPipelinePath.Camera grandchild = camera("grandchild");
        IrisViewPipelinePath.Camera nextChild = camera("next-child");
        IrisViewPipelinePath.Camera nextGrandchild = camera("next-grandchild");
        IrisViewPipelinePath previous = new IrisViewPipelinePath(List.of(departed, child, grandchild), OVERWORLD);
        List<IrisViewPipelines.TraversalTransfer> transfers = List.of(
            new IrisViewPipelines.TraversalTransfer(List.of(departed, child), List.of(camera("a"))),
            new IrisViewPipelines.TraversalTransfer(List.of(departed, child), List.of(camera("b"))),
            new IrisViewPipelines.TraversalTransfer(previous.cameras(), List.of(nextChild, nextGrandchild)));
        assertEquals(new IrisViewPipelinePath(List.of(nextChild, nextGrandchild), OVERWORLD),
            IrisViewPipelines.continuedTraversalPath(previous, transfers));
    }

    @Test
    public void preparedTravelRootsReuseSeparateHistoryForCoincidentIndependentPortals() {
        ClientLevel source = mock(ClientLevel.class);
        ClientLevel destination = mock(ClientLevel.class);
        when(source.dimension()).thenReturn(Level.OVERWORLD);
        when(destination.dimension()).thenReturn(Level.END);
        PortalSurface surface = PortalSurface.of(mirrorGeometry(0));
        List<IrisViewPipelinePath> paths = new ArrayList<>();
        for (String key : List.of("coincident-a", "coincident-b")) {
            PortalView arm = mock(PortalView.class);
            when(arm.kind()).thenReturn(PortalView.Kind.ARM);
            when(arm.key()).thenReturn(key);
            when(arm.historyKey()).thenReturn(key);
            when(arm.source()).thenReturn(source);
            when(arm.destination()).thenReturn(destination);
            when(arm.surface()).thenReturn(surface);
            when(arm.toDestination()).thenReturn(Similarity.IDENTITY);
            when(arm.recursion()).thenReturn(2);
            paths.add(new IrisViewPipelinePath(List.of(IrisViewPipelines.camera(arm)), "minecraft:the_end"));
            for (PortalView future : PortalViews.futureTravelViews(arm)) {
                paths.add(new IrisViewPipelinePath(List.of(IrisViewPipelines.camera(future)),
                    future.destination().dimension().identifier().toString()));
            }
        }
        AtomicInteger created = new AtomicInteger();
        IrisViewPipelineCache<IrisViewPipelinePath, AtomicInteger> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(12, key -> new AtomicInteger(created.incrementAndGet()), value -> {}, System::nanoTime));
        for (IrisViewPipelinePath path : paths) {
            assertTrue(cache.prepare(path, 1, Set.copyOf(paths), IrisViewPipelineCache.Preparation.ACTUAL));
        }
        List<AtomicInteger> histories = new ArrayList<>();
        for (IrisViewPipelinePath path : paths) {
            AtomicInteger history = cache.enter(path, 2);
            for (AtomicInteger other : histories) {
                assertNotSame(other, history);
            }
            history.set(100 + histories.size());
            histories.add(history);
            cache.exit(path);
        }
        for (int index = 0; index < paths.size(); index++) {
            assertSame(histories.get(index), cache.enter(paths.get(index), 3));
            assertEquals(100 + index, histories.get(index).get());
            cache.exit(paths.get(index));
        }
        assertEquals(8, created.get());
        cache.close();
    }

    @Test
    public void reconstructedCameraPathReusesItsHistory() {
        AtomicInteger created = new AtomicInteger();
        List<AtomicInteger> released = new ArrayList<>();
        IrisViewPipelineCache<IrisViewPipelinePath, AtomicInteger> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(12, key -> new AtomicInteger(created.incrementAndGet()), released::add, System::nanoTime));
        IrisViewPipelinePath original = new IrisViewPipelinePath(List.of(camera("a")), OVERWORLD);
        AtomicInteger history = cache.enter(original, 1);
        history.set(99);
        cache.exit(original);
        IrisViewPipelinePath recreated = new IrisViewPipelinePath(List.of(camera(new String("a"))), OVERWORLD);
        assertSame(history, cache.enter(recreated, 2));
        assertEquals(99, history.get());
        cache.exit(recreated);
        assertEquals(1, created.get());
        cache.close();
        assertEquals(List.of(history), released);
    }

    @Test
    public void reopenedMirrorWithNewStreamIdsRetainsHistoryAndKeepsOtherCamerasSeparate() {
        ClientLevel home = mock(ClientLevel.class);
        when(home.dimension()).thenReturn(Level.OVERWORLD);
        PortalViews views = new PortalViews(mock(ResidentLevels.class));
        AtomicInteger created = new AtomicInteger();
        List<AtomicInteger> released = new ArrayList<>();
        IrisViewPipelineCache<IrisViewPipelinePath, AtomicInteger> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(12, key -> new AtomicInteger(created.incrementAndGet()), released::add, System::nanoTime));
        ClientViewSession session = mock(ClientViewSession.class);
        WormholesClient client = mock(WormholesClient.class);
        when(client.session()).thenReturn(session);
        for (int key : List.of(1, 9, 10)) {
            when(session.matchesSource(key, home)).thenReturn(true);
        }
        try (MockedStatic<PortalBackends> backends = mockStatic(PortalBackends.class);
             MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class)) {
            clients.when(WormholesClient::instance).thenReturn(client);
            backends.when(PortalBackends::pipeline).thenReturn(PipelineBackend.NONE);
            try {
                ApertureDescriptor geometry = mirrorGeometry(0);
                views.frame(home, Vec3d.ZERO, List.of(), true, true, List.of(new ClientPortal(1, geometry, 1, 0.0D)), null);
                IrisViewPipelinePath original = new IrisViewPipelinePath(List.of(camera(views.current().getFirst())), OVERWORLD);
                AtomicInteger history = cache.enter(original, 1);
                history.set(99);
                cache.exit(original);
                views.frame(home, Vec3d.ZERO, List.of(), false, true, List.of(), null);
                views.frame(home, Vec3d.ZERO, List.of(), true, true,
                    List.of(new ClientPortal(9, geometry.withDepth(48), 7, 0.0D)), null);
                IrisViewPipelinePath reopened = new IrisViewPipelinePath(List.of(camera(views.current().getFirst())), OVERWORLD);
                assertEquals(original, reopened);
                assertSame(history, cache.enter(reopened, 2));
                assertEquals(99, history.get());
                cache.exit(reopened);
                assertEquals(1, created.get());
                views.frame(home, Vec3d.ZERO, List.of(), true, true,
                    List.of(new ClientPortal(10, mirrorGeometry(16), 1, 0.0D)), null);
                IrisViewPipelinePath sibling = new IrisViewPipelinePath(List.of(camera(views.current().getFirst())), OVERWORLD);
                assertNotEquals(reopened, sibling);
                assertNotSame(history, cache.enter(sibling, 3));
                cache.exit(sibling);
                assertEquals(2, created.get());
            } finally {
                views.clear();
                cache.close();
            }
        }
        assertEquals(2, released.size());
    }

    @Test
    public void siblingAndOrderedNestedTraversalsOwnDifferentHistory() {
        IrisViewPipelinePath.Camera a = camera("a");
        IrisViewPipelinePath.Camera b = camera("b");
        List<IrisViewPipelinePath> paths = List.of(
            new IrisViewPipelinePath(List.of(a), OVERWORLD),
            new IrisViewPipelinePath(List.of(b), OVERWORLD),
            new IrisViewPipelinePath(List.of(a, b), OVERWORLD),
            new IrisViewPipelinePath(List.of(b, a), OVERWORLD),
            new IrisViewPipelinePath(List.of(a, a), OVERWORLD));
        List<AtomicInteger> released = new ArrayList<>();
        IrisViewPipelineCache<IrisViewPipelinePath, AtomicInteger> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(12, key -> new AtomicInteger(), released::add, System::nanoTime));
        List<AtomicInteger> histories = new ArrayList<>();
        for (IrisViewPipelinePath path : paths) {
            AtomicInteger history = cache.enter(path, 1);
            for (AtomicInteger previous : histories) {
                assertNotSame(previous, history);
            }
            histories.add(history);
            cache.exit(path);
        }
        assertEquals(5, cache.size());
        cache.close();
    }

    @Test
    public void changedKindWorldTransformOrShaderDimensionChangesCameraIdentity() {
        IrisViewPipelinePath.Camera original = camera("a");
        assertNotEquals(original, new IrisViewPipelinePath.Camera("RETURN", "a", OVERWORLD, OVERWORLD, Similarity.IDENTITY));
        assertNotEquals(original, new IrisViewPipelinePath.Camera("MIRROR", "a", "custom:source", OVERWORLD, Similarity.IDENTITY));
        assertNotEquals(original, new IrisViewPipelinePath.Camera("MIRROR", "a", OVERWORLD, "custom:destination", Similarity.IDENTITY));
        assertNotEquals(original, new IrisViewPipelinePath.Camera("MIRROR", "a", OVERWORLD, OVERWORLD,
            Similarity.of(OpticTransform.IDENTITY, 2.0D)));
        assertNotEquals(new IrisViewPipelinePath(List.of(original), OVERWORLD),
            new IrisViewPipelinePath(List.of(original), "minecraft:the_nether"));
    }

    @Test
    public void traversalIsImmutableAndCannotRepresentTheMainCamera() {
        List<IrisViewPipelinePath.Camera> cameras = new ArrayList<>(List.of(camera("a")));
        IrisViewPipelinePath path = new IrisViewPipelinePath(cameras, OVERWORLD);
        cameras.clear();
        assertEquals(1, path.cameras().size());
        assertThrows(UnsupportedOperationException.class, () -> path.cameras().clear());
        assertThrows(IllegalArgumentException.class, () -> new IrisViewPipelinePath(List.of(), OVERWORLD));
    }

    private static IrisViewPipelinePath.Camera camera(String key) {
        return new IrisViewPipelinePath.Camera("MIRROR", key, OVERWORLD, OVERWORLD,
            Similarity.of(OpticTransform.IDENTITY, 1.0D));
    }

    private static Vec3d mappedPoint(IrisViewPipelinePath path, Vec3d point) {
        for (IrisViewPipelinePath.Camera camera : path.cameras()) {
            point = camera.transform().point(point);
        }
        return point;
    }

    private static Vec3d mappedDirection(IrisViewPipelinePath path, Vec3d direction) {
        for (IrisViewPipelinePath.Camera camera : path.cameras()) {
            direction = camera.transform().direction(direction);
        }
        return direction;
    }

    private static void assertHistories(IrisViewPipelineCache<IrisViewPipelinePath, AtomicInteger> cache,
                                        List<IrisViewPipelinePath> owners, List<IrisViewPipelinePath> retired,
                                        List<AtomicInteger> histories, int frame) {
        for (int index = 0; index < owners.size(); index++) {
            assertFalse(cache.contains(retired.get(index)));
            assertSame(histories.get(index), cache.enter(owners.get(index), frame));
            assertEquals(100 + index, histories.get(index).get());
            cache.exit(owners.get(index));
        }
    }

    private static IrisViewPipelinePath.Camera camera(PortalView view) {
        return IrisViewPipelines.camera(view);
    }

    private static ApertureDescriptor mirrorGeometry(int x) {
        return new ApertureDescriptor(x, 64, 0, Face.S.ordinal(), true, 0, true, 2, 3, new long[]{63L}, ShapeDescriptor.FULL,
            0.0F, 0.0F, 0.0F, 16, 3, 0, 0, 0, 0, 0, 0, 0.0D, 0, 0L, List.of());
    }

    private static final class PhysicalCrossing implements AutoCloseable {
        private final MockedStatic<PortalBackends> backends = mockStatic(PortalBackends.class);
        private final MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class);
        private final MockedStatic<ClientWorldLoader> worlds = mockStatic(ClientWorldLoader.class);
        private final ClientLevel home = mock(ClientLevel.class);
        private final ClientLevel destination = mock(ClientLevel.class);
        private final ResidentLevels residents = mock(ResidentLevels.class);
        private final PortalViews views = new PortalViews(residents);
        private final Similarity toward = Similarity.of(OpticTransform.of(OpticTransform.IDENTITY.permutation(), 0.0D, 0.0D, 200.0D), 1.0D);
        private final ClientPortal mirror = new ClientPortal(10, mirrorGeometry(16), 1, 0.0D);
        private final TravelMessage.TravelBegin forward = mock(TravelMessage.TravelBegin.class);
        private final TravelMessage.TravelBegin reverse = mock(TravelMessage.TravelBegin.class);
        private final ClientPortal reverseRoot;
        private final ClientPortal child = new ClientPortal(20, mirror.geometry().withParent(9), 1, 0.0D);
        private final IrisViewPipelinePath source;

        private PhysicalCrossing() {
            when(home.dimension()).thenReturn(Level.OVERWORLD);
            when(destination.dimension()).thenReturn(Level.END);
            when(residents.level(1)).thenReturn(destination);
            when(residents.level(2)).thenReturn(home);
            LevelRenderer renderer = mock(LevelRenderer.class);
            worlds.when(() -> ClientWorldLoader.residentRenderer(home)).thenReturn(renderer);
            worlds.when(() -> ClientWorldLoader.residentRenderer(destination)).thenReturn(renderer);
            WormholesClient client = mock(WormholesClient.class);
            ClientViewSession session = mock(ClientViewSession.class);
            clients.when(WormholesClient::instance).thenReturn(client);
            when(client.session()).thenReturn(session);
            when(client.portalViews()).thenReturn(views);
            when(session.matchesSource(10, home)).thenReturn(true);
            when(session.matchesSource(9, destination)).thenReturn(true);
            backends.when(PortalBackends::pipeline).thenReturn(PipelineBackend.NONE);
            ApertureDescriptor sourceGeometry = new ApertureDescriptor(0, 64, 0, Face.S.ordinal(), true, 0, false, 2, 3,
                new long[]{63L}, ShapeDescriptor.FULL, 0.0F, 0.0F, 0.0F, 16, 3, 0, 0, 0, 0, 0, 0, 0.0D, 0, 0L, List.of());
            ApertureDescriptor reverseGeometry = new ApertureDescriptor(0, 64, 200, Face.S.ordinal(), false, 0, false, 2, 3,
                new long[]{63L}, ShapeDescriptor.FULL, 0.0F, 0.0F, 0.0F, 16, 3, 0, 0, 0, 0, 0, 0, 0.0D, 0, 0L, List.of());
            when(forward.token()).thenReturn(UUID.randomUUID());
            when(forward.sourcePortal()).thenReturn(UUID.randomUUID());
            when(forward.sourceWorld()).thenReturn(OVERWORLD);
            when(forward.sourceGeometry()).thenReturn(sourceGeometry);
            when(forward.sourceToDestination()).thenReturn(toward);
            when(forward.resident()).thenReturn(true);
            when(forward.levelHandle()).thenReturn(1);
            when(reverse.token()).thenReturn(UUID.randomUUID());
            when(reverse.sourcePortal()).thenReturn(UUID.randomUUID());
            when(reverse.sourceWorld()).thenReturn("minecraft:the_end");
            when(reverse.sourceGeometry()).thenReturn(reverseGeometry);
            when(reverse.sourceToDestination()).thenReturn(toward.inverse());
            when(reverse.resident()).thenReturn(true);
            when(reverse.levelHandle()).thenReturn(2);
            reverseRoot = new ClientPortal(9, reverseGeometry, 1, 0.0D);
            EnvironmentState environment = mock(EnvironmentState.class);
            EnvironmentState.World world = mock(EnvironmentState.World.class);
            when(world.dimensionKey()).thenReturn(OVERWORLD);
            when(environment.world()).thenReturn(world);
            when(environment.transform()).thenReturn(PortalSurface.mirrorTransform(mirror.geometry()));
            when(environment.scale()).thenReturn(1.0F);
            when(session.environment(20)).thenReturn(environment);
            views.frame(home, new Vec3d(1.0D, 65.0D, 4.0D), List.of(forward), true, true, List.of(mirror), null);
            source = new IrisViewPipelinePath(List.of(camera(views.current().get(1))), OVERWORLD);
        }

        private void cross() {
            views.crossed(forward, home, destination, true);
        }

        private void frame(boolean children) {
            views.frame(destination, new Vec3d(1.0D, 65.0D, 204.0D), List.of(reverse), true, true,
                children ? List.of(reverseRoot, child) : List.of(reverseRoot), null);
        }

        @Override
        public void close() {
            views.clear();
            worlds.close();
            clients.close();
            backends.close();
        }
    }
}
