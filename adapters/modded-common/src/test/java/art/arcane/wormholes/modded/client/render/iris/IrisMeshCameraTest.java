package art.arcane.wormholes.modded.client.render.iris;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Face;
import art.arcane.optics.shape.ShapeDescriptor;
import org.junit.Test;

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

public class IrisMeshCameraTest {
    private static final String OVERWORLD = "minecraft:overworld";
    private static final String NETHER = "minecraft:the_nether";

    @Test
    public void streamedSiblingsOwnStableHistoriesWhileNativeParentIsActive() {
        AtomicInteger allocations = new AtomicInteger();
        List<History> released = new ArrayList<>();
        IrisViewPipelineCache<IrisViewPipelinePath, History> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(12, path -> new History(allocations.incrementAndGet()), released::add, System::nanoTime));
        IrisViewPipelinePath.Camera parent = parent("native-parent");
        IrisViewPipelinePath nativePath = new IrisViewPipelinePath(List.of(parent), OVERWORLD);
        IrisViewPipelinePath firstPath = camera(0).append(List.of(parent), OVERWORLD);
        IrisViewPipelinePath secondPath = camera(8).append(List.of(parent), OVERWORLD);
        Set<IrisViewPipelinePath> planned = Set.of(nativePath, firstPath, secondPath);
        cache.plan(planned, Map.of(), Set.of());
        for (IrisViewPipelinePath path : planned) {
            assertTrue(cache.prepare(path, 1, planned, IrisViewPipelineCache.Preparation.ACTUAL));
        }
        History nativeHistory = cache.enter(nativePath, 1);
        nativeHistory.updates = 11;
        History first = cache.enter(firstPath, 1);
        first.updates++;
        cache.exit(firstPath);
        History second = cache.enter(secondPath, 1);
        second.updates += 2;
        cache.exit(secondPath);
        assertNotSame(nativeHistory, first);
        assertNotSame(nativeHistory, second);
        assertNotSame(first, second);
        assertNotEquals(first.texture, second.texture);
        assertEquals(11, nativeHistory.updates);
        cache.exit(nativePath);
        for (int frame = 2; frame <= 5; frame++) {
            assertSame(first, cache.enter(camera(0).append(List.of(parent), OVERWORLD), frame));
            cache.exit(firstPath);
            assertSame(second, cache.enter(camera(8).append(List.of(parent), OVERWORLD), frame));
            cache.exit(secondPath);
        }
        assertEquals(3, allocations.get());
        assertEquals(1, first.updates);
        assertEquals(2, second.updates);
        cache.close();
        assertEquals(Set.of(nativeHistory, first, second), Set.copyOf(released));
    }

    @Test
    public void enclosingCameraAndSelectedDimensionOwnDifferentStreamedHistories() {
        IrisMeshCamera stream = camera(0);
        assertNotEquals(stream.append(List.of(), OVERWORLD), stream.append(List.of(parent("a")), OVERWORLD));
        assertNotEquals(stream.append(List.of(parent("a")), OVERWORLD), stream.append(List.of(parent("b")), OVERWORLD));
        assertNotEquals(stream.append(List.of(parent("a")), OVERWORLD), stream.append(List.of(parent("a")), NETHER));
        assertEquals(NETHER, stream.append(List.of(parent("a")), NETHER).dimension());
    }

    @Test
    public void presentationChangesAndStreamSlotsKeepPhysicalCameraIdentity() {
        ApertureDescriptor aperture = aperture(0, 7, 3);
        ApertureDescriptor refreshed = new ApertureDescriptor(aperture.originX(), aperture.originY(), aperture.originZ(), aperture.facing(),
            aperture.frontSide(), aperture.quarterTurns(), aperture.mirror(), aperture.apertureWidth(), aperture.apertureHeight(),
            aperture.apertureMask(), aperture.shape(), 0.7F, 0.2F, 0.9F, 64, 4, 1, 1, 1, 1, 3, 2, aperture.planeOffset(), 42,
            aperture.targetIdentity(), List.of());
        assertEquals(new IrisMeshCamera(List.of(aperture), OVERWORLD, OVERWORLD, Similarity.IDENTITY),
            new IrisMeshCamera(List.of(refreshed), OVERWORLD, OVERWORLD, Similarity.IDENTITY));
    }

    @Test
    public void differentDestinationAndStreamedAncestorsStayIndependent() {
        ApertureDescriptor child = aperture(0, 7, 3);
        assertNotEquals(new IrisMeshCamera(List.of(aperture(2, 0, 1), child), OVERWORLD, OVERWORLD, Similarity.IDENTITY),
            new IrisMeshCamera(List.of(aperture(4, 0, 1), child), OVERWORLD, OVERWORLD, Similarity.IDENTITY));
        assertNotEquals(new IrisMeshCamera(List.of(child), OVERWORLD, OVERWORLD, Similarity.IDENTITY),
            new IrisMeshCamera(List.of(child), OVERWORLD, NETHER, Similarity.IDENTITY));
        assertNotEquals(new IrisMeshCamera(List.of(child), OVERWORLD, OVERWORLD, Similarity.IDENTITY),
            new IrisMeshCamera(List.of(aperture(0, 7, 4)), OVERWORLD, OVERWORLD, Similarity.IDENTITY));
    }

    @Test
    public void realSourceWorldsSharingOneShaderDimensionKeepSeparateHistories() {
        ApertureDescriptor aperture = aperture(0, 0, 1);
        IrisMeshCamera first = new IrisMeshCamera(List.of(aperture), "example:first", OVERWORLD, Similarity.IDENTITY);
        IrisMeshCamera second = new IrisMeshCamera(List.of(aperture), "example:second", OVERWORLD, Similarity.IDENTITY);
        IrisViewPipelinePath firstPath = first.append(List.of(), OVERWORLD);
        IrisViewPipelinePath secondPath = second.append(List.of(), OVERWORLD);
        assertEquals(firstPath.dimension(), secondPath.dimension());
        assertNotEquals(firstPath, secondPath);
        IrisViewPipelineCache<IrisViewPipelinePath, Object> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(12, path -> new Object(), value -> {}, System::nanoTime));
        Object firstHistory = cache.enter(firstPath, 1);
        Object secondHistory = cache.enter(secondPath, 1);
        assertNotSame(firstHistory, secondHistory);
        cache.exit(firstPath);
        cache.exit(secondPath);
        cache.close();
    }

    @Test
    public void nativeAndStreamedCamerasUseOneCapacityAndActiveOwnersCannotBeEvicted() {
        IrisViewPipelineCache<IrisViewPipelinePath, Object> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(2, path -> new Object(), value -> {}, System::nanoTime));
        IrisViewPipelinePath nativePath = new IrisViewPipelinePath(List.of(parent("a")), OVERWORLD);
        IrisViewPipelinePath first = camera(0).append(nativePath.cameras(), OVERWORLD);
        IrisViewPipelinePath second = camera(8).append(nativePath.cameras(), OVERWORLD);
        cache.enter(nativePath, 1);
        cache.enter(first, 1);
        assertThrows(IllegalStateException.class, () -> cache.enter(second, 1));
        assertEquals(2, cache.size());
        cache.exit(first);
        cache.exit(nativePath);
        cache.close();
    }

    private static IrisMeshCamera camera(int x) {
        return new IrisMeshCamera(List.of(aperture(x, 0, 1)), OVERWORLD, OVERWORLD, Similarity.IDENTITY);
    }

    private static IrisViewPipelinePath.Camera parent(String key) {
        return new IrisViewPipelinePath.Camera("MIRROR", key, OVERWORLD, OVERWORLD, Similarity.IDENTITY);
    }

    private static ApertureDescriptor aperture(int x, int parent, long target) {
        return new ApertureDescriptor(x, 64, 0, Face.N.ordinal(), true, 0, true, 2, 3, new long[] {63L}, ShapeDescriptor.FULL,
            0.0F, 0.0F, 0.0F, 0, 0, 0, 0, 0, 0, 0, 0, 0.0D, parent, target, List.of());
    }

    private static final class History {
        private final int texture;
        private int updates;

        private History(int texture) {
            this.texture = texture;
        }
    }
}
