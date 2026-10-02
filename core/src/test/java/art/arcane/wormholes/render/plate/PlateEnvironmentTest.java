package art.arcane.wormholes.render.plate;

import art.arcane.wormholes.network.client.BrickLightSource;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.render.ProjectorFrameTransform;
import art.arcane.wormholes.render.view.ProjectionContentView;
import art.arcane.wormholes.util.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class PlateEnvironmentTest {
    @Test
    void projectedMetadataSurvivesSourceUnloadWithoutFurtherReads() {
        ProjectionContentView<String, String> view = mock(ProjectionContentView.class);
        when(view.sampleBiome(anyInt(), anyInt(), anyInt())).thenAnswer(call -> (int) call.getArgument(0) < 0 ? "test:west" : "test:east");
        when(view.getLight(anyInt(), anyInt(), anyInt())).thenAnswer(call -> ProjectionContentView.packLight(13, (int) call.getArgument(1) & 15));
        ProjectorFrameTransform transform = new ProjectorFrameTransform();
        PortalFrame frame = PortalFrame.canonical(Direction.N);
        transform.configure(frame, frame, 0, 0, 0, -8, 0, 0);
        PlateEnvironment environment = PlateEnvironment.capture(new PlateBox(0, -16, 0, 16, 16, 16), transform, view);
        assertNotNull(environment);
        assertTrue(environment.bytes() < 3000, "uniform sky must not retain a full light channel");
        when(view.sampleBiome(anyInt(), anyInt(), anyInt())).thenThrow(new IllegalStateException("unloaded"));
        when(view.getLight(anyInt(), anyInt(), anyInt())).thenThrow(new IllegalStateException("unloaded"));
        byte[] block = new byte[2048];
        byte[] sky = new byte[2048];
        environment.fill(0, -1, 0, block, sky);
        assertEquals("test:west", environment.biomes().biome(0));
        assertEquals("test:east", environment.biomes().biome(3));
        assertEquals(13, BrickLightSource.nibble(sky, 4095));
        assertEquals(15, BrickLightSource.nibble(block, 4095));
        block[0] = 0;
        environment.fill(0, -1, 0, block, sky);
        assertEquals(13, BrickLightSource.nibble(sky, 0));
    }

    @Test
    void missingDestinationSamplesCannotCreatePartialEnvironment() {
        ProjectionContentView<String, String> view = mock(ProjectionContentView.class);
        ProjectorFrameTransform transform = new ProjectorFrameTransform();
        PortalFrame frame = PortalFrame.canonical(Direction.N);
        transform.configure(frame, frame, 0, 0, 0, 0, 0, 0);
        PlateBox box = new PlateBox(0, 0, 0, 16, 16, 16);
        assertNull(PlateEnvironment.capture(box, transform, view));
        when(view.sampleBiome(anyInt(), anyInt(), anyInt())).thenReturn("test:plains");
        when(view.getLight(anyInt(), anyInt(), anyInt())).thenReturn(ProjectionContentView.LIGHT_UNAVAILABLE);
        assertNull(PlateEnvironment.capture(box, transform, view));
    }
}
