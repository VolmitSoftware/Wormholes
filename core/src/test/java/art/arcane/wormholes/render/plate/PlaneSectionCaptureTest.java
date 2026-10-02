package art.arcane.wormholes.render.plate;

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

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalGeometry;
import art.arcane.wormholes.render.ProjectionCellKey;
import art.arcane.wormholes.render.ProjectorSample;
import art.arcane.wormholes.render.client.ClientSweepScene;
import art.arcane.wormholes.render.lod.LodPolicy;
import art.arcane.wormholes.render.view.ProjectionContentView;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;
import org.junit.jupiter.api.Test;

final class PlaneSectionCaptureTest {
    @Test
    void meshSectionsCaptureThePlaneBlockWithoutChangingPacketClearance() {
        ClientSweepScene.ContentView view = new ClientSweepScene.ContentView(UUID.randomUUID(), (x, y, z) -> "minecraft:stone");
        for (Direction facing : Direction.values()) {
            for (boolean front : new boolean[] {false, true}) {
                for (int coordinate : new int[] {-17, -16, -1, 0, 15, 16}) {
                    AxisAlignedBB area = new AxisAlignedBB(coordinate, coordinate + 0.999D, coordinate, coordinate + 0.999D,
                        coordinate, coordinate + 0.999D);
                    PortalGeometry aperture = new PortalGeometry();
                    aperture.setArea(area);
                    GeometryVector origin = area.center();
                    PortalFrame frame = PortalFrame.canonical(facing);
                    ViewPlateKey key = new ViewPlateKey(UUID.randomUUID(), view, front, 0, 0L);
                    ViewPlateBuilder.Request<String, String, ClientSweepScene.ContentView> request = new ViewPlateBuilder.Request<>(
                        key, aperture, view, frame, PortalFrame.canonical(Direction.N), origin.x(), origin.y(), origin.z(),
                        200.4995D, 70.4995D, 90.4995D, false, 0, 2, 2, 0, false, "minecraft:air", LodPolicy.NONE,
                        false, 0L, 0L, 0L, new ClientSweepScene.StringBlocks());
                    PlateBox clip = new PlateBox((coordinate >> 4) << 4, (coordinate >> 4) << 4, (coordinate >> 4) << 4, 16, 16, 16);
                    ViewPlateBuilder.Job<String, Object> job = ViewPlateBuilder.sectionJob(request, clip);
                    assertTrue(job.step(Integer.MAX_VALUE));
                    String context = facing + " front=" + front + " coordinate=" + coordinate;
                    long planeKey = ProjectionCellKey.pack(coordinate, coordinate, coordinate);
                    PlateCell<String> cell = job.result().cell(planeKey);
                    assertNotNull(cell, context);
                    assertEquals(ProjectorSample.Kind.BLOCK, cell.kind(), context);
                    assertEquals("minecraft:stone", cell.sourceData(), context);
                    int eyeSide = front ? 1 : -1;
                    assertNull(job.result().cell(ProjectionCellKey.pack(coordinate + facing.x() * eyeSide,
                        coordinate + facing.y() * eyeSide, coordinate + facing.z() * eyeSide)), context);
                    assertNull(ViewPlateBuilder.build(request).cell(planeKey), context);
                }
            }
        }
    }
    @Test
    void incompleteRemoteMetadataWaitsBeforeCapturingBlocks() {
        ClientSweepScene.ContentView view = spy(new ClientSweepScene.ContentView(UUID.randomUUID(), (x, y, z) -> "minecraft:stone"));
        when(view.sampleBiome(anyInt(), anyInt(), anyInt())).thenReturn(null);
        PortalGeometry aperture = new PortalGeometry();
        aperture.setArea(new AxisAlignedBB(0, 1, 0, 1, 0, 1));
        PortalFrame frame = PortalFrame.canonical(Direction.N);
        ViewPlateKey key = new ViewPlateKey(UUID.randomUUID(), view, true, 0, 0L);
        ViewPlateBuilder.Request<String, String, ClientSweepScene.ContentView> request = new ViewPlateBuilder.Request<>(
            key, aperture, view, frame, frame, 0.5D, 0.5D, 0.5D, 200.5D, 70.5D, 90.5D,
            false, 0, 32, 32, 0, false, "minecraft:air", LodPolicy.NONE, false, 0L, 0L, 0L,
            new ClientSweepScene.StringBlocks());
        ViewPlateBuilder.Job<String, Object> job = ViewPlateBuilder.sectionJob(request, new PlateBox(0, 0, 0, 16, 16, 16));
        assertFalse(job.step(4096));
        assertNull(job.result());
        verify(view, never()).sampleBlockData(anyInt(), anyInt(), anyInt());
        when(view.sampleBiome(anyInt(), anyInt(), anyInt())).thenReturn("test:destination");
        when(view.getLight(anyInt(), anyInt(), anyInt())).thenReturn(ProjectionContentView.LIGHT_UNAVAILABLE);
        assertFalse(job.step(4096));
        assertNull(job.result());
        when(view.getLight(anyInt(), anyInt(), anyInt())).thenReturn(ProjectionContentView.packLight(11, 2));
        assertTrue(job.step(4096));
        assertNotNull(job.result().environment());
        assertEquals("test:destination", job.result().environment().biomes().biome(0));
        assertTrue(job.result().cellCount() > 0);
    }

}
