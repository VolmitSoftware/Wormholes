package art.arcane.optics.plate;

import art.arcane.optics.math.BlockBox;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import art.arcane.optics.math.Vec3d;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.math.CellKeys;
import art.arcane.optics.scan.Sample;
import art.arcane.optics.client.ClientSweepScene;
import art.arcane.optics.volume.LodPolicy;
import art.arcane.optics.view.ContentView;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import org.junit.jupiter.api.Test;

final class PlaneSectionCaptureTest {
    @Test
    void meshSectionsCaptureThePlaneBlockWithoutChangingPacketClearance() {
        ClientSweepScene.SceneView view = new ClientSweepScene.SceneView(UUID.randomUUID(), (x, y, z) -> "minecraft:stone");
        for (Face facing : Face.values()) {
            for (boolean front : new boolean[] {false, true}) {
                for (int coordinate : new int[] {-17, -16, -1, 0, 15, 16}) {
                    Box area = new Box(coordinate, coordinate + 0.999D, coordinate, coordinate + 0.999D,
                        coordinate, coordinate + 0.999D);
                    ApertureCells aperture = new ApertureCells();
                    aperture.setArea(area);
                    Vec3d origin = area.center();
                    Frame frame = Frame.canonical(facing);
                    ViewPlateKey key = new ViewPlateKey(UUID.randomUUID(), view, front, 0, 0L);
                    ViewPlateBuilder.Request<String, String, ClientSweepScene.SceneView> request = new ViewPlateBuilder.Request<>(
                        key, aperture, view, frame, Frame.canonical(Face.N), origin.x(), origin.y(), origin.z(),
                        200.4995D, 70.4995D, 90.4995D, false, 0, 2, 2, 0, false, "minecraft:air", LodPolicy.NONE,
                        false, 0L, 0L, 0L, new ClientSweepScene.StringBlocks());
                    BlockBox clip = new BlockBox((coordinate >> 4) << 4, (coordinate >> 4) << 4, (coordinate >> 4) << 4, 16, 16, 16);
                    ViewPlateBuilder.Job<String, Object> job = ViewPlateBuilder.sectionJob(request, clip);
                    assertTrue(job.step(Integer.MAX_VALUE));
                    String context = facing + " front=" + front + " coordinate=" + coordinate;
                    long planeKey = CellKeys.pack(coordinate, coordinate, coordinate);
                    PlateCell<String> cell = job.result().cell(planeKey);
                    assertNotNull(cell, context);
                    assertEquals(Sample.Kind.BLOCK, cell.kind(), context);
                    assertEquals("minecraft:stone", cell.sourceData(), context);
                    int eyeSide = front ? 1 : -1;
                    assertNull(job.result().cell(CellKeys.pack(coordinate + facing.x() * eyeSide,
                        coordinate + facing.y() * eyeSide, coordinate + facing.z() * eyeSide)), context);
                    assertNull(ViewPlateBuilder.build(request).cell(planeKey), context);
                }
            }
        }
    }
    @Test
    void incompleteRemoteMetadataWaitsBeforeCapturingBlocks() {
        ClientSweepScene.SceneView view = spy(new ClientSweepScene.SceneView(UUID.randomUUID(), (x, y, z) -> "minecraft:stone"));
        when(view.sampleBiome(anyInt(), anyInt(), anyInt())).thenReturn(null);
        ApertureCells aperture = new ApertureCells();
        aperture.setArea(new Box(0, 1, 0, 1, 0, 1));
        Frame frame = Frame.canonical(Face.N);
        ViewPlateKey key = new ViewPlateKey(UUID.randomUUID(), view, true, 0, 0L);
        ViewPlateBuilder.Request<String, String, ClientSweepScene.SceneView> request = new ViewPlateBuilder.Request<>(
            key, aperture, view, frame, frame, 0.5D, 0.5D, 0.5D, 200.5D, 70.5D, 90.5D,
            false, 0, 32, 32, 0, false, "minecraft:air", LodPolicy.NONE, false, 0L, 0L, 0L,
            new ClientSweepScene.StringBlocks());
        ViewPlateBuilder.Job<String, Object> job = ViewPlateBuilder.sectionJob(request, new BlockBox(0, 0, 0, 16, 16, 16));
        assertFalse(job.step(4096));
        assertNull(job.result());
        verify(view, never()).sampleBlockData(anyInt(), anyInt(), anyInt());
        when(view.sampleBiome(anyInt(), anyInt(), anyInt())).thenReturn("test:destination");
        when(view.getLight(anyInt(), anyInt(), anyInt())).thenReturn(ContentView.LIGHT_UNAVAILABLE);
        assertFalse(job.step(4096));
        assertNull(job.result());
        when(view.getLight(anyInt(), anyInt(), anyInt())).thenReturn(ContentView.packLight(11, 2));
        assertTrue(job.step(4096));
        assertNotNull(job.result().environment());
        assertEquals("test:destination", job.result().environment().biomes().biome(0));
        assertTrue(job.result().cellCount() > 0);
    }

}
