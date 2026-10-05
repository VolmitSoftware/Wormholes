package art.arcane.wormholes.modded.client.render;

import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

public class PortalIrisResolutionTest {
    private static final long MIB = 1024L * 1024L;
    private static final NamespacedId OVERWORLD = new NamespacedId("minecraft:overworld");
    private static final NamespacedId NETHER = new NamespacedId("minecraft:the_nether");

    @Test
    public void preservesFullFramebufferResolutionWhenAllViewsFit() {
        ProgramSet programs = mock(ProgramSet.class);
        try (MockedStatic<PortalIrisResources> resources = resources(programs, true, 2 * MIB)) {
            PortalIrisResolution planner = new PortalIrisResolution(40 * MIB);
            assertEquals(new PortalShaderRenderer.Resolution(1920, 1080), planner.select(demand(programs, 1)).getFirst());
        }
    }

    @Test
    public void prioritizesFullResolutionRootWhileRetainingSixBudgetedHistories() {
        ProgramSet programs = mock(ProgramSet.class);
        try (MockedStatic<PortalIrisResources> resources = resources(programs, true, 2 * MIB)) {
            PortalIrisResolution.Demand demand = demand(programs, 6);
            List<PortalShaderRenderer.Resolution> sizes = new PortalIrisResolution(40 * MIB).select(demand);
            assertEquals(new PortalShaderRenderer.Resolution(1920, 1080), sizes.getFirst());
            assertEquals(6, demand.programs().get(OVERWORLD).depths().size());
            assertEquals(6, sizes.size());
            assertTrue(bytes(sizes) + 2 * MIB <= 40 * MIB);
            for (int depth = 1; depth < sizes.size(); depth++) {
                assertTrue(sizes.get(depth).width() <= sizes.get(depth - 1).width());
                assertTrue(sizes.get(depth).width() >= 256);
            }
            resources.when(() -> PortalIrisResources.shareShadows(programs)).thenReturn(false);
            List<PortalShaderRenderer.Resolution> privateSizes = new PortalIrisResolution(40 * MIB).select(demand);
            assertEquals(sizes.getFirst(), privateSizes.getFirst());
            assertTrue(privateSizes.get(1).width() <= sizes.get(1).width());
        }
    }

    @Test
    public void eachDimensionHasItsOwnSharedShadowsEvenWithSameProgramSet() {
        ProgramSet programs = mock(ProgramSet.class);
        try (MockedStatic<PortalIrisResources> resources = resources(programs, true, 10 * MIB)) {
            PortalIrisResolution.Demand demand = new PortalIrisResolution.Demand(1920, 1080, Map.of(
                OVERWORLD, new PortalIrisResolution.Dimension(programs, List.of(0)),
                NETHER, new PortalIrisResolution.Dimension(programs, List.of(0))), PortalIrisResources.revision(), 0);
            assertEquals(new PortalShaderRenderer.Resolution(1920, 1080), new PortalIrisResolution(52 * MIB).select(demand).getFirst());
        }
    }

    @Test
    public void visibilityChurnDoesNotRepeatedlyResizeHistoryAndStableDemandEventuallyGrows() {
        ProgramSet programs = mock(ProgramSet.class);
        try (MockedStatic<PortalIrisResources> resources = resources(programs, true, 2 * MIB)) {
            PortalIrisResolution planner = new PortalIrisResolution(40 * MIB);
            PortalIrisResolution.Demand six = demand(programs, 6);
            PortalIrisResolution.Demand one = demand(programs, 1);
            List<PortalShaderRenderer.Resolution> reduced = planner.select(six);
            for (int frame = 0; frame < 119; frame++) {
                assertEquals(reduced, planner.select(one));
            }
            assertEquals(reduced, planner.select(six));
            assertEquals(reduced, planner.select(new PortalIrisResolution.Demand(1920, 1080, Map.of(), PortalIrisResources.revision(), 0)));
            for (int frame = 0; frame < 119; frame++) {
                assertEquals(reduced, planner.select(one));
            }
            assertEquals(new PortalShaderRenderer.Resolution(1920, 1080), planner.select(one).getFirst());
            assertEquals(reduced, planner.select(six));
        }
    }

