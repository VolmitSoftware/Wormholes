package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.optics.stream.EnvironmentState;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.ClientPalette;
import art.arcane.wormholes.modded.client.ClientPortal;
import art.arcane.wormholes.modded.client.ClientViewSession;
import art.arcane.wormholes.modded.client.ResidentLevels;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.WormholesClientConfig;
import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import art.arcane.wormholes.network.client.ClientViewExtensions;
import art.arcane.wormholes.network.client.LocalDestination;
import art.arcane.wormholes.network.client.LocalDestinationExtension;
import art.arcane.wormholes.network.client.RootSource;
import art.arcane.wormholes.network.client.RootSourceExtension;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.Level;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

public class PortalViewsCrossingGraphTest extends MinecraftTestBase {
    @Test
    public void twelvePhysicalPathsExpandAndCollapseForThreeTripsBeforeReverseArming() throws Exception {
        for (Similarity transform : List.of(translation(), rotated(), scaled())) {
            try (CrossingGraph graph = new CrossingGraph(transform)) {
                for (int trip = 0; trip < 3; trip++) {
                    List<List<PortalView>> original = graph.homePaths();
                    assertEquals(12, original.size());
                    graph.crossOut();
                    graph.arrive(false, false);
                    for (List<PortalView> path : original) {
                        assertEquals(path, graph.views.crossingTraversal(keys(path)));
                        assertNull(graph.views.continuedTraversal(keys(path)));
                    }
                    graph.arrive(false, true);
                    List<List<PortalView>> expanded = expand(graph, original);
                    for (List<PortalView> path : expanded) {
                        assertEquals(PortalView.Kind.RETURN, path.getFirst().kind());
                        assertSame(graph.destination, path.getFirst().source());
                        assertSame(graph.home, path.getFirst().destination());
                    }
                    if (trip != 2) {
                        graph.arrive(true, true);
                        List<List<PortalView>> armed = expand(graph, original);
                        for (int index = 0; index < armed.size(); index++) {
                            assertEquals(PortalView.Kind.ARM, armed.get(index).getFirst().kind());
                            assertSameCameraPath(expanded.get(index), armed.get(index));
                        }
                        expanded = armed;
                    }
                    graph.crossBack();
                    Set<List<Object>> restored = new HashSet<>();
                    for (int index = 0; index < expanded.size(); index++) {
                        PortalViews.TraversalContinuation continuation = graph.views.continuedTraversal(keys(expanded.get(index)));
                        assertEquals(expanded.get(index), continuation.previous());
                        assertEquals(keys(original.get(index)), keys(continuation.next()));
                        assertSameCameraPath(original.get(index), continuation.next());
                        assertTrue(restored.add(keys(continuation.next())));
                    }
                    assertEquals(12, restored.size());
                }
            }
        }
    }

    @Test
    public void placedWitnessRetainsExactCopiesWhenEveryResidentHasTheSameDimension() throws Exception {
        try (CrossingGraph graph = new CrossingGraph(rotated(), true)) {
            ClientLevel unrelated = mock(ClientLevel.class);
            when(unrelated.dimension()).thenReturn(Level.OVERWORLD);
            when(graph.residents.level(3)).thenReturn(unrelated);
            TravelMessage.TravelBegin other = graph.arm(geometry(800, Face.S, false, 0), "minecraft:overworld", 3, Similarity.IDENTITY);
            graph.crossOut();
            graph.views.frame(graph.destination, graph.arrivalEye(), List.of(other), true, true, graph.arrivalPortals(), null);
            PortalView returning = graph.views.current().getLast();
            assertEquals(PortalView.Kind.RETURN, returning.kind());
            assertEquals(2, graph.views.streamKey(returning));
            assertSame(graph.destination, returning.source());
            assertSame(graph.home, returning.destination());
            assertEquals(6, graph.views.children(returning).size());
            for (PortalView child : graph.views.children(returning)) {
                assertSame(graph.home, child.source());
                assertSame(graph.home, child.destination());
            }
        }
    }

