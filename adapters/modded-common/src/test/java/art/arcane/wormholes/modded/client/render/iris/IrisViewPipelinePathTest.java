package art.arcane.wormholes.modded.client.render.iris;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.ClientPortal;
import art.arcane.wormholes.modded.client.ClientViewSession;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.ResidentLevels;
import art.arcane.wormholes.modded.client.render.stencil.PipelineBackend;
import art.arcane.wormholes.modded.client.render.stencil.PortalBackends;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import art.arcane.wormholes.modded.client.render.stencil.PortalSurface;
import art.arcane.wormholes.modded.client.render.stencil.PortalViews;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
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
            assertTrue(cache.prepare(path, 1, Set.copyOf(paths)));
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

    private static IrisViewPipelinePath.Camera camera(PortalView view) {
        return new IrisViewPipelinePath.Camera(view.kind().name(), view.key(), view.source().dimension().identifier().toString(),
            view.destination().dimension().identifier().toString(), view.toDestination());
    }

    private static ApertureDescriptor mirrorGeometry(int x) {
        return new ApertureDescriptor(x, 64, 0, Face.S.ordinal(), true, 0, true, 2, 3, new long[]{63L}, ShapeDescriptor.FULL,
            0.0F, 0.0F, 0.0F, 16, 3, 0, 0, 0, 0, 0, 0, 0.0D, 0, 0L, List.of());
    }
}
