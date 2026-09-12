package art.arcane.wormholes.render.blockentity;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.github.retrooper.packetevents.protocol.nbt.NBT;
import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;
import com.github.retrooper.packetevents.protocol.nbt.NBTList;

/**
 * Reduces a block entity tag to what the client needs to draw it: position and identity fields are
 * dropped, container inventories, loot tables and locks are stripped, and the result is capped at
 * {@link BlockEntitySample#MAX_NBT_BYTES}. Container contents never cross; the containers flag only
 * decides whether container block-entity types are projected at all (name, appearance).
 */
public final class BlockEntitySanitizer {
    private static final Set<String> ALWAYS_STRIPPED = Set.of("id", "x", "y", "z", "keepPacked", "Lock", "lock",
        "LootTable", "LootTableSeed", "loot_table", "loot_table_seed");
    private static final Set<String> CONTENT_TAGS = Set.of("Items", "Inventory", "item", "RecordItem", "Book");

    private BlockEntitySanitizer() {
    }

    public static BlockEntitySample sanitize(String typeKey, byte[] nbt, List<String> whitelist, boolean containers) {
        if (typeKey == null || nbt == null) {
            return null;
        }
        NBTCompound compound;
        try {
            compound = BlockEntityNbt.decode(nbt);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
        return sanitize(typeKey, compound, whitelist, containers);
    }

    public static BlockEntitySample sanitize(String typeKey, NBTCompound tag, List<String> whitelist, boolean containers) {
        if (typeKey == null || tag == null || !BlockEntityMaterials.allowed(typeKey, whitelist, containers)) {
            return null;
        }
        NBTCompound stripped = new NBTCompound();
        for (String name : new ArrayList<String>(tag.getTagNames())) {
            if (ALWAYS_STRIPPED.contains(name)) {
                continue;
            }
            NBT value = tag.getTagOrNull(name);
            if (value == null) {
                continue;
            }
            if (CONTENT_TAGS.contains(name) || isSlotList(value)) {
                continue;
            }
            stripped.setTag(name, value);
        }
        byte[] encoded;
        try {
            encoded = BlockEntityNbt.encode(stripped);
        } catch (IOException | RuntimeException unwritable) {
            return null;
        }
        if (encoded.length > BlockEntitySample.MAX_NBT_BYTES) {
            return null;
        }
        return new BlockEntitySample(typeKey, encoded);
    }

    private static boolean isSlotList(NBT value) {
        if (!(value instanceof NBTList<?> list) || list.isEmpty()) {
            return false;
        }
        for (NBT element : list.getTags()) {
            if (element instanceof NBTCompound compound && compound.contains("Slot")) {
                return true;
            }
        }
        return false;
    }
}
