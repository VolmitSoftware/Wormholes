package art.arcane.wormholes.render;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.PortalCellAperture;
import art.arcane.wormholes.util.AxisAlignedBB;
import org.bukkit.World;

import java.util.List;
import java.util.function.Supplier;

public final class BukkitProjectorPortalAccess implements ProjectorRecursivePortals.PortalAccess<World, ILocalPortal> {
    private final Supplier<List<ILocalPortal>> source;

    public BukkitProjectorPortalAccess(Supplier<List<ILocalPortal>> source) {
        this.source = source;
    }

    public static ProjectorRecursivePortals<World, ILocalPortal> create() {
        return create(() -> Wormholes.portalManager == null ? List.of() : Wormholes.portalManager.getLocalPortals());
    }

    public static ProjectorRecursivePortals<World, ILocalPortal> create(Supplier<List<ILocalPortal>> source) {
        return new ProjectorRecursivePortals<>(new BukkitProjectorPortalAccess(source),
            () -> new ProjectorRecursivePortals.Options(Settings.PROJECTION_APERTURE_PADDING_BLOCKS, Settings.PROJECTION_DEPTH_BLOCKS));
    }

    @Override
    public List<ILocalPortal> portals() {
        return source.get();
    }

    @Override
    public World world(ILocalPortal portal) {
        return portal.getWorld();
    }

    @Override
    public PortalCellAperture structure(ILocalPortal portal) {
        return portal.getStructure();
    }

    @Override
    public AxisAlignedBB view(ILocalPortal portal) {
        return portal.getView();
    }

    @Override
    public boolean eligible(ILocalPortal portal) {
        return portal.supportsProjections() && portal.isProjecting() && portal.isOpen() && !portal.blocksProjection();
    }

    @Override
    public boolean mirror(ILocalPortal portal) {
        return portal.isMirrorMode();
    }

    @Override
    public int mirrorQuarterTurns(ILocalPortal portal) {
        return portal.getMirrorRotation().getQuarterTurns();
    }

    @Override
    public ILocalPortal destination(ILocalPortal portal) {
        ITunnel tunnel = portal.getTunnel();
        return tunnel != null && tunnel.getDestination() instanceof ILocalPortal destination ? destination : null;
    }
}
