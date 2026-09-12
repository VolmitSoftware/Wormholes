package art.arcane.wormholes.render.blockentity;

import java.util.List;

import org.bukkit.Material;

/** Maps block materials to the block-entity type key the client expects in a block-entity data packet. */
public final class BlockEntityMaterials {
    private BlockEntityMaterials() {
    }

    public static boolean isCandidate(Material material) {
        return typeKey(material) != null;
    }

    public static String typeKey(Material material) {
        if (material == null) {
            return null;
        }
        String name = material.name();
        if (name.endsWith("_HANGING_SIGN")) {
            return "minecraft:hanging_sign";
        }
        if (name.endsWith("_SIGN")) {
            return "minecraft:sign";
        }
        if (name.endsWith("_BANNER")) {
            return "minecraft:banner";
        }
        if (name.endsWith("_HEAD") || name.endsWith("_SKULL")) {
            return "minecraft:skull";
        }
        return switch (name) {
            case "DECORATED_POT" -> "minecraft:decorated_pot";
            case "BELL" -> "minecraft:bell";
            case "SPAWNER" -> "minecraft:mob_spawner";
            case "CHEST", "TRAPPED_CHEST" -> "minecraft:chest";
            case "BARREL" -> "minecraft:barrel";
            case "FURNACE" -> "minecraft:furnace";
            case "BLAST_FURNACE" -> "minecraft:blast_furnace";
            case "SMOKER" -> "minecraft:smoker";
            case "HOPPER" -> "minecraft:hopper";
            case "DISPENSER" -> "minecraft:dispenser";
            case "DROPPER" -> "minecraft:dropper";
            case "BREWING_STAND" -> "minecraft:brewing_stand";
            default -> name.endsWith("SHULKER_BOX") ? "minecraft:shulker_box" : null;
        };
    }

    public static boolean isContainerType(String typeKey) {
        return switch (typeKey) {
            case "minecraft:chest", "minecraft:barrel", "minecraft:furnace", "minecraft:blast_furnace",
                 "minecraft:smoker", "minecraft:hopper", "minecraft:dispenser", "minecraft:dropper",
                 "minecraft:brewing_stand", "minecraft:shulker_box" -> true;
            default -> false;
        };
    }

    public static boolean allowed(String typeKey, List<String> whitelist, boolean containers) {
        if (typeKey == null || whitelist == null) {
            return false;
        }
        if (isContainerType(typeKey) && !containers) {
            return false;
        }
        return whitelist.contains(typeKey);
    }
}
