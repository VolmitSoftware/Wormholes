package art.arcane.wormholes.modded.client.render;

import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.irisshaders.iris.pipeline.WorldRenderingPhase;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class PortalIrisShadowFrameTest {
    @Test
    public void failedShadowFeaturesRestoreThePreviousPrivatePhase() {
        AtomicReference<WorldRenderingPhase> current = new AtomicReference<>(WorldRenderingPhase.TERRAIN_CUTOUT);
        List<String> calls = new ArrayList<>();
        Supplier<WorldRenderingPhase> getter = () -> {
            calls.add("get");
            return current.get();
        };
        Consumer<WorldRenderingPhase> setter = phase -> {
            calls.add(phase.name());
            current.set(phase);
        };
        assertThrows(IllegalStateException.class, () -> {
            try (PortalShaderRenderer.Frame phase = PortalIrisShadowFrame.features(getter, setter)) {
                assertEquals(WorldRenderingPhase.ENTITIES, current.get());
                throw new IllegalStateException("shadow feature draw failed");
            }
        });
        assertEquals(WorldRenderingPhase.TERRAIN_CUTOUT, current.get());
        assertEquals(List.of("get", "ENTITIES", "TERRAIN_CUTOUT"), calls);
    }

    @Test
    public void defaultDepthRangeMatchesIrisShaderUniformsIncludingDistantHorizonsRange() {
        PackShadowDirectives directives = mock(PackShadowDirectives.class);
        when(directives.getNearPlane()).thenReturn(-1F);
        when(directives.getFarPlane()).thenReturn(-1F);
        when(directives.getDistance()).thenReturn(128F);
        assertEquals(new PortalIrisShadowFrame.Planes(-64F, 64F), PortalIrisShadowFrame.planes(directives, 4));
        assertEquals(new PortalIrisShadowFrame.Planes(-4096F, 4096F), PortalIrisShadowFrame.planes(directives, 256));
    }

    @Test
    public void explicitPackPlanesRemainIndependentOfSourceLodDistance() {
        PackShadowDirectives directives = mock(PackShadowDirectives.class);
        when(directives.getNearPlane()).thenReturn(-24F);
        when(directives.getFarPlane()).thenReturn(192F);
        assertEquals(new PortalIrisShadowFrame.Planes(-24F, 192F), PortalIrisShadowFrame.planes(directives, 256));
        when(directives.getFarPlane()).thenReturn(-1F);
        assertEquals(new PortalIrisShadowFrame.Planes(-24F, 4096F), PortalIrisShadowFrame.planes(directives, 256));
    }
}
