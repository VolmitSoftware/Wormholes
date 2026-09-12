package art.arcane.wormholes.render.lod;

import org.bukkit.Material;

import art.arcane.wormholes.render.FidelitySettings;

/**
 * Level-of-detail rules applied along the view axis. Depth is the slab index counted from the portal
 * plane outward. Run merging makes every second slab past {@code distanceBlocks} reuse the previous
 * slab's samples; the detail cutoff drops thin decorative blocks past {@code detailCutoffBlocks}.
 */
public final class LodPolicy {
    public static final LodPolicy NONE = new LodPolicy(false, Integer.MAX_VALUE, Integer.MAX_VALUE);

    private final boolean mergeRuns;
    private final int distanceBlocks;
    private final int detailCutoffBlocks;

    public LodPolicy(boolean mergeRuns, int distanceBlocks, int detailCutoffBlocks) {
        this.mergeRuns = mergeRuns;
        this.distanceBlocks = Math.max(0, distanceBlocks);
        this.detailCutoffBlocks = Math.max(0, detailCutoffBlocks);
    }

    public static LodPolicy current(LodProfile profile) {
        LodProfile active = profile == null ? LodProfile.BALANCED : profile;
        double scale = active.distanceScale();
        int distance = scaled(FidelitySettings.lodDistanceBlocks, scale);
        int cutoff = scaled(FidelitySettings.lodDetailCutoffBlocks, scale);
        return new LodPolicy(FidelitySettings.lodMergeRuns, distance, cutoff);
    }

    public boolean mergeRuns() {
        return mergeRuns;
    }

    public int distanceBlocks() {
        return distanceBlocks;
    }

    public int detailCutoffBlocks() {
        return detailCutoffBlocks;
    }

    /** The same distances with run merging forced on; used when the cell budget would otherwise empty the view. */
    public LodPolicy withMergeRuns() {
        return mergeRuns ? this : new LodPolicy(true, distanceBlocks, detailCutoffBlocks);
    }

    public boolean isNone() {
        return !mergeRuns && detailCutoffBlocks == Integer.MAX_VALUE;
    }

    /** Whole blocks past the portal plane clearance for a cell at the given signed plane distance. */
    public static int depthIndex(double cellDot, double clearance) {
        return (int) Math.max(0.0D, Math.floor(Math.abs(cellDot) - clearance));
    }

    public boolean mergesSlab(int depth) {
        return mergeRuns && depth > distanceBlocks && ((depth - distanceBlocks) & 1) == 1;
    }

    public boolean dropsDetail(int depth, Material material) {
        return depth > detailCutoffBlocks && isDetail(material);
    }

    /** Candidate work after merging: near slabs stay dense, far slabs sample every second slab. */
    public long coarsenedWork(long work, double axialBlocks) {
        if (!mergeRuns || axialBlocks <= distanceBlocks || work <= 0L) {
            return work;
        }
        double nearFraction = distanceBlocks / axialBlocks;
        long near = (long) Math.ceil(work * nearFraction);
        long far = work - near;
        return near + ((far + 1L) / 2L);
    }

    public static boolean isDetail(Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        if (name.endsWith("_FENCE") || name.endsWith("_FENCE_GATE") || name.endsWith("_PANE")
            || name.endsWith("_SAPLING") || name.endsWith("_BUSH") || name.endsWith("FERN")
            || name.endsWith("_TULIP") || name.endsWith("_CARPET") || name.endsWith("_PRESSURE_PLATE")
            || name.endsWith("_BUTTON") || name.endsWith("_TORCH") || name.endsWith("_ROOTS")) {
            return true;
        }
        return switch (name) {
            case "SHORT_GRASS", "TALL_GRASS", "SEAGRASS", "TALL_SEAGRASS", "DEAD_BUSH", "POPPY", "DANDELION",
                 "BLUE_ORCHID", "ALLIUM", "AZURE_BLUET", "OXEYE_DAISY", "CORNFLOWER", "LILY_OF_THE_VALLEY",
                 "WITHER_ROSE", "TORCHFLOWER", "SUNFLOWER", "LILAC", "ROSE_BUSH", "PEONY", "PINK_PETALS",
                 "VINE", "KELP", "KELP_PLANT", "IRON_BARS", "CHAIN", "TORCH", "LEVER", "TRIPWIRE",
                 "TRIPWIRE_HOOK", "COBWEB", "GLOW_LICHEN", "HANGING_ROOTS", "SPORE_BLOSSOM", "SWEET_BERRY_BUSH",
                 "BAMBOO", "SUGAR_CANE", "LADDER", "RAIL", "POWERED_RAIL", "DETECTOR_RAIL", "ACTIVATOR_RAIL",
                 "REDSTONE_WIRE", "SNOW", "MOSS_CARPET" -> true;
            default -> false;
        };
    }

    private static int scaled(int blocks, double scale) {
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, Math.round(blocks * scale)));
    }
}
