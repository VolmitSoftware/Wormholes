package art.arcane.wormholes.modded.client.render.iris;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.client.render.stencil.NativeMirrorHistoryScene;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class IrisNativeMirrorHistoryTest extends MinecraftTestBase {
    @Test
    public void sixIndependentOwnersSurviveLateChildrenGrandchildrenAndNewStreamSlots() {
        AtomicInteger creations = new AtomicInteger();
        List<AtomicInteger> released = new ArrayList<>();
        IrisViewPipelineCache<IrisViewPipelinePath, AtomicInteger> cache = new IrisViewPipelineCache<>(
            new IrisViewPipelineCache.Options<>(12, key -> new AtomicInteger(creations.incrementAndGet()), released::add, System::nanoTime));
        try (NativeMirrorHistoryScene scene = new NativeMirrorHistoryScene()) {
            scene.frame(0);
            List<IrisViewPipelinePath> initial = paths(scene.traversals());
            List<AtomicInteger> owners = new ArrayList<>(6);
            for (IrisViewPipelinePath path : initial) {
                owners.add(cache.enter(path, 1));
                cache.exit(path);
            }
            assertEquals(6, new HashSet<>(initial).size());
            assertEquals(6, new HashSet<>(owners).size());
            for (int stage : List.of(2, 3, 4)) {
                scene.frame(stage);
                reuse(cache, paths(scene.traversals()), owners, stage + 1);
            }
            scene.views.frame(scene.level, Vec3d.ZERO, List.of(), false, true, List.of(), null);
            scene.slots(40, 8);
            scene.frame(0);
            reuse(cache, paths(scene.traversals()), owners, 8);
            scene.frame(4);
            reuse(cache, paths(scene.traversals()), owners, 9);
            assertEquals(6, creations.get());
            assertEquals(6, cache.size());
            assertEquals(0, cache.statistics().evictions());
            assertTrue(released.isEmpty());
            PortalView a = scene.views.current().get(0);
            PortalView b = scene.views.current().get(1);
            assertNotEquals(path(List.of(a, b)), path(List.of(b, a)));
            assertNotEquals(path(List.of(a)), path(List.of(a, a)));
            IrisViewPipelinePath mirror = path(List.of(a));
            assertNotEquals(mirror, new IrisViewPipelinePath(mirror.cameras(), "minecraft:the_end"));
        } finally {
            cache.close();
        }
        assertEquals(6, released.size());
        assertEquals(6, new HashSet<>(released).size());
    }

    @Test
    public void equalDimensionReplacementNeverBorrowsPreviousNativeMirrorOwner() {
        IrisViewPipelinePath previous;
        try (NativeMirrorHistoryScene scene = new NativeMirrorHistoryScene()) {
            scene.frame(4);
            previous = paths(scene.traversals()).get(2);
        }
        try (NativeMirrorHistoryScene replacement = new NativeMirrorHistoryScene()) {
            replacement.frame(4);
            assertNotEquals(previous, paths(replacement.traversals()).get(2));
        }
    }

    private static void reuse(IrisViewPipelineCache<IrisViewPipelinePath, AtomicInteger> cache,
                              List<IrisViewPipelinePath> paths, List<AtomicInteger> owners, int frame) {
        cache.plan(new HashSet<>(paths), Map.of(), new HashSet<>());
        for (int index = 0; index < paths.size(); index++) {
            IrisViewPipelinePath path = paths.get(index);
            assertSame(owners.get(index), cache.enter(path, frame));
            cache.exit(path);
        }
    }

    private static List<IrisViewPipelinePath> paths(List<List<PortalView>> traversals) {
        List<IrisViewPipelinePath> paths = new ArrayList<>(traversals.size());
        for (List<PortalView> traversal : traversals) {
            paths.add(path(traversal));
        }
        return paths;
    }

    private static IrisViewPipelinePath path(List<PortalView> traversal) {
        List<IrisViewPipelinePath.Camera> cameras = new ArrayList<>(traversal.size());
        for (PortalView view : traversal) {
            cameras.add(IrisViewPipelines.camera(view));
        }
        return new IrisViewPipelinePath(cameras, "minecraft:overworld");
    }
}
