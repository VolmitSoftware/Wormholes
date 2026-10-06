package art.arcane.wormholes.modded;

import art.arcane.optics.fidelity.BlockEntitySample;
import art.arcane.optics.fidelity.BlockEntitySanitizer;
import art.arcane.optics.math.CellKeys;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtIo;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class MinecraftBlockEntityTagsTest {
    @Test
    public void nativeTagsUseSharedInventoryAndIdentityStrippingPolicy() throws Exception {
        CompoundTag chest = new CompoundTag();
        chest.putString("id", "minecraft:chest");
        chest.putInt("x", 12);
        chest.putString("Lock", "private");
        chest.putString("LootTable", "minecraft:chests/example");
        chest.putString("CustomName", "Visible name");
        CompoundTag item = new CompoundTag();
        item.putByte("Slot", (byte) 0);
        ListTag inventory = new ListTag();
        inventory.add(item);
        chest.put("Items", inventory);
        chest.put("CustomSlots", inventory);
        BlockEntitySample sample = BlockEntitySanitizer.sanitize("minecraft:chest", chest,
            new BlockEntitySanitizer.Options<>(List.of("minecraft:chest"), true, MinecraftBlockEntityTags.INSTANCE));
        assertNotNull(sample);
        CompoundTag decoded = NbtIo.read(new DataInputStream(new ByteArrayInputStream(sample.nbt())));
        assertFalse(decoded.contains("id"));
        assertFalse(decoded.contains("x"));
        assertFalse(decoded.contains("Lock"));
        assertFalse(decoded.contains("LootTable"));
        assertFalse(decoded.contains("Items"));
        assertFalse(decoded.contains("CustomSlots"));
        assertTrue(decoded.contains("CustomName"));
        assertTrue(chest.contains("Items"));
    }

    @Test
    public void nativePacketUsesProjectedCoordinatesAndSanitizedTag() throws Exception {
        MinecraftTestBase.bootstrap();
        CompoundTag source = new CompoundTag();
        source.putString("id", "minecraft:chest");
        source.putInt("x", 400);
        source.putString("CustomName", "Visible name");
        source.put("Items", new ListTag());
        BlockEntitySample sample = BlockEntitySanitizer.sanitize("minecraft:chest", source,
            new BlockEntitySanitizer.Options<>(List.of("minecraft:chest"), true, MinecraftBlockEntityTags.INSTANCE));
        ClientboundBlockEntityDataPacket packet = MinecraftBlockEntityPackets.packet(
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY), CellKeys.pack(-33, -12, 65), sample);
        assertEquals(new BlockPos(-33, -12, 65), packet.getPos());
        assertSame(BuiltInRegistries.BLOCK_ENTITY_TYPE.getOptional(Identifier.parse("minecraft:chest")).orElseThrow(), packet.getType());
        assertTrue(packet.getTag().contains("CustomName"));
        assertFalse(packet.getTag().contains("Items"));
        assertFalse(packet.getTag().contains("x"));
        assertFalse(packet.getTag().contains("id"));
    }

    @Test
    public void whitelistContainerGateAndSizeCapMatchSharedSanitizer() {
        CompoundTag chest = new CompoundTag();
        assertNull(BlockEntitySanitizer.sanitize("minecraft:chest", chest,
            new BlockEntitySanitizer.Options<>(List.of("minecraft:chest"), false, MinecraftBlockEntityTags.INSTANCE)));
        assertNull(BlockEntitySanitizer.sanitize("minecraft:bell", chest,
            new BlockEntitySanitizer.Options<>(List.of("minecraft:sign"), false, MinecraftBlockEntityTags.INSTANCE)));
        CompoundTag skull = new CompoundTag();
        skull.putString("profile", "x".repeat(BlockEntitySample.MAX_NBT_BYTES));
        assertNull(BlockEntitySanitizer.sanitize("minecraft:skull", skull,
            new BlockEntitySanitizer.Options<>(List.of("minecraft:skull"), false, MinecraftBlockEntityTags.INSTANCE)));
    }
}
