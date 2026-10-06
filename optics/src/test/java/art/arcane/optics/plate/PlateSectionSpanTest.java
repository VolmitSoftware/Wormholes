package art.arcane.optics.plate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.UUID;

import art.arcane.optics.math.Vec3;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.client.ClientSweepScene;
import art.arcane.optics.volume.LodPolicy;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import org.junit.jupiter.api.Test;

final class PlateSectionSpanTest {
    private static final int DEPTH = 64;
    private static final int LATERAL_CLAMP = 40;
    private static final double APERTURE_PADDING = 0.75D;
    private static final int FRAME_CAP_BYTES = 1 << 20;

    @Test
    void theSweepBoundsHelperMatchesBuiltPlates() {
        ClientSweepScene.SceneView view = new ClientSweepScene.SceneView(UUID.fromString("00000000-0000-0000-0000-0000000000c1"),
            (x, y, z) -> y < 60 ? "minecraft:stone" : "minecraft:air");
        for (Face facing : new Face[] {Face.E, Face.U, Face.N, Face.D}) {
            for (int size : new int[] {1, 3, 5}) {
                for (boolean frontSide : new boolean[] {true, false}) {
                    Box area = area(facing, size, 3, 64, -7);
                    ApertureCells aperture = new ApertureCells();
                    aperture.setArea(area);
                    Frame frame = Frame.canonical(facing);
                    Vec3 origin = area.center();
                    ViewPlateKey key = new ViewPlateKey(UUID.fromString("00000000-0000-0000-0000-0000000000c2"), view, frontSide, 0, 0L);
                    ViewPlateBuilder.Request<String, String, ClientSweepScene.SceneView> request =
                        new ViewPlateBuilder.Request<String, String, ClientSweepScene.SceneView>(key, aperture, view, frame,
                            Frame.canonical(Face.S), origin.getX(), origin.getY(), origin.getZ(), 200.5D, 70.5D, 90.5D,
                            false, 0, 24, 12, APERTURE_PADDING, false, "minecraft:air", LodPolicy.NONE, false, 0L, 0L, 0L,
                            new ClientSweepScene.StringBlocks());
                    PlateBox built = ViewPlateBuilder.build(request).grid().box();
                    assertEquals(built, ClientSweepScene.plateBox(area, frame, origin, frontSide, 24, 12, APERTURE_PADDING),
                        facing + " size=" + size + " front=" + frontSide);
                }
            }
        }
    }

    @Test
    void aClampedPlateSpansAtMostSevenBySevenByFiveSections() {
        int worstLateral = 0;
        int worstNormal = 0;
        int worstBricks = 0;
        for (int offsetX = 0; offsetX < 16; offsetX++) {
            for (int offsetY = 0; offsetY < 16; offsetY++) {
                Box area = area(Face.E, 1, offsetX, 64 + offsetY, offsetY);
                Vec3 origin = area.center();
                PlateBox box = ClientSweepScene.plateBox(area, Frame.canonical(Face.E), origin, true, DEPTH, LATERAL_CLAMP,
                    APERTURE_PADDING);
                int sectionsX = sections(box.minX(), box.sizeX());
                int sectionsY = sections(box.minY(), box.sizeY());
                int sectionsZ = sections(box.minZ(), box.sizeZ());
                worstNormal = Math.max(worstNormal, sectionsX);
                worstLateral = Math.max(worstLateral, Math.max(sectionsY, sectionsZ));
                worstBricks = Math.max(worstBricks, sectionsX * sectionsY * sectionsZ);
                assertEquals(DEPTH, box.sizeX());
                assertEquals(1 + (2 * (LATERAL_CLAMP + 1)), box.sizeY());
            }
        }
        System.out.println(String.format(Locale.ROOT, "plate section span at lateral %d depth %d: %dx%dx%d sections, worst %d bricks",
            LATERAL_CLAMP, DEPTH, worstLateral, worstLateral, worstNormal, worstBricks));
        assertEquals(7, worstLateral);
        assertEquals(5, worstNormal);
        assertTrue(worstBricks <= 7 * 7 * 5);
    }

    @Test
    void aSingleBrickStaysFarBelowTheFrameCap() {
        int header = 2 + 1 + 1 + 1;
        int palette = 1 + (256 * 3);
        int indices = 4096;
        int light = 2048 + 2048;
        int blockEntity = 2 + 3 + 256 + BlockEntitySample.MAX_NBT_BYTES;
        long worst = header + palette + indices + light + 2L + ((long) PlateCaptureJob.MAX_BLOCK_ENTITIES_PER_CHUNK * blockEntity);
        System.out.println(String.format(Locale.ROOT, "worst single brick: %d bytes (%.1f KiB)", worst, worst / 1024.0D));
        assertTrue(worst < FRAME_CAP_BYTES / 4, "a brick must fit several times into one frame, worst " + worst);
    }

    private static int sections(int min, int size) {
        return ((min + size - 1) >> 4) - (min >> 4) + 1;
    }

    private static Box area(Face facing, int size, int x, int y, int z) {
        Frame frame = Frame.canonical(facing);
        int[] min = {x, y, z};
        int[] max = {x, y, z};
        max[axisOf(frame.getRight())] += size - 1;
        max[axisOf(frame.getUp())] += size - 1;
        return new Box(min[0], max[0] + 0.999D, min[1], max[1] + 0.999D, min[2], max[2] + 0.999D);
    }

    private static int axisOf(Face direction) {
        return direction.x() != 0 ? 0 : direction.y() != 0 ? 1 : 2;
    }
}