    @Test
    public void stableDemandReusesSizesWhileWaitingForGrowth() {
        ProgramSet programs = mock(ProgramSet.class);
        try (MockedStatic<PortalIrisResources> resources = resources(programs, true, 2 * MIB)) {
            PortalIrisResolution planner = new PortalIrisResolution(40 * MIB);
            PortalIrisResolution.Demand six = demand(programs, 6);
            PortalIrisResolution.Demand one = demand(programs, 1);
            List<PortalShaderRenderer.Resolution> initial = planner.select(six);
            assertSame(initial, planner.select(six));
            List<PortalShaderRenderer.Resolution> waiting = planner.select(one);
            assertEquals(initial, waiting);
            for (int frame = 1; frame < 119; frame++) {
                assertSame(waiting, planner.select(one));
            }
            List<PortalShaderRenderer.Resolution> grown = planner.select(one);
            assertEquals(new PortalShaderRenderer.Resolution(1920, 1080), grown.getLast());
            assertSame(grown, planner.select(one));
            assertEquals(initial, planner.select(six));
        }
    }

    @Test
    public void resizeImmediatelyUsesNewAspectAndBudgetWithoutDroppingDemand() {
        ProgramSet programs = mock(ProgramSet.class);
        try (MockedStatic<PortalIrisResources> resources = resources(programs, true, 2 * MIB)) {
            PortalIrisResolution planner = new PortalIrisResolution(40 * MIB);
            planner.select(demand(programs, 6));
            assertEquals(new PortalShaderRenderer.Resolution(1280, 1024), planner.select(
                new PortalIrisResolution.Demand(1280, 1024, demand(programs, 6).programs(), PortalIrisResources.revision(), 0)).getFirst());
            assertEquals(new PortalShaderRenderer.Resolution(640, 360), planner.select(
                new PortalIrisResolution.Demand(640, 360, demand(programs, 6).programs(), PortalIrisResources.revision(), 0)).getFirst());
        }
    }

    @Test
    public void reorderedTreesHaveIdenticalBudgetDemand() {
        ProgramSet programs = mock(ProgramSet.class);
        PortalIrisResolution.Dimension ordered = new PortalIrisResolution.Dimension(programs, List.of(0, 1, 2, 0, 1));
        PortalIrisResolution.Dimension reordered = new PortalIrisResolution.Dimension(programs, List.of(0, 1, 0, 1, 2));
        assertEquals(ordered, reordered);
    }

    @Test
    public void activeRootsAndRequiredPrivateResourcesMayExceedCacheTarget() {
        ProgramSet programs = mock(ProgramSet.class);
        try (MockedStatic<PortalIrisResources> resources = resources(programs, false, 8 * MIB)) {
            List<Integer> roots = new ArrayList<>();
            for (int index = 0; index < 100; index++) {
                roots.add(0);
            }
            PortalIrisResolution.Demand active = new PortalIrisResolution.Demand(1920, 1080,
                Map.of(OVERWORLD, new PortalIrisResolution.Dimension(programs, roots)), PortalIrisResources.revision(), 0);
            assertEquals(new PortalShaderRenderer.Resolution(1920, 1080),
                new PortalIrisResolution(40 * MIB).select(active).getFirst());
            List<PortalShaderRenderer.Resolution> nested = new PortalIrisResolution(40 * MIB).select(demand(programs, 6));
            assertEquals(new PortalShaderRenderer.Resolution(1920, 1080), nested.getFirst());
            assertTrue(bytes(nested) + 48 * MIB > 40 * MIB);
        }
    }

    private static PortalIrisResolution.Demand demand(ProgramSet programs, int views) {
        List<Integer> depths = new ArrayList<>(views);
        for (int depth = 0; depth < views; depth++) {
            depths.add(depth);
        }
        return new PortalIrisResolution.Demand(1920, 1080, Map.of(OVERWORLD, new PortalIrisResolution.Dimension(programs, depths)), PortalIrisResources.revision(), 0);
    }

    private static long bytes(List<PortalShaderRenderer.Resolution> sizes) {
        long bytes = 0;
        for (PortalShaderRenderer.Resolution size : sizes) {
            bytes += (long) size.width() * size.height() * 16;
        }
        return bytes;
    }

    private static MockedStatic<PortalIrisResources> resources(ProgramSet programs, boolean shared, long shadows) {
        MockedStatic<PortalIrisResources> resources = mockStatic(PortalIrisResources.class);
        resources.when(() -> PortalIrisResources.targets(eq(programs), anyInt(), anyInt()))
            .thenAnswer(call -> (long) call.<Integer>getArgument(1) * call.<Integer>getArgument(2) * 16);
        resources.when(() -> PortalIrisResources.shadows(programs)).thenReturn(shadows);
        resources.when(() -> PortalIrisResources.shareShadows(programs)).thenReturn(shared);
        return resources;
    }
}
