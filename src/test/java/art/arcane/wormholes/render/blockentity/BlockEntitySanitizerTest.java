package art.arcane.wormholes.render.blockentity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;

import com.github.retrooper.packetevents.protocol.nbt.NBTByte;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTInt;
import com.github.retrooper.packetevents.protocol.nbt.NBTList;
import com.github.retrooper.packetevents.protocol.nbt.NBTString;

import org.junit.jupiter.api.Test;

final class BlockEntitySanitizerTest {
    private static final List<String> WHITELIST = List.of("minecraft:sign", "minecraft:chest", "minecraft:skull");

    @Test
    void containerContentsLockAndLootTablesAreStrippedWhileAppearanceSurvives() throws IOException {
        NBTCompound chest = new NBTCompound();
        chest.setTag("id", new NBTString("minecraft:chest"));
        chest.setTag("x", new NBTInt(1));
        chest.setTag("y", new NBTInt(64));
        chest.setTag("z", new NBTInt(3));
        chest.setTag("Lock", new NBTString("secret"));
        chest.setTag("LootTable", new NBTString("minecraft:chests/simple_dungeon"));
        chest.setTag("LootTableSeed", new NBTInt(42));
        NBTList<NBTCompound> items = NBTList.createCompoundList();
        NBTCompound item = new NBTCompound();
        item.setTag("Slot", new NBTByte((byte) 0));
        item.setTag("id", new NBTString("minecraft:diamond"));
        items.addTag(item);
        chest.setTag("Items", items);
        chest.setTag("CustomName", new NBTString("{\"text\":\"Loot\"}"));

        BlockEntitySample sample = BlockEntitySanitizer.sanitize("minecraft:chest", chest, WHITELIST, true);
        assertNotNull(sample);
        NBTCompound decoded = BlockEntityNbt.decode(sample.nbt());
        assertFalse(decoded.contains("Items"));
        assertFalse(decoded.contains("Lock"));
        assertFalse(decoded.contains("LootTable"));
        assertFalse(decoded.contains("LootTableSeed"));
        assertFalse(decoded.contains("id"));
        assertFalse(decoded.contains("x"));
        assertEquals("{\"text\":\"Loot\"}", decoded.getStringTagValueOrThrow("CustomName"));

        assertNull(BlockEntitySanitizer.sanitize("minecraft:chest", chest, WHITELIST, false),
            "container types are refused entirely while block-entity-containers is off");
        NBTCompound withContainers = BlockEntityNbt.decode(
            BlockEntitySanitizer.sanitize("minecraft:chest", chest, WHITELIST, true).nbt());
        assertFalse(withContainers.contains("Items"), "contents never cross even when container types are admitted");
    }

    @Test
    void signTextAndHeadProfilesPassThroughAndTheWhitelistIsEnforced() throws IOException {
        NBTCompound sign = new NBTCompound();
        NBTCompound front = new NBTCompound();
        NBTList<NBTString> messages = NBTList.createStringList();
        messages.addTag(new NBTString("{\"text\":\"hello\"}"));
        front.setTag("messages", messages);
        front.setTag("color", new NBTString("black"));
        sign.setTag("front_text", front);
        sign.setTag("is_waxed", new NBTByte((byte) 0));

        BlockEntitySample sample = BlockEntitySanitizer.sanitize("minecraft:sign", sign, WHITELIST, false);
        assertNotNull(sample);
        assertEquals("minecraft:sign", sample.typeKey());
        NBTCompound decoded = BlockEntityNbt.decode(sample.nbt());
        assertEquals("{\"text\":\"hello\"}", decoded.getCompoundTagOrThrow("front_text").getStringListTagOrThrow("messages").getTag(0).getValue());

        assertNull(BlockEntitySanitizer.sanitize("minecraft:bell", sign, WHITELIST, false), "types outside the whitelist are dropped");
        assertNull(BlockEntitySanitizer.sanitize(null, sign, WHITELIST, false));
    }

    @Test
    void oversizedTagsAreDropped() {
        NBTCompound skull = new NBTCompound();
        skull.setTag("profile", new NBTString("x".repeat(BlockEntitySample.MAX_NBT_BYTES + 16)));
        assertNull(BlockEntitySanitizer.sanitize("minecraft:skull", skull, WHITELIST, false));
        NBTCompound small = new NBTCompound();
        small.setTag("profile", new NBTString("x".repeat(64)));
        BlockEntitySample sample = BlockEntitySanitizer.sanitize("minecraft:skull", small, WHITELIST, false);
        assertNotNull(sample);
        assertTrue(sample.nbt().length <= BlockEntitySample.MAX_NBT_BYTES);
    }
}
