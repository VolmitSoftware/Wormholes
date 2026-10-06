package art.arcane.wormholes.render.clientview;

import java.util.List;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.LongSupplier;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import art.arcane.optics.math.Vec3;
import art.arcane.optics.view.WorldChangeTracker;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.ProjectionManager;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.optics.client.ClientViewEnvironmentTransform;
import art.arcane.optics.plate.PlateBox;
import art.arcane.optics.stream.BrickLightSource;
import art.arcane.optics.stream.SectionBiomes;
import art.arcane.wormholes.network.client.SessionPalette;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.render.ClientViewPortalSource;
import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.recursion.ClientRecursionPlanner;
import art.arcane.optics.client.ClientSpace;
import art.arcane.wormholes.render.client.session.ClientViewPortalAccess;
import art.arcane.optics.plate.ViewPlate;
import art.arcane.optics.plate.ViewPlateCache;
import art.arcane.wormholes.render.view.ProjectionWorldViewProvider;
import art.arcane.optics.math.Box;

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
    public ApertureDescriptor geometry(ClientViewObserver observer, UUID portal, SessionPalette palette) {
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
    public SectionBiomes meshBiomes(ClientViewObserver observer, UUID portal, ViewPlate<BlockData> plate) {
        if (plate.environment() == null) {
            throw new IllegalStateException("native mesh section has no captured destination light and biomes for " + portal);
        }
        return plate.environment().biomes();
    }

    @Override
    public BrickLightSource lightBaseline(ClientViewObserver observer, UUID portal, ViewPlate<BlockData> plate) {
        return observer.meshDepth() > 0 && plate.environment() != null ? plate.environment() : scene.light(observer, portal, plate);
    }

    @Override
    public void releaseVanilla(ClientViewObserver observer, UUID portal) {
        releaseVanilla.accept(observer.id(), portal);
    }

    @Override
    public int meshDistanceBlocks(ClientViewObserver observer) {
        Player player = observer.player();
        return player == null ? 0 : Math.clamp(player.getClientViewDistance(), 2, 32) * 16;
    }

    @Override
    public Vec3 meshEye(ClientViewObserver observer) {
        Location eye = observer.eye();
        return eye == null ? null : new Vec3(eye.getX(), eye.getY(), eye.getZ());
    }

    @Override
    public WorldChangeTracker meshChanges(ClientViewObserver observer) {
        return Wormholes.projectionChangeTracker;
    }

    @Override
    public boolean localMeshWorld(ClientViewObserver observer, UUID contextId) {
        ClientViewPortalSource source = source(observer, contextId);
        Location eye = observer.eye();
        return source != null && source.portal().isMirrorMode() && eye != null
            && eye.getWorld().equals(source.destinationWorld());
    }

    @Override
    public ViewPlate<BlockData> meshSection(ClientViewObserver observer, UUID portal, PlateBox clip, int distance) {
        ClientViewPortalSource source = source(observer, portal);
        return source == null ? null : source.meshSection(clip, distance);
    }

    @Override
    public void nested(ClientViewObserver observer, UUID parent, ApertureDescriptor parentGeometry, List<UUID> out) {
        if (observer.meshDepth() > 0) {
            nestedNative(observer, parent, parentGeometry, out);
            return;
        }
        if (!parentGeometry.mirror()) {
            return;
        }
        ClientViewPortalSource mirror = observer.source(parent);
        Location eye = observer.eye();
        if (mirror == null || eye == null || mirror.transformFrame() == null) {
            return;
        }
        ProjectionEnvironment.Transform transform = ClientViewEnvironmentTransform.of(mirror.transformFrame());
        double[] reflected = new double[3];
        ClientSpace.mirror(parentGeometry).toContent(eye.getX(), eye.getY(), eye.getZ(), reflected);
        observer.reflectedEye(parent, new Location(eye.getWorld(), reflected[0], reflected[1], reflected[2]));
        List<ILocalPortal> candidates = observer.candidates();
        for (int i = 0; i < candidates.size(); i++) {
            ILocalPortal candidate = candidates.get(i);
            if (candidate == mirror.portal() || candidate.isMirrorMode() || candidate.getStructure() == null) {
                continue;
            }
            Box area = candidate.getStructure().getArea();
            if (ClientRecursionPlanner.destinationReaches(parentGeometry, transform, area)) {
                out.add(candidate.getId());
            }
        }
    }

    @Override
    public void prepareNested(ClientViewObserver observer, UUID context, UUID parentContext, UUID portal) {
        ClientViewPortalSource source = parentContext == null ? source(observer, portal) : nestedSource(observer, parentContext, portal);
        Location eye = parentContext == null ? observer.eye() : observer.reflectedEye(parentContext);
        if (source == null || eye == null || source.destinationWorld() == null || source.transformFrame() == null) {
            observer.releaseNested(context);
            return;
        }
        observer.nestedContext(context, source, eye);
        ProjectionEnvironment.Transform transform = ClientViewEnvironmentTransform.of(source.transformFrame());
        Vec3 destinationEye = transform.destinationPoint(eye.getX(), eye.getY(), eye.getZ());
        observer.reflectedEye(context, new Location(source.destinationWorld(), destinationEye.x(), destinationEye.y(), destinationEye.z()));
    }

    @Override
    public void releaseNested(ClientViewObserver observer, UUID context) {
        observer.releaseNested(context);
    }

    @Override
    public Vec3 nestedEye(ClientViewObserver observer, UUID context) {
        Location eye = observer.reflectedEye(context);
        return eye == null ? null : new Vec3(eye.getX(), eye.getY(), eye.getZ());
    }

    private void nestedNative(ClientViewObserver observer, UUID parent, ApertureDescriptor geometry, List<UUID> out) {
        ClientViewObserver.NestedContext context = observer.nestedContext(parent);
        if (context == null) {
            return;
        }
        ClientViewPortalSource source = context.source();
        ProjectionEnvironment.Transform transform = ClientViewEnvironmentTransform.of(source.transformFrame());
        List<ILocalPortal> candidates = new ArrayList<>(observer.candidates());
        if (Wormholes.portalManager != null) {
            candidates.addAll(Wormholes.portalManager.getLocalPortals());
        }
        if (Wormholes.dimensionalDoorManager != null) {
            candidates.addAll(Wormholes.dimensionalDoorManager.projectionRegistry().apertures());
        }
        Set<UUID> seen = new HashSet<>();
        for (ILocalPortal candidate : candidates) {
            if (!seen.add(candidate.getId()) || candidate.getId().equals(source.portal().getId()) || candidate.isDestroyed()
                || !source.destinationWorld().equals(candidate.getWorld()) || candidate.getStructure() == null
                || !ClientRecursionPlanner.destinationReaches(geometry, transform, candidate.getStructure().getArea())) {
                continue;
            }
            if (!observer.candidates().contains(candidate)) {
                ProjectionManager.ProjectionResolution resolution = Wormholes.projectionManager
                    .resolveProjection(candidate, observer.player(), observer.frameTick());
                if (!resolution.projectable()) {
                    continue;
                }
                observer.nestedPortal(candidate, resolution.target());
            }
            out.add(candidate.getId());
        }
    }

    @Override
    public long nestedGeometryRevision(ClientViewObserver observer, UUID parent, UUID child) {
        ClientViewPortalSource source = nestedSource(observer, parent, child);
        return source == null ? 0L : source.geometryRevision();
    }

    @Override
    public ApertureDescriptor nestedGeometry(ClientViewObserver observer, UUID parent, UUID child, SessionPalette palette) {
        ClientViewPortalSource source = nestedSource(observer, parent, child);
        return source == null || observer.meshDepth() == 0 && source.refused() ? null : source.geometry(palette, identitySalt.getAsLong());
    }

    @Override
    public ViewPlate<BlockData> nestedPlate(ClientViewObserver observer, UUID parent, UUID child) {
        ClientViewPortalSource source = nestedSource(observer, parent, child);
        return source == null ? null : source.plate(false);
    }

    @Override
    public ViewPlate<BlockData> nestedMeshSection(ClientViewObserver observer, UUID parent, UUID child, PlateBox clip, int distance) {
        ClientViewPortalSource source = nestedSource(observer, parent, child);
        return source == null ? null : source.meshSection(clip, distance);
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
    public ApertureDescriptor effectGeometry(ClientViewObserver observer, UUID portalId, SessionPalette palette) {
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
        ILocalPortal portal = resolve(observer, childId);
        if (portal == null || !reflected.getWorld().equals(portal.getWorld())) {
            return null;
        }
        ClientViewPortalSource source = observer.nestedSource(parentId, childId);
        if (source == null || source.portal() != portal) {
            source = new ClientViewPortalSource(portal, views, plates);
            observer.nestedSource(parentId, childId, source);
        }
        source.update(player, reflected, observer.target(childId), observer.frameTick(), observer.meshDepth() > 0);
        return source;
    }

    ClientViewPortalSource source(ClientViewObserver observer, UUID portalId) {
        ClientViewObserver.NestedContext context = observer.nestedContext(portalId);
        if (context != null && !context.source().portal().getId().equals(portalId)) {
            return context.source();
        }
        Player player = observer.player();
        Location eye = observer.eye();
        if (player == null || eye == null) {
            return null;
        }
        ILocalPortal portal = resolve(observer, portalId);
        if (portal == null) {
            return null;
        }
        ClientViewPortalSource source = observer.source(portalId);
        if (source == null || source.portal() != portal) {
            source = new ClientViewPortalSource(portal, views, plates);
            observer.source(portalId, source);
        }
        source.update(player, eye, observer.target(portalId), observer.frameTick(), observer.meshDepth() > 0);
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
