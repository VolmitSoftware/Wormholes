package art.arcane.optics.fidelity;

import java.io.IOException;
import java.util.List;
import java.util.Set;


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

    public static <T> BlockEntitySample sanitize(String typeKey, T tag, Options<T> options) {
        if (typeKey == null || tag == null || !BlockEntityMaterials.allowed(typeKey, options.whitelist(), options.containers())) {
            return null;
        }
        TagAccess<T> access = options.access();
        T stripped = access.compound();
        for (String name : access.names(tag)) {
            if (ALWAYS_STRIPPED.contains(name)) {
                continue;
            }
            T value = access.get(tag, name);
            if (value == null) {
                continue;
            }
            if (CONTENT_TAGS.contains(name) || isSlotList(value, access)) {
                continue;
            }
            access.put(stripped, name, value);
        }
        byte[] encoded;
        try {
            encoded = access.encode(stripped);
        } catch (IOException | RuntimeException unwritable) {
            return null;
        }
        if (encoded.length > BlockEntitySample.MAX_NBT_BYTES) {
            return null;
        }
        return new BlockEntitySample(typeKey, encoded);
    }

    private static <T> boolean isSlotList(T value, TagAccess<T> access) {
        Iterable<? extends T> list = access.list(value);
        if (list == null) {
            return false;
        }
        for (T element : list) {
            if (access.contains(element, "Slot")) {
                return true;
            }
        }
        return false;
    }

    public record Options<T>(List<String> whitelist, boolean containers, TagAccess<T> access) {
    }

    public interface TagAccess<T> {
        Iterable<String> names(T compound);
        T get(T compound, String name);
        T compound();
        void put(T compound, String name, T value);
        Iterable<? extends T> list(T value);
        boolean contains(T compound, String name);
        byte[] encode(T compound) throws IOException;
    }
}
