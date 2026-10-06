package art.arcane.wormholes.render;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.Settings;
import art.arcane.optics.fidelity.BlockEntityMaterials;
import art.arcane.wormholes.render.view.OccludedMarker;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.util.Objects;
import java.util.function.Function;
import org.bukkit.World;
import art.arcane.wormholes.portal.ILocalPortal;
import java.util.function.Predicate;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.state.StateProperties;
import art.arcane.optics.state.StateRewriteCache;
import art.arcane.optics.recursion.RecursiveEndpoints;
import art.arcane.optics.scan.CellScan;
import art.arcane.optics.scan.ProjectorSampleMemo;
import art.arcane.optics.scan.ProjectorSampler;
import art.arcane.optics.view.BlockStates;

public final class BukkitProjectorBlocks implements BlockStates<BlockData, Material> {
    private static final byte UNKNOWN = 0;
    private static final byte UNTOUCHED = 1;
    private static final byte REWRITTEN = 2;
    private static final int MATERIALS = Material.values().length;
    private static final StateRewriteCache<BlockData> REWRITES =
        new StateRewriteCache<BlockData>(BukkitProjectorBlocks::readProperties, BukkitProjectorBlocks::writeProperties);
    private static final BukkitProjectorBlocks DEFAULTS =
        new BukkitProjectorBlocks(material -> material != null && !ProjectionWorldView.isAir(material) && material.isOccluding());

    private final Predicate<Material> occlusion;
    private final byte[] rewrittenMaterials;

    public BukkitProjectorBlocks(Predicate<Material> occlusion) {
        this.occlusion = Objects.requireNonNull(occlusion);
        this.rewrittenMaterials = new byte[MATERIALS];
    }

    public static BukkitProjectorBlocks defaults() {
        return DEFAULTS;
    }

    public static ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo() {
        return new ProjectorSampleMemo<BlockData, Material, ProjectionWorldView>(DEFAULTS, () -> Wormholes.projectionChangeTracker);
    }

    public static ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo(Predicate<Material> occlusion) {
        return new ProjectorSampleMemo<BlockData, Material, ProjectionWorldView>(
            new BukkitProjectorBlocks(occlusion), () -> Wormholes.projectionChangeTracker);
    }

    public static ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler(
        ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo,
        RecursiveEndpoints<World, ILocalPortal> recursivePortals,
        Function<World, ProjectionWorldView> viewLookup) {
        return new ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView>(
            new ProjectorSampler.Options<BlockData, Material, World, ILocalPortal, ProjectionWorldView>(
                memo, recursivePortals, viewLookup, ProjectionWorldView::getWorld));
    }

    static CellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan(
        ILocalPortal portal,
        ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler,
        ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo, ProjectorBlackoutSeal blackout) {
        return new CellScan<>(new CellScan.Context<>(portal, portal.getStructure(), sampler, memo, blackout,
            OccludedMarker::isOccluding, () -> new CellScan.ScanSettings(Settings.PROJECTION_RECURSIVE_PORTAL_DEPTH,
                Settings.PROJECTION_OCCLUSION_REVEAL_MARGIN_DEGREES, Settings.PROJECTION_APERTURE_PADDING_BLOCKS, Settings.DEBUG,
                Settings.PROJECTION_HOLD_INVISIBLE_CLAIMS, Settings.PROJECTION_MAX_HELD_CELLS_PER_PORTAL, Settings.PROJECTION_FINISH_IN_SLOT)));
    }

    @Override
    public BlockData air() {
        return Material.AIR.createBlockData();
    }

    @Override
    public BlockData occluded() {
        return OccludedMarker.standIn();
    }

    @Override
    public boolean isOccluded(BlockData block) {
        return OccludedMarker.isStandIn(block);
    }

    @Override
    public Material material(BlockData block) {
        return block.getMaterial();
    }

    @Override
    public String materialName(Material material) {
        return material.name();
    }

    @Override
    public boolean blockEntityCandidate(Material material) {
        return material != null && BlockEntityMaterials.isCandidate(material.name());
    }

    @Override
    public boolean isAir(Material material) {
        return ProjectionWorldView.isAir(material);
    }

    @Override
    public boolean isOccluding(Material material) {
        return occlusion.test(material);
    }

    @Override
    public boolean requiresTransform(BlockData block) {
        int material = block.getMaterial().ordinal();
        byte known = rewrittenMaterials[material];
        if (known != UNKNOWN) {
            return known == REWRITTEN;
        }
        boolean rewritten = REWRITES.rewrites(block);
        rewrittenMaterials[material] = rewritten ? REWRITTEN : UNTOUCHED;
        return rewritten;
    }

    @Override
    public BlockData transform(BlockData block, AxisPermutation permutation) {
        return REWRITES.apply(block, permutation);
    }

    @Override
    public StateProperties properties(BlockData block) {
        return readProperties(block);
    }

    @Override
    public BlockData withProperties(BlockData block, StateProperties properties) {
        return writeProperties(block, properties);
    }

    private static StateProperties readProperties(BlockData block) {
        String state = block.getAsString();
        int open = state.indexOf('[');
        return open < 0 ? StateProperties.EMPTY : StateProperties.parse(state.substring(open));
    }

    private static BlockData writeProperties(BlockData block, StateProperties properties) {
        String state = block.getAsString();
        int open = state.indexOf('[');
        if (open < 0) {
            return block;
        }
        String key = state.substring(0, open);
        StateProperties accepted = readProperties(block);
        BlockData result = block;
        for (int index = 0; index < accepted.size(); index++) {
            String value = properties.get(accepted.name(index));
            StateProperties candidate = value == null ? accepted : accepted.with(accepted.name(index), value);
            if (candidate == accepted) {
                continue;
            }
            try {
                result = Bukkit.createBlockData(key.concat(candidate.toString()));
                accepted = candidate;
            } catch (IllegalArgumentException unsupported) {
                continue;
            }
        }
        return result;
    }
}