    @Test
    public void predictionCrossingBindsItsPlacedRootAndKeepsTheWitnessDirection() throws Exception {
        try (CrossingGraph graph = new CrossingGraph(scaled())) {
            PortalView approached = graph.views.current().getFirst();
            graph.views.frame(graph.home, graph.eye, List.of(graph.forward), true, true, graph.arrivalPortals(),
                new PortalViews.Crossing(graph.forward.token(), graph.destination, approached.surface(), graph.toward));
            PortalView crossing = graph.views.current().getLast();
            assertEquals(PortalView.Kind.CROSSING, crossing.kind());
            assertEquals(2, graph.views.streamKey(crossing));
            assertSame(graph.destination, crossing.source());
            assertSame(graph.home, crossing.destination());
            assertEquals(6, graph.views.children(crossing).size());
        }
    }

    @Test
    public void rootRevisionWaitsForNewProofAndResetOrExpiryInvalidatesPendingPaths() throws Exception {
        for (boolean expire : List.of(false, true)) {
            try (CrossingGraph graph = new CrossingGraph(translation())) {
                List<PortalView> previous = graph.homePaths().getFirst();
                graph.crossOut();
                graph.reverseRoot.geometry(graph.reverseRoot.geometry().withDepth(48), 2);
                graph.arrive(false, true);
                assertNull(graph.views.continuedTraversal(keys(previous)));
                assertEquals(previous, graph.views.crossingTraversal(keys(previous)));
                graph.announceReverse();
                graph.arrive(false, true);
                assertEquals(2, graph.views.continuedTraversal(keys(previous)).next().size());
                long epoch = graph.views.continuityEpoch();
                if (expire) {
                    graph.views.frame(graph.destination, new Vec3d(10_000.0D, 10_000.0D, 10_000.0D), List.of(), true, true,
                        graph.arrivalPortals(), null);
                } else {
                    graph.views.resetReturn(graph.destination);
                }
                assertTrue(graph.views.continuityEpoch() > epoch);
                assertFalse(graph.views.hasTraversalHistory());
                assertTrue(graph.views.crossingTraversal(keys(previous)).isEmpty());
                assertNull(graph.views.continuedTraversal(keys(previous)));
            }
        }
    }

    @Test
    public void everyAncestorCeilingStopsAPlacedReturnBranch() throws Exception {
        try (CrossingGraph graph = new CrossingGraph(translation())) {
            graph.views.crossed(graph.forward, graph.home, graph.destination, false);
            graph.views.frame(graph.destination, graph.arrivalEye(), List.of(), true, true, graph.arrivalPortals(), null);
            PortalView returning = graph.views.current().getFirst();
            assertEquals(1, returning.recursion());
            assertTrue(graph.views.children(returning).isEmpty());
        }
    }

    @Test
    public void floorAndCeilingCrossingsBindTheirPhysicalPlacedOpening() throws Exception {
        for (Face face : List.of(Face.U, Face.D)) {
            for (Similarity transform : List.of(rotated(), scaled())) {
                try (CrossingGraph graph = new CrossingGraph(new GraphOptions(transform, face, false))) {
                    List<List<PortalView>> previous = graph.homePaths();
                    graph.crossOut();
                    graph.arrive(false, true);
                    List<List<PortalView>> next = expand(graph, previous);
                    assertEquals(2, graph.views.streamKey(next.getFirst().getFirst()));
                    graph.crossBack();
                    for (int index = 0; index < previous.size(); index++) {
                        assertEquals(keys(previous.get(index)), keys(graph.views.continuedTraversal(keys(next.get(index))).next()));
                    }
                }
            }
        }
    }

    public record GraphOptions(Similarity transform, Face face, boolean sameDimension) {
    }

