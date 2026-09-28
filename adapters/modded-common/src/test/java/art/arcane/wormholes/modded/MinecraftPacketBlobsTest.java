package art.arcane.wormholes.modded;

import net.minecraft.SharedConstants;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class MinecraftPacketBlobsTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void metadataUsesVanillaSerializerIdsAndTerminator() {
        MinecraftPacketBlobs codec = new MinecraftPacketBlobs(RegistryAccess.EMPTY);
        byte[] wire = {0, 0, 32, (byte) 255};
        List<SynchedEntityData.DataValue<?>> decoded = codec.readMetadata(wire);
        assertEquals(1, decoded.size());
        assertEquals(0, decoded.getFirst().id());
        assertSame(EntityDataSerializers.BYTE, decoded.getFirst().serializer());
        assertEquals((byte) 32, decoded.getFirst().value());
        assertArrayEquals(wire, codec.writeMetadata(decoded));
    }

    @Test
    public void equipmentUsesSharedSlotOrderIncludingBodyAndSaddle() {
        MinecraftPacketBlobs codec = new MinecraftPacketBlobs(RegistryAccess.EMPTY);
        byte[] wire = {8, 0, 0, 1, 0, 2, 0, 3, 0, 4, 0, 5, 0, 6, 0, 7, 0};
        List<MinecraftPacketBlobs.Equipment> decoded = codec.readEquipment(wire);
        assertEquals(List.of(EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND, EquipmentSlot.FEET,
            EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD, EquipmentSlot.BODY,
            EquipmentSlot.SADDLE), decoded.stream().map(MinecraftPacketBlobs.Equipment::slot).toList());
        for (MinecraftPacketBlobs.Equipment piece : decoded) {
            assertTrue(piece.item().isEmpty());
        }
        assertArrayEquals(wire, codec.writeEquipment(decoded));
        assertArrayEquals(new byte[] {1, 7, 0}, codec.writeEquipment(List.of(
            new MinecraftPacketBlobs.Equipment(EquipmentSlot.SADDLE, ItemStack.EMPTY))));
    }

    @Test
    public void emptyBlobsProduceNoEntityOverrides() {
        MinecraftPacketBlobs codec = new MinecraftPacketBlobs(RegistryAccess.EMPTY);
        assertTrue(codec.readMetadata(new byte[0]).isEmpty());
        assertTrue(codec.readEquipment(null).isEmpty());
        assertArrayEquals(new byte[0], codec.writeEquipment(List.of()));
        assertArrayEquals(new byte[0], codec.writeMetadata(List.of()));
    }
}
