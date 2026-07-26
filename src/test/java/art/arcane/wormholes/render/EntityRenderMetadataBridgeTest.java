package art.arcane.wormholes.render;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class EntityRenderMetadataBridgeTest {
    @Test
    void patchesPlayerSkinPartsWithoutReplacingAbsorptionMetadata() {
        ProjectedEntityPacketRecorder recorder = ProjectedEntityPacketRecorder.install();
        try {
            EntityData<Byte> skinParts = new EntityData<Byte>(16, EntityDataTypes.BYTE, Byte.valueOf((byte) 0x04));
            EntityData<Float> absorption = new EntityData<Float>(17, EntityDataTypes.FLOAT, Float.valueOf(6.5F));

            List<EntityData<?>> patched = EntityRenderMetadataBridge.withUpsideDownPlayerMetadata(
                List.of(skinParts, absorption));

            assertEquals(2, patched.size());
            EntityData<?> patchedSkinParts = dataAt(patched, 16);
            EntityData<?> preservedAbsorption = dataAt(patched, 17);
            assertInstanceOf(Byte.class, patchedSkinParts.getValue());
            assertEquals(Byte.valueOf((byte) 0x05), patchedSkinParts.getValue());
            assertInstanceOf(Float.class, preservedAbsorption.getValue());
            assertEquals(Float.valueOf(6.5F), preservedAbsorption.getValue());
        } finally {
            recorder.uninstall();
        }
    }

    private static EntityData<?> dataAt(List<EntityData<?>> metadata, int index) {
        for (EntityData<?> data : metadata) {
            if (data.getIndex() == index) {
                return data;
            }
        }
        throw new AssertionError("Missing metadata index " + index);
    }
}