    private static List<List<PortalView>> expand(CrossingGraph graph, List<List<PortalView>> previous) {
        List<List<PortalView>> next = new ArrayList<>(12);
        Set<List<Object>> identities = new HashSet<>();
        for (List<PortalView> path : previous) {
            PortalViews.TraversalContinuation continuation = graph.views.continuedTraversal(keys(path));
            assertEquals(path, continuation.previous());
            assertEquals(path.size() + 1, continuation.next().size());
            assertSameCameraPath(path, continuation.next().subList(1, continuation.next().size()));
            assertTrue(identities.add(keys(continuation.next())));
            next.add(continuation.next());
        }
        assertEquals(12, identities.size());
        return List.copyOf(next);
    }

    private static void assertSameCameraPath(List<PortalView> expected, List<PortalView> actual) {
        assertEquals(expected.size(), actual.size());
        for (int index = 0; index < expected.size(); index++) {
            assertTrue(PortalViews.sameCamera(expected.get(index), actual.get(index)));
        }
    }

    public static List<Object> keys(List<PortalView> path) {
        List<Object> keys = new ArrayList<>(path.size());
        for (PortalView view : path) {
            keys.add(view.key());
        }
        return List.copyOf(keys);
    }

    private static Similarity translation() {
        return Similarity.between(Frame.canonical(Face.S), Vec3d.ZERO, Frame.canonical(Face.S), new Vec3d(0.0D, 0.0D, 200.0D), 1.0D);
    }

    private static Similarity rotated() {
        return Similarity.between(Frame.canonical(Face.S), Vec3d.ZERO, Frame.canonical(Face.E), new Vec3d(128.0D, 32.0D, 200.0D), 1.0D);
    }

    private static Similarity scaled() {
        return Similarity.between(Frame.canonical(Face.S), Vec3d.ZERO, Frame.canonical(Face.E), new Vec3d(128.0D, 32.0D, 200.0D), 2.0D);
    }

    private static ApertureDescriptor geometry(int x, Face face, boolean mirror, int quarterTurns) {
        return new ApertureDescriptor(x, 64, 0, face.ordinal(), true, quarterTurns, mirror, 2, 3, new long[]{63L}, ShapeDescriptor.FULL,
            0.0F, 0.0F, 0.0F, 16, 3, 0, 0, 0, 0, 0, 0, 0.0D, 0, x + 1L, List.of());
    }

    private static ApertureDescriptor placedGeometry(PortalSurface surface) {
        Vec3d first = surface.placement().point(surface.aperture().point(0.0D, 0.0D));
        Vec3d last = surface.placement().point(surface.aperture().point(surface.geometry().apertureWidth(), surface.geometry().apertureHeight()));
        Face face = Face.closest(surface.servedNormal());
        Frame canonical = Frame.canonical(face);
        double[] minimum = {Math.min(first.x(), last.x()), Math.min(first.y(), last.y()), Math.min(first.z(), last.z())};
        double[] maximum = {Math.max(first.x(), last.x()), Math.max(first.y(), last.y()), Math.max(first.z(), last.z())};
        int width = (int) Math.round(maximum[canonical.getRight().axisIndex()] - minimum[canonical.getRight().axisIndex()]);
        int height = (int) Math.round(maximum[canonical.getUp().axisIndex()] - minimum[canonical.getUp().axisIndex()]);
        int[] origin = {(int) Math.floor(minimum[0]), (int) Math.floor(minimum[1]), (int) Math.floor(minimum[2])};
        double offset = (minimum[face.axisIndex()] - origin[face.axisIndex()] - 0.5D) * (face.x() + face.y() + face.z());
        return new ApertureDescriptor(origin[0], origin[1], origin[2], face.ordinal(), true, 0, false, width, height,
            new long[]{(1L << (width * height)) - 1L}, ShapeDescriptor.FULL, 0.0F, 0.0F, 0.0F, 16, 3, 0, 0, 0, 0, 0, 0,
            offset, 0, 50L, List.of());
    }

