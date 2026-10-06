package art.arcane.wormholes.render;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.optics.aperture.CellAperture;
import art.arcane.optics.math.Box;
import org.bukkit.World;

import java.util.List;
import java.util.function.Supplier;
import art.arcane.optics.recursion.RecursiveEndpoints;

public final class BukkitProjectorPortalAccess implements RecursiveEndpoints.PortalAccess<World, ILocalPortal> {
    private final Supplier<List<ILocalPortal>> source;

    public BukkitProjectorPortalAccess(Supplier<List<ILocalPortal>> source) {
        this.source = source;
    }

    public static RecursiveEndpoints<World, ILocalPortal> create() {
        return create(() -> Wormholes.portalManager == null ? List.of() : Wormholes.portalManager.getLocalPortals());
    }

    public static RecursiveEndpoints<World, ILocalPortal> create(Supplier<List<ILocalPortal>> source) {
        return new RecursiveEndpoints<>(new BukkitProjectorPortalAccess(source),
            () -> new RecursiveEndpoints.Options(Settings.PROJECTION_APERTURE_PADDING_BLOCKS, Settings.PROJECTION_DEPTH_BLOCKS));
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
    public CellAperture structure(ILocalPortal portal) {
        return portal.getStructure();
    }

    @Override
    public Box view(ILocalPortal portal) {
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
        return portal.getMirrorRotation().coherentFor(portal.getFrame()).getQuarterTurns();
    }

    @Override
    public ILocalPortal destination(ILocalPortal portal) {
        ITunnel tunnel = portal.getTunnel();
        return tunnel != null && tunnel.getDestination() instanceof ILocalPortal destination ? destination : null;
    }
}
