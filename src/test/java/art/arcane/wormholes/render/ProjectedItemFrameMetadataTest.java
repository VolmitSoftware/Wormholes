package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.util.Vector3d;

import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.entity.ItemFrameTransform;

public final class ProjectedItemFrameMetadataTest {
    @Test
    public void metadataTransformsDirectionWithoutMutatingTheCapturedList() {
        Frame sourceFrame = Frame.canonical(Face.N);
        Frame targetFrame = Frame.canonical(Face.U);
        int transform = ItemFrameTransform.of(Face.N,
            OpticTransform.between(sourceFrame, new Vec3d(0.0D, 0.0D, 0.0D), targetFrame, new Vec3d(0.0D, 0.0D, 0.0D)));
        EntityData<String> retained = new EntityData<String>(11, null, "retained");
        List<EntityData<?>> source = List.of(
            new EntityData<BlockFace>(8, null, BlockFace.NORTH),
            new EntityData<Integer>(10, null, Integer.valueOf(1)),
            retained);

        List<EntityData<?>> projected = EntityRenderMetadataBridge.FRAMES.transformMetadata(source, transform, null, false);

        assertEquals(BlockFace.UP, valueAt(projected, 8));
        assertEquals(Integer.valueOf(5), valueAt(projected, 10));
        assertSame(retained, projected.get(2));
        assertEquals(BlockFace.NORTH, valueAt(source, 8));
        assertEquals(Integer.valueOf(1), valueAt(source, 10));
    }

    private static Object valueAt(List<EntityData<?>> metadata, int index) {
        for (EntityData<?> data : metadata) {
            if (data.getIndex() == index) {
                return data.getValue();
            }
        }
        return null;
    }

}