    public static final class CrossingGraph implements AutoCloseable {
        public final ClientLevel home = mock(ClientLevel.class);
        public final ClientLevel destination = mock(ClientLevel.class);
        public final ResidentLevels residents = mock(ResidentLevels.class);
        public final PortalViews views = new PortalViews(residents);
        public final Similarity toward;
        public final Vec3d eye;
        public final TravelMessage.TravelBegin forward;
        public final TravelMessage.TravelBegin reverse;
        private final MockedStatic<PortalBackends> backends = mockStatic(PortalBackends.class);
        private final MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class);
        private final MockedStatic<ClientWorldLoader> worlds = mockStatic(ClientWorldLoader.class);
        private final ClientViewSession session;
        private final ClientViewSession.Sink sink = mock(ClientViewSession.Sink.class);
        private final ClientPortal reverseRoot;
        private final List<ClientPortal> homePortals = new ArrayList<>(13);
        private final List<ClientPortal> reversePortals = new ArrayList<>(13);

        public CrossingGraph(Similarity toward) throws Exception {
            this(new GraphOptions(toward, Face.S, false));
        }

        public CrossingGraph(Similarity toward, boolean sameDimension) throws Exception {
            this(new GraphOptions(toward, Face.S, sameDimension));
        }

        public CrossingGraph(GraphOptions options) throws Exception {
            try {
                toward = options.transform();
                when(home.dimension()).thenReturn(Level.OVERWORLD);
                when(destination.dimension()).thenReturn(options.sameDimension() ? Level.OVERWORLD : Level.NETHER);
                when(residents.level(1)).thenReturn(destination);
                when(residents.level(2)).thenReturn(home);
                LevelRenderer renderer = mock(LevelRenderer.class);
                worlds.when(() -> ClientWorldLoader.residentRenderer(home)).thenReturn(renderer);
                worlds.when(() -> ClientWorldLoader.residentRenderer(destination)).thenReturn(renderer);
                backends.when(PortalBackends::pipeline).thenReturn(PipelineBackend.NONE);
                WormholesClientConfig config = new WormholesClientConfig();
                config.normalize();
                session = new ClientViewSession(config, new ClientPalette(BuiltInRegistries.BLOCK), 1, "test");
                session.accept(new ViewStreamMessage.Accept(1, ViewStreamCapability.CLIENT_MIRROR.mask()
                    | ClientViewExtensions.LOCAL_DESTINATION | ClientViewExtensions.ROOT_SOURCE, 20,
                    ViewStreamLimits.DEFAULT_MAX_FRAME_BYTES, 7L, 8));
                WormholesClient client = mock(WormholesClient.class);
                clients.when(WormholesClient::instance).thenReturn(client);
                when(client.session()).thenReturn(session);
                ApertureDescriptor entered = geometry(0, options.face(), false, 0);
                forward = arm(entered, "minecraft:overworld", 1, toward);
                PortalSurface returned = PortalSurface.of(entered).through(toward);
                eye = PortalSurface.of(entered).planePoint().add(PortalSurface.of(entered).servedNormal().multiply(2.0D));
                reverseRoot = new ClientPortal(2, placedGeometry(returned), 1, 0.0D);
                reverse = arm(reverseRoot.geometry(), destination.dimension().identifier().toString(), 2, toward.inverse());
                reversePortals.add(reverseRoot);
                announceReverse();
                for (int index = 0; index < 6; index++) {
                    Face face = List.of(Face.S, Face.E, Face.U, Face.D, Face.S, Face.E).get(index);
                    ClientPortal root = new ClientPortal(100 + index, geometry(16 + index * 16, face, true, index % 4), 1, 0.0D);
                    ClientPortal child = new ClientPortal(200 + index, geometry(400 + index * 16, face, true, 0).withParent(root.portalKey()), 1, 0.0D);
                    ClientPortal arrived = new ClientPortal(300 + index, root.geometry().withParent(2), 1, 0.0D);
                    ClientPortal grandchild = new ClientPortal(400 + index, child.geometry().withParent(arrived.portalKey()), 1, 0.0D);
                    homePortals.add(root);
                    homePortals.add(child);
                    reversePortals.add(arrived);
                    reversePortals.add(grandchild);
                    announce(root, home);
                    for (ClientPortal portal : List.of(root, child, arrived, grandchild)) {
                        session.portals().put(portal.portalKey(), portal);
                        environment(portal, home, Similarity.of(PortalSurface.mirrorTransform(portal.geometry()), 1.0D));
                    }
                }
                depart();
            } catch (Exception | Error failure) {
                close();
                throw failure;
            }
        }

