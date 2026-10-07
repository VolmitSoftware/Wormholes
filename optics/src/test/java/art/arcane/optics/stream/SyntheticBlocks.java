package art.arcane.optics.stream;

import java.util.Locale;
import java.util.Set;

import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.state.StateProperties;
import art.arcane.optics.view.BlockStates;

final class SyntheticBlocks implements BlockStates<String, String> {
    static final SyntheticBlocks INSTANCE = new SyntheticBlocks();
    static final String OCCLUDED = "#occluded";
    private static final Set<String> BLOCK_ENTITY_MATERIALS = Set.of("chest", "barrel", "sign", "oak_sign", "lectern");
    private static final String[] OPEN = {"glass", "leaves", "water", "lava", "slab", "stairs", "fence", "_wall", "wall_", "door", "pane",
        "grass", "fern", "sapling", "torch", "vine", "kelp", "seagrass", "sugar_cane", "bamboo", "carpet", "rail", "sign", "button",
        "pressure_plate", "cactus", "cobweb", "lichen", "pointed_dripstone", "amethyst_cluster", "_bud", "bush", "roots", "azalea",
        "lantern", "chain", "ladder", "_bed", "chest", "sculk_vein", "sculk_sensor", "sculk_shrieker", "petals", "leaf_litter", "dandelion",
        "poppy", "orchid", "allium", "bluet", "tulip", "daisy", "cornflower", "lily", "rose", "peony", "lilac", "sunflower", "dripleaf",
        "spore_blossom", "bubble_column", "farmland", "dirt_path", "coral", "sea_pickle", "scaffolding", "honey_block", "slime_block",
        "hopper", "cauldron", "anvil", "campfire", "bell", "candle", "head", "skull", "banner", "pot", "composter", "lectern", "grindstone",
        "brewing", "enchanting", "end_rod", "lightning_rod", "iron_bars", "trapdoor", "tripwire", "redstone_wire", "repeater", "comparator",
        "daylight", "stonecutter", "conduit", "sprouts", "fungus", "frogspawn", "egg", "moss_carpet", "hanging_moss", "resin_clump",
        "eyeblossom", "wildflowers", "cave_vines", "mushroom", "snow", "ice", "berry", "wheat", "carrots", "potatoes", "beetroots", "melon_stem",
        "pumpkin_stem", "cocoa", "nether_wart", "frosted", "structure_void", "barrier", "light", "spawner", "beacon", "portal", "fire"};

    private SyntheticBlocks() {
    }

    @Override
    public String air() {
        return SyntheticWorld.AIR;
    }

    @Override
    public String occluded() {
        return OCCLUDED;
    }

    @Override
    public boolean isOccluded(String block) {
        return OCCLUDED.equals(block);
    }

    @Override
    public String material(String block) {
        return SyntheticWorld.materialOf(block);
    }

    @Override
    public String materialName(String material) {
        return material == null ? null : material.toUpperCase(Locale.ROOT);
    }

    @Override
    public boolean blockEntityCandidate(String material) {
        return BLOCK_ENTITY_MATERIALS.contains(material);
    }

    @Override
    public boolean isAir(String material) {
        return material == null || material.endsWith("air");
    }

    @Override
    public boolean isOccluding(String material) {
        if (material == null || isAir(material)) {
            return false;
        }
        if (material.equals("grass_block") || material.equals("snow_block") || material.equals("dripstone_block")
            || material.equals("moss_block") || material.equals("mud_bricks") || material.equals("honeycomb_block")) {
            return true;
        }
        for (String open : OPEN) {
            if (material.contains(open)) {
                return false;
            }
        }
        return true;
    }

    @Override
    public boolean occludes(String block) {
        return block != null && isOccluding(material(block));
    }

    @Override
    public boolean requiresTransform(String block) {
        return block.contains("facing=") || block.contains("axis=");
    }

    @Override
    public String transform(String block, AxisPermutation permutation) {
        return block;
    }

    @Override
    public StateProperties properties(String block) {
        return StateProperties.EMPTY;
    }

    @Override
    public String withProperties(String block, StateProperties properties) {
        return block;
    }
}
