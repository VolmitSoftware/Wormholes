package art.arcane.wormholes.modded;

import art.arcane.wormholes.config.toml.DoorsConfig;
import art.arcane.wormholes.door.DoorForm;
import art.arcane.wormholes.door.DoorVec3;
import art.arcane.wormholes.door.DoorwayPlane;
import art.arcane.wormholes.door.view.DoorApertureFrames;
import art.arcane.wormholes.door.view.DoorProjectionDestination;
import art.arcane.wormholes.door.view.DoorProjectionProfile;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.Portal;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.aperture.ApertureCells;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.optics.scan.ProjectorPassRevision;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;

public final class MinecraftDoorProjectionViews {
    private final WormholesModRuntime runtime;
    private final Map<UUID, Aperture> apertures = new HashMap<>();

    public MinecraftDoorProjectionViews(WormholesModRuntime runtime) {
        this.runtime = runtime;
    }

    public List<MinecraftPortal> update(ServerPlayer observer, List<MinecraftDoorService.DoorView> doors, boolean nativeMesh) {
        DoorsConfig settings = runtime.configuration().settings().getDoors();
        List<MinecraftPortal> sources = new ArrayList<>();
        Set<UUID> retained = new HashSet<>();
        for (MinecraftDoorService.DoorView door : doors) {
            if (!door.active() || !nativeMesh && (door.level() != observer.level()
                || !settings.projectionEnabled || !door.endpoint().projection().projects(true))) {
                continue;
            }
            MinecraftDoorService.ProjectionDestination destination = runtime.doors().projectionDestination(door, observer.getUUID()).orElse(null);
            if (destination == null) {
                continue;
            }
            DoorProjectionDestination route = new DoorProjectionDestination(destination.id(), destination.level().dimension().identifier().toString(),
                destination.origin(), destination.frame());
            UUID id = door.endpoint().identity().itemId();
            Aperture aperture = apertures.get(id);
            if (aperture == null || !aperture.plane().equals(door.plane()) || !aperture.destination().signature().equals(route.signature())
                || aperture.source().getActivationRange() != settings.projectionRange
                || aperture.source().getNetworkViewDepth() != settings.projectionDepthBlocks) {
                aperture = create(door, route, settings);
                apertures.put(id, aperture);
            }
            retained.add(id);
            sources.add(aperture.source());
        }
        apertures.keySet().retainAll(retained);
        return sources;
    }

    public boolean current(MinecraftPortal portal) {
        Aperture aperture = apertures.get(portal.getId());
        return aperture != null && aperture.source() == portal;
    }

    public boolean contains(UUID endpointId) {
        return apertures.containsKey(endpointId);
    }

    public MinecraftPortal source(UUID endpointId) {
        Aperture aperture = apertures.get(endpointId);
        return aperture == null ? null : aperture.source();
    }

    public long routeIdentity(MinecraftPortal source) {
        Aperture aperture = apertures.get(source.getId());
        return aperture != null && aperture.source() == source ? aperture.identity() : 0L;
    }

    public MinecraftPortal destination(MinecraftPortal source) {
        Aperture aperture = apertures.get(source.getId());
        return aperture != null && aperture.source() == source ? aperture.anchor() : null;
    }

    public List<MinecraftPortal> sources() {
        List<MinecraftPortal> sources = new ArrayList<>(apertures.size());
        for (Aperture aperture : apertures.values()) {
            sources.add(aperture.source());
        }
        return sources;
    }

    private static Aperture create(MinecraftDoorService.DoorView door, DoorProjectionDestination destination, DoorsConfig settings) {
        DoorwayPlane plane = door.plane();
        DoorVec3 center = plane.center();
        ApertureCells geometry = new ApertureCells();
        Vec3d lower = new Vec3d(plane.blockX(), plane.blockY(), plane.blockZ());
        geometry.setBlocks(plane.form() == DoorForm.TRAPDOOR ? List.of(lower) : List.of(lower, lower.add(new Vec3d(0, 1, 0))));
        MinecraftPortal source = descriptor(door.endpoint().identity().itemId(),
            new Vec3d(center.x(), center.y(), center.z()), DoorApertureFrames.of(plane), geometry,
            door.level().dimension().identifier().toString());
        source.setActivationRange(settings.projectionRange);
        source.setNetworkViewDepth(settings.projectionDepthBlocks);
        source.setNetworkViewLateralPad(DoorProjectionProfile.VIEW_LATERAL_PAD);
        source.setNetworkViewHeartbeatTicks(DoorProjectionProfile.VIEW_HEARTBEAT_TICKS);
        source.setNetworkViewEntityIntervalTicks(DoorProjectionProfile.VIEW_ENTITY_INTERVAL_TICKS);
        source.setNetworkViewUnsubscribeGraceSeconds(DoorProjectionProfile.VIEW_UNSUBSCRIBE_GRACE_SECONDS);
        source.setNetworkViewFallbackBlock(DoorProjectionProfile.VIEW_FALLBACK_BLOCK);
        source.setAmbientStyle(AmbientParticleStyle.OFF);
        source.setRenderMode(ProjectionRenderMode.VENTICULAR);
        source.setBlackoutBackground(false);
        MinecraftPortal anchor = descriptor(destination.routeId(), destination.origin(), destination.frame(), new ApertureCells(), destination.worldKey());
        UUID route = UUID.nameUUIDFromBytes(destination.signature().getBytes(StandardCharsets.UTF_8));
        long identity = ProjectorPassRevision.mix(route.getMostSignificantBits(), route.getLeastSignificantBits());
        return new Aperture(plane, destination, source, anchor, identity == 0L ? 1L : identity);
    }

    private static MinecraftPortal descriptor(UUID id, Vec3d origin, Frame frame, ApertureCells geometry, String worldKey) {
        return new MinecraftPortal(new MinecraftPortal.Definition(new Portal.State(id, origin, id.toString(), frame, true), geometry, worldKey,
            Map.of("type", PortalType.PORTAL.name())));
    }

    private record Aperture(DoorwayPlane plane, DoorProjectionDestination destination, MinecraftPortal source, MinecraftPortal anchor, long identity) {
    }
}