        public List<List<PortalView>> homePaths() {
            List<List<PortalView>> paths = new ArrayList<>(12);
            for (PortalView root : views.current()) {
                if (root.kind() == PortalView.Kind.MIRROR) {
                    paths.add(List.of(root));
                    paths.add(List.of(root, views.children(root).getFirst()));
                }
            }
            return List.copyOf(paths);
        }

        public void crossOut() {
            views.crossed(forward, home, destination, true);
        }

        public void arrive(boolean armed, boolean children) {
            views.frame(destination, arrivalEye(), armed ? List.of(reverse) : List.of(), true, true,
                children ? reversePortals : List.of(reverseRoot), null);
        }

        public void crossBack() {
            views.crossed(reverse, destination, home, true);
            depart();
        }

        public Vec3d arrivalEye() {
            return toward.point(eye);
        }

        public List<ClientPortal> arrivalPortals() {
            return List.copyOf(reversePortals);
        }

        private void depart() {
            views.frame(home, eye, List.of(forward), true, true, homePortals, null);
        }

        private void announceReverse() throws Exception {
            announce(reverseRoot, destination);
            environment(reverseRoot, home, toward.inverse());
        }

        private void announce(ClientPortal portal, ClientLevel source) throws Exception {
            session.portals().put(portal.portalKey(), portal);
            session.handle(RootSourceExtension.INSTANCE.wrap(new RootSource(portal.portalKey(), portal.geometryRevision(),
                source.dimension().identifier().toString())), sink);
        }

        private void environment(ClientPortal portal, ClientLevel target, Similarity transform) throws Exception {
            EnvironmentState environment = mock(EnvironmentState.class);
            EnvironmentState.World world = mock(EnvironmentState.World.class);
            String dimension = target.dimension().identifier().toString();
            when(world.dimensionKey()).thenReturn(dimension);
            when(environment.world()).thenReturn(world);
            float scale = (float) (1.0D / transform.scale());
            Vec3d center = PortalSurface.of(portal.geometry()).planePoint();
            Similarity content = Similarity.between(portal.geometry().frame(), center, portal.geometry().frame(), center, scale);
            when(environment.transform()).thenReturn(content.inverse().compose(transform.inverse()).rigid());
            when(environment.scale()).thenReturn(scale);
            session.handle(new ViewStreamMessage.Environment(portal.portalKey(), environment), sink);
            session.handle(LocalDestinationExtension.INSTANCE.wrap(new LocalDestination(portal.portalKey(),
                portal.geometryRevision(), target.dimension().identifier().toString())), sink);
        }

        private TravelMessage.TravelBegin arm(ApertureDescriptor aperture, String source, int handle, Similarity transform) {
            TravelMessage.TravelBegin arm = mock(TravelMessage.TravelBegin.class);
            when(arm.token()).thenReturn(UUID.randomUUID());
            when(arm.sourcePortal()).thenReturn(UUID.randomUUID());
            when(arm.sourceWorld()).thenReturn(source);
            when(arm.sourceGeometry()).thenReturn(aperture);
            when(arm.sourceToDestination()).thenReturn(transform);
            when(arm.resident()).thenReturn(true);
            when(arm.levelHandle()).thenReturn(handle);
            return arm;
        }

        @Override
        public void close() {
            try {
                views.clear();
            } finally {
                worlds.close();
                clients.close();
                backends.close();
            }
        }
    }
}
