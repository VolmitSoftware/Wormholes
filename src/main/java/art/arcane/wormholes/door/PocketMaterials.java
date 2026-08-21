package art.arcane.wormholes.door;

import org.bukkit.Material;
import org.bukkit.block.data.type.Door;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/** Resolves and vets the materials a pocket shell may be built from. */
public final class PocketMaterials {
    /**
     * Short palette offered before an operator types anything. Every usable
     * block is still accepted and still suggested once there is a prefix to
     * search on; a bare tab listing every solid block in the game is unusable.
     */
    private static final List<String> COMMON_SHELL_MATERIALS = List.of(
        "SMOOTH_STONE",
        "STONE_BRICKS",
        "DEEPSLATE_BRICKS",
        "POLISHED_BLACKSTONE_BRICKS",
        "OBSIDIAN",
        "SMOOTH_QUARTZ",
        "END_STONE_BRICKS",
        "SEA_LANTERN"
    );

    private static final List<String> COMMON_RETURN_DOOR_MATERIALS = List.of(
        "CRIMSON_DOOR",
        "WARPED_DOOR",
        "OAK_DOOR",
        "SPRUCE_DOOR",
        "BIRCH_DOOR",
        "COPPER_DOOR"
    );

    private static final String DOOR_SUFFIX = "_DOOR";

    private PocketMaterials() {
    }

    /**
     * A shell block has to hold the room in: solid, not air, and not something
     * that falls away or evaporates the moment the room is entered.
     */
    public static boolean isUsableShellMaterial(Material material) {
        return material != null
            && material.isBlock()
            && material.isSolid()
            && !material.isAir()
            && !material.hasGravity();
    }

    /**
     * A pocket must never require redstone to escape, so the return door has to
     * be a hinged door a player can open by hand.
     */
    public static boolean isUsableReturnDoorMaterial(Material material) {
        if (material == null || !material.isBlock() || material == Material.IRON_DOOR) {
            return false;
        }
        try {
            return material.createBlockData() instanceof Door;
        } catch (IllegalArgumentException | UnsupportedOperationException unsupported) {
            return false;
        }
    }

    public static Optional<Material> shellMaterial(String name) {
        return resolve(name).filter(PocketMaterials::isUsableShellMaterial);
    }

    public static Optional<Material> returnDoorMaterial(String name) {
        return resolve(name).filter(PocketMaterials::isUsableReturnDoorMaterial);
    }

    /** Falls back to the built-in shell material rather than leaving a pocket unbuildable. */
    public static Material shellMaterialOrDefault(String name) {
        return shellMaterial(name).orElseGet(
            () -> Objects.requireNonNull(Material.matchMaterial(PocketShell.DEFAULT_SHELL_MATERIAL)));
    }

    public static Material returnDoorMaterialOrDefault(String name) {
        return returnDoorMaterial(name).orElseGet(
            () -> Objects.requireNonNull(Material.matchMaterial(PocketShell.DEFAULT_RETURN_DOOR_MATERIAL)));
    }

    /** Palette shown when no prefix has been typed yet. */
    public static List<String> commonShellMaterialNames() {
        return existing(COMMON_SHELL_MATERIALS, PocketMaterials::isUsableShellMaterial);
    }

    public static List<String> commonReturnDoorMaterialNames() {
        return existing(COMMON_RETURN_DOOR_MATERIALS, PocketMaterials::isUsableReturnDoorMaterial);
    }

    /** Every usable shell block whose name contains {@code input}. */
    public static List<String> shellMaterialNamesMatching(String input) {
        return matching(input, PocketMaterials::isUsableShellMaterial, material -> true);
    }

    /**
     * Every usable exit door whose name contains {@code input}. The name test
     * runs first so the expensive block-data check only sees door candidates.
     */
    public static List<String> returnDoorMaterialNamesMatching(String input) {
        return matching(
            input,
            PocketMaterials::isUsableReturnDoorMaterial,
            material -> material.name().endsWith(DOOR_SUFFIX));
    }

    private static List<String> existing(List<String> names, Predicate<Material> usable) {
        List<String> resolved = new ArrayList<>(names.size());
        for (String name : names) {
            Material material = Material.matchMaterial(name);
            if (material != null && usable.test(material)) {
                resolved.add(material.name());
            }
        }
        return List.copyOf(resolved);
    }

    private static List<String> matching(
        String input,
        Predicate<Material> usable,
        Predicate<Material> candidate
    ) {
        String needle = PocketShell.normalizeMaterial(input, "material");
        List<String> matches = new ArrayList<>();
        for (Material material : Material.values()) {
            if (!candidate.test(material) || !material.name().contains(needle)) {
                continue;
            }
            if (usable.test(material)) {
                matches.add(material.name());
            }
        }
        return List.copyOf(matches);
    }

    private static Optional<Material> resolve(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(Material.matchMaterial(PocketShell.normalizeMaterial(name, "material")));
    }
}
