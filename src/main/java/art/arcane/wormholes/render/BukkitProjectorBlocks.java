package art.arcane.wormholes.render;

import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.Settings;
import art.arcane.wormholes.render.blockentity.BlockEntityMaterials;
import art.arcane.wormholes.render.view.OccludedMarker;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.util.Objects;
import java.util.function.Function;
import org.bukkit.World;
import art.arcane.wormholes.portal.ILocalPortal;
import java.util.function.Predicate;

public final class BukkitProjectorBlocks implements ProjectionBlockTypes<BlockData, Material> {
    private final Predicate<Material> occlusion;

    public BukkitProjectorBlocks(Predicate<Material> occlusion) {
        this.occlusion = Objects.requireNonNull(occlusion);
    }

    public static BukkitProjectorBlocks defaults() {
        return new BukkitProjectorBlocks(material -> material != null && !ProjectionWorldView.isAir(material) && material.isOccluding());
    }

    public static ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo() {
        return memo(material -> material != null && !ProjectionWorldView.isAir(material) && material.isOccluding());
    }

    public static ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo(Predicate<Material> occlusion) {
        return new ProjectorSampleMemo<BlockData, Material, ProjectionWorldView>(
            new BukkitProjectorBlocks(occlusion), () -> Wormholes.projectionChangeTracker);
    }

    public static ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler(
        ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo,
        ProjectorRecursivePortals<World, ILocalPortal> recursivePortals,
        Function<World, ProjectionWorldView> viewLookup) {
        return new ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView>(
            new ProjectorSampler.Options<BlockData, Material, World, ILocalPortal, ProjectionWorldView>(
                memo, recursivePortals, viewLookup, ProjectionWorldView::getWorld));
    }

    static ProjectorCellScan<BlockData, Material, World, ILocalPortal, ProjectionWorldView> scan(
        ILocalPortal portal,
        ProjectorSampler<BlockData, Material, World, ILocalPortal, ProjectionWorldView> sampler,
        ProjectorSampleMemo<BlockData, Material, ProjectionWorldView> memo, ProjectorBlackoutSeal blackout) {
        return new ProjectorCellScan<>(new ProjectorCellScan.Context<>(portal, portal.getStructure(), sampler, memo, blackout,
            OccludedMarker::isOccluding, () -> new ProjectorCellScan.ScanSettings(Settings.PROJECTION_RECURSIVE_PORTAL_DEPTH,
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
        return ProjectedBlockDataTransformer.requiresTransform(block);
    }

    @Override
    public BlockData transform(BlockData block, DirectionMapping mapping) {
        return ProjectedBlockDataTransformer.transform(block, mapping);
    }
}
