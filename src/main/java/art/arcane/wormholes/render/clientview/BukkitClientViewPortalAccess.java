package art.arcane.wormholes.render.clientview;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import art.arcane.wormholes.network.client.BrickLightSource;
import art.arcane.wormholes.network.client.SessionPalette;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.render.ClientViewPortalSource;
import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.render.client.ClientRecursionPlanner;
import art.arcane.wormholes.render.client.ClientSpace;
import art.arcane.wormholes.render.client.session.ClientViewPortalAccess;
import art.arcane.wormholes.render.plate.ViewPlate;
import art.arcane.wormholes.render.plate.ViewPlateCache;
import art.arcane.wormholes.render.view.ProjectionWorldViewProvider;
import art.arcane.wormholes.util.AxisAlignedBB;

public final class BukkitClientViewPortalAccess implements ClientViewPortalAccess<ClientViewObserver, BlockData> {
    private final ProjectionWorldViewProvider views;
    private final ViewPlateCache<BlockData, World> plates;
    private final Function<UUID, ILocalPortal> lookup;
    private final BiConsumer<UUID, UUID> releaseVanilla;
    private final LongSupplier identitySalt;
    private final BukkitClientViewScene scene;

    public BukkitClientViewPortalAccess(ProjectionWorldViewProvider views, ViewPlateCache<BlockData, World> plates,
                                        Function<UUID, ILocalPortal> lookup, BiConsumer<UUID, UUID> releaseVanilla,
                                        LongSupplier identitySalt) {
        this.views = Objects.requireNonNull(views, "views");
        this.plates = plates;
        this.lookup = Objects.requireNonNull(lookup, "lookup");
        this.releaseVanilla = Objects.requireNonNull(releaseVanilla, "releaseVanilla");
        this.identitySalt = Objects.requireNonNull(identitySalt, "identitySalt");
        this.scene = new BukkitClientViewScene(this);
    }

    BukkitClientViewScene scene() {
        return scene;
    }

    @Override
    public void interested(ClientViewObserver observer, List<UUID> out) {
        List<UUID> interest = observer.interest();
        for (int i = 0; i < interest.size(); i++) {
            out.add(interest.get(i));
        }
    }

    @Override
    public long geometryRevision(ClientViewObserver observer, UUID portal) {
        ClientViewPortalSource source = source(observer, portal);
        return source == null ? 0L : source.geometryRevision();
    }

    @Override
    public ClientPortalGeometry geometry(ClientViewObserver observer, UUID portal, SessionPalette palette) {
        ClientViewPortalSource source = source(observer, portal);
        return source == null ? null : source.geometry(palette, identitySalt.getAsLong());
    }

    @Override
    public ViewPlate<BlockData> plate(ClientViewObserver observer, UUID portal, boolean firstAttendance) {
        ClientViewPortalSource source = source(observer, portal);
        return source == null ? null : source.plate(firstAttendance);
    }

    @Override
    public boolean refused(ClientViewObserver observer, UUID portal) {
        ClientViewPortalSource source = source(observer, portal);
        return source == null || source.refused();
    }

    @Override
    public ViewPlate<BlockData> standbyPlate(ClientViewObserver observer, UUID portal) {
        return null;
    }

    @Override
    public BrickLightSource lightBaseline(ClientViewObserver observer, UUID portal, ViewPlate<BlockData> plate) {
        return scene.light(observer, portal, plate);
    }

    @Override
    public void releaseVanilla(ClientViewObserver observer, UUID portal) {
        releaseVanilla.accept(observer.id(), portal);
    }

