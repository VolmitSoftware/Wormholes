package art.arcane.optics.plate;

import art.arcane.optics.stream.BrickLightSource;
import art.arcane.optics.stream.SectionBiomes;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.ProjectorFrameTransform;
import art.arcane.optics.view.ContentView;
import art.arcane.optics.math.Face;
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
        ContentView<String, String> view = mock(ContentView.class);
        when(view.sampleBiome(anyInt(), anyInt(), anyInt())).thenAnswer(call -> (int) call.getArgument(0) < 0 ? "test:west" : "test:east");
        when(view.getLight(anyInt(), anyInt(), anyInt())).thenAnswer(call -> ContentView.packLight(13, (int) call.getArgument(1) & 15));
        ProjectorFrameTransform transform = new ProjectorFrameTransform();
        Frame frame = Frame.canonical(Face.N);
        transform.configure(frame, frame, 0, 0, 0, -8, 0, 0);
        PlateEnvironment environment = PlateEnvironment.capture(new PlateBox(0, -16, 0, 16, 16, 16), transform, view);
        assertNotNull(environment);
        assertTrue(environment.bytes() < 4096, "uniform sky must not retain a full light channel");
        when(view.sampleBiome(anyInt(), anyInt(), anyInt())).thenThrow(new IllegalStateException("unloaded"));
        when(view.getLight(anyInt(), anyInt(), anyInt())).thenThrow(new IllegalStateException("unloaded"));
        byte[] block = new byte[2048];
        byte[] sky = new byte[2048];
        environment.fill(0, -1, 0, block, sky);
        assertEquals("test:west", environment.biomes().biome(SectionBiomes.cell(0, 0, 0)));
        assertEquals("test:east", environment.biomes().biome(SectionBiomes.cell(12, 0, 0)));
        assertEquals(13, BrickLightSource.nibble(sky, 4095));
        assertEquals(15, BrickLightSource.nibble(block, 4095));
        block[0] = 0;
        environment.fill(0, -1, 0, block, sky);
        assertEquals(13, BrickLightSource.nibble(sky, 0));
    }

    @Test
    void sectionBiomeHaloIncludesBelowSectionAndMaximumBlendRadius() {
        ContentView<String, String> view = mock(ContentView.class);
        when(view.sampleBiome(anyInt(), anyInt(), anyInt())).thenAnswer(call -> (int) call.getArgument(1) < 0 ? "test:below" : "test:above");
        when(view.getLight(anyInt(), anyInt(), anyInt())).thenReturn(ContentView.packLight(15, 0));
        ProjectorFrameTransform transform = new ProjectorFrameTransform();
        Frame frame = Frame.canonical(Face.N);
        transform.configure(frame, frame, 0, 0, 0, 0, 0, 0);
        PlateEnvironment environment = PlateEnvironment.capture(new PlateBox(0, 0, 0, 16, 16, 16), transform, view);
        assertNotNull(environment);
        when(view.sampleBiome(anyInt(), anyInt(), anyInt())).thenThrow(new IllegalStateException("unloaded"));
        for (int x = -7; x <= 7; x++) {
            for (int z = -7; z <= 7; z++) {
                assertEquals("test:below", environment.biomes().biome(SectionBiomes.cell(x, -1, z)));
                assertEquals("test:above", environment.biomes().biome(SectionBiomes.cell(15 + x, 16, 15 + z)));
            }
        }
    }

    @Test
    void missingDestinationSamplesCannotCreatePartialEnvironment() {
        ContentView<String, String> view = mock(ContentView.class);
        ProjectorFrameTransform transform = new ProjectorFrameTransform();
        Frame frame = Frame.canonical(Face.N);
        transform.configure(frame, frame, 0, 0, 0, 0, 0, 0);
        PlateBox box = new PlateBox(0, 0, 0, 16, 16, 16);
        assertNull(PlateEnvironment.capture(box, transform, view));
        when(view.sampleBiome(anyInt(), anyInt(), anyInt())).thenReturn("test:plains");
        when(view.getLight(anyInt(), anyInt(), anyInt())).thenReturn(ContentView.LIGHT_UNAVAILABLE);
        assertNull(PlateEnvironment.capture(box, transform, view));
    }
}
