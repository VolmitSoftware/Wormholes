package art.arcane.wormholes.modded.client.render;

import com.mojang.blaze3d.vertex.QuadInstance;
import net.minecraft.core.Direction;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.CardinalLighting;
import org.junit.Test;

import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public class PortalTerrainLightingTest {
    @Test
    public void separateAoSurvivesActualQuadDirectionalAndTintOperations() {
        try (PortalTerrainLighting scope = new PortalTerrainLighting(new PortalTerrainMaterials.Lighting(0.8F, false, true))) {
            QuadInstance quad = new QuadInstance();
            quad.setColor(PortalTerrainLighting.ambientColor(0.5F));
            quad.scaleColor(0.8F);
            quad.multiplyColor(ARGB.color(255, 200, 100, 50));
            for (int vertex = 0; vertex < 4; vertex++) {
                assertEquals(127, ARGB.alpha(quad.getColor(vertex)));
                assertEquals(160, ARGB.red(quad.getColor(vertex)));
                assertEquals(80, ARGB.green(quad.getColor(vertex)));
                assertEquals(40, ARGB.blue(quad.getColor(vertex)));
            }
        }
        assertEquals(ARGB.gray(0.5F), PortalTerrainLighting.ambientColor(0.5F));
        assertNull(PortalTerrainLighting.current());
    }

    @Test
    public void directionalFlagsApplyToBlocksAndFluidFacesAndRestoreAfterFailure() {
        PortalTerrainMaterials.Lighting outer = new PortalTerrainMaterials.Lighting(0.4F, false, false);
        try (PortalTerrainLighting scope = new PortalTerrainLighting(outer)) {
            try {
                try (PortalTerrainLighting nested = new PortalTerrainLighting(new PortalTerrainMaterials.Lighting(0.9F, true, true))) {
                    assertEquals(1, PortalTerrainLighting.directionalBrightness(0.6F), 0);
                    CardinalLighting fluid = PortalTerrainLighting.cardinalLighting(CardinalLighting.DEFAULT);
                    for (Direction direction : Direction.values()) {
                        assertEquals(1, fluid.byFace(direction), 0);
                    }
                    throw new IllegalStateException("Interrupted section compile");
                }
            } catch (IllegalStateException expected) {
                assertSame(outer, PortalTerrainLighting.current());
            }
            assertEquals(0.6F, PortalTerrainLighting.directionalBrightness(0.6F), 0);
            assertSame(CardinalLighting.DEFAULT, PortalTerrainLighting.cardinalLighting(CardinalLighting.DEFAULT));
        }
        assertNull(PortalTerrainLighting.current());
    }

    @Test
    public void simultaneousSectionWorkersKeepIndependentImmutableLighting() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<PortalTerrainMaterials.Lighting> first = workers.submit(() -> sample(barrier, new PortalTerrainMaterials.Lighting(0.2F, true, false)));
            Future<PortalTerrainMaterials.Lighting> second = workers.submit(() -> sample(barrier, new PortalTerrainMaterials.Lighting(0.8F, false, true)));
            assertEquals(new PortalTerrainMaterials.Lighting(0.2F, true, false), first.get(5, TimeUnit.SECONDS));
            assertEquals(new PortalTerrainMaterials.Lighting(0.8F, false, true), second.get(5, TimeUnit.SECONDS));
            assertNull(PortalTerrainLighting.current());
        } finally {
            workers.shutdownNow();
        }
    }

    private static PortalTerrainMaterials.Lighting sample(CyclicBarrier barrier, PortalTerrainMaterials.Lighting lighting) throws Exception {
        try (PortalTerrainLighting scope = new PortalTerrainLighting(lighting)) {
            barrier.await(3, TimeUnit.SECONDS);
            return PortalTerrainLighting.current();
        } finally {
            assertNull(PortalTerrainLighting.current());
        }
    }
}