    @Override
    public void nested(ClientViewObserver observer, UUID parent, ClientPortalGeometry parentGeometry, List<UUID> out) {
        if (!parentGeometry.mirror()) {
            return;
        }
        ClientViewPortalSource mirror = observer.source(parent);
        Location eye = observer.eye();
        if (mirror == null || eye == null) {
            return;
        }
        double[] reflected = new double[3];
        ClientSpace.mirror(parentGeometry).toContent(eye.getX(), eye.getY(), eye.getZ(), reflected);
        observer.reflectedEye(parent, new Location(eye.getWorld(), reflected[0], reflected[1], reflected[2]));
        List<ILocalPortal> candidates = observer.candidates();
        for (int i = 0; i < candidates.size(); i++) {
            ILocalPortal candidate = candidates.get(i);
            if (candidate == mirror.portal() || candidate.isMirrorMode() || candidate.getStructure() == null) {
                continue;
            }
            AxisAlignedBB area = candidate.getStructure().getArea();
            if (ClientRecursionPlanner.mirrorReaches(parentGeometry, area)) {
                out.add(candidate.getId());
            }
        }
    }

    @Override
    public long nestedGeometryRevision(ClientViewObserver observer, UUID parent, UUID child) {
        ClientViewPortalSource source = nestedSource(observer, parent, child);
        return source == null ? 0L : source.geometryRevision();
    }

    @Override
    public ClientPortalGeometry nestedGeometry(ClientViewObserver observer, UUID parent, UUID child, SessionPalette palette) {
        ClientViewPortalSource source = nestedSource(observer, parent, child);
        return source == null || source.refused() ? null : source.geometry(palette, identitySalt.getAsLong());
    }

    @Override
    public ViewPlate<BlockData> nestedPlate(ClientViewObserver observer, UUID parent, UUID child) {
        ClientViewPortalSource source = nestedSource(observer, parent, child);
        return source == null ? null : source.plate(false);
    }

    @Override
    public void effects(ClientViewObserver observer, List<UUID> out) {
        if (observer.player() != null) {
            observer.effects(out, System.nanoTime());
        }
    }

    @Override
    public long effectGeometryRevision(ClientViewObserver observer, UUID portalId) {
        ILocalPortal portal = portal(observer, portalId);
        return portal == null ? 0L : ClientViewPortalSource.effectGeometryRevision(portal, observer.eye());
    }

    @Override
    public ClientPortalGeometry effectGeometry(ClientViewObserver observer, UUID portalId, SessionPalette palette) {
        ILocalPortal portal = portal(observer, portalId);
        return portal == null ? null : ClientViewPortalSource.effectGeometry(portal, observer.eye());
    }

    ILocalPortal portal(ClientViewObserver observer, UUID portalId) {
        Player player = observer.player();
        ILocalPortal portal = player == null ? null : resolve(observer, portalId);
        return portal == null || !player.getWorld().equals(portal.getWorld()) ? null : portal;
    }

    ClientViewPortalSource nestedSource(ClientViewObserver observer, UUID parentId, UUID childId) {
        Player player = observer.player();
        Location reflected = observer.reflectedEye(parentId);
        if (player == null || reflected == null) {
            return null;
        }
        ClientViewPortalSource source = observer.nestedSource(parentId, childId);
        if (source == null) {
            ILocalPortal portal = resolve(observer, childId);
            if (portal == null) {
                return null;
            }
            source = new ClientViewPortalSource(portal, views, plates);
            observer.nestedSource(parentId, childId, source);
        }
        source.update(player, reflected, observer.target(childId), observer.frameTick());
        return source;
    }

    ClientViewPortalSource source(ClientViewObserver observer, UUID portalId) {
        Player player = observer.player();
        Location eye = observer.eye();
        if (player == null || eye == null) {
            return null;
        }
        ClientViewPortalSource source = observer.source(portalId);
        if (source == null) {
            ILocalPortal portal = resolve(observer, portalId);
            if (portal == null) {
                return null;
            }
            source = new ClientViewPortalSource(portal, views, plates);
            observer.source(portalId, source);
        }
        source.update(player, eye, observer.target(portalId), observer.frameTick());
        return source;
    }

    private ILocalPortal resolve(ClientViewObserver observer, UUID portalId) {
        ILocalPortal portal = observer.portal(portalId);
        if (portal == null) {
            portal = lookup.apply(portalId);
        }
        return portal == null || portal.isDestroyed() ? null : portal;
    }
}
