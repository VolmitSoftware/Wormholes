package art.arcane.wormholes.modded.clientview;

import art.arcane.optics.entity.EntityProfile;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftEntityMetadata;
import art.arcane.wormholes.modded.MinecraftLocalEntityView;
import art.arcane.wormholes.modded.MinecraftPacketBlobs;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftProjectionWorldView;
import art.arcane.wormholes.modded.MinecraftViewPlates;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.optics.stream.BrickLightSource;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.optics.entity.EntitySnapshot;
import art.arcane.wormholes.portal.AmbientOutlineGeometry;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.rtp.RtpRimRenderer;
import art.arcane.wormholes.render.FidelitySettings;
import art.arcane.optics.entity.ItemFrameTransform;
import art.arcane.optics.entity.EntityProjection;
import art.arcane.optics.fidelity.AcousticsBridge;
import art.arcane.optics.fidelity.AcousticsProfile;
import art.arcane.wormholes.render.client.session.ClientViewEmitters;
import art.arcane.optics.stream.EntityFrames;
import art.arcane.optics.client.PlateLight;
import art.arcane.wormholes.render.client.session.ClientViewSceneFx;
import art.arcane.optics.plate.ViewPlate;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.ViewWindow;
import art.arcane.optics.frame.QuarterTurn;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.block.state.BlockState;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import art.arcane.wormholes.network.client.FxMessage;
import art.arcane.wormholes.render.ProjectedEntityIdentity;

public final class MinecraftClientViewScene implements EntityFrames.Scenes<MinecraftClientViewPeer>,
    ClientViewSceneFx.Effects<MinecraftClientViewPeer> {
    private static final int SURFACE_CADENCE_TICKS = 5;
    private static final long METADATA_IDLE_TICKS = 200L;

    private final WormholesModRuntime runtime;
    private final MinecraftClientViewPortalAccess portals;
    private final long secret;
    private final EntityProjection transform;
    private final PlateLight.Cache<BlockState> lights;
    private final MinecraftEnvironmentCapture environments;
    private final Map<UUID, AmbientOutlineGeometry> outlines;
    private final Map<UUID, PatchedMetadata> metadata;
    private final Map<UUID, Long> spectators;
    private long metadataSweepTick;
    private long outlineSweepTick;

    public MinecraftClientViewScene(WormholesModRuntime runtime, MinecraftClientViewPortalAccess portals) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.portals = Objects.requireNonNull(portals, "portals");
        this.secret = new SecureRandom().nextLong();
        this.transform = new EntityProjection();
        this.lights = new PlateLight.Cache<BlockState>();
        this.environments = new MinecraftEnvironmentCapture(runtime);
        this.outlines = new HashMap<UUID, AmbientOutlineGeometry>();
        this.metadata = new HashMap<UUID, PatchedMetadata>();
        this.spectators = new ConcurrentHashMap<UUID, Long>();
    }

    @Override
    public Object sceneKey(MinecraftClientViewPeer peer, UUID portalId) {
        RenderConfig render = runtime.configuration().settings().getRender();
        if (!render.entitySpoofing || render.maxSpoofedEntities <= 0) {
            return null;
        }
        Destination destination = destination(peer, portalId);
        if (destination == null) {
            return null;
        }
        ViewWindow frame = destination.frame();
        return new SceneKey(portalId, destination.world(), destination.anchor().getId(), frame, peer.meshDepth() > 0,
            peer.portals().routeIdentity(portals.portal(peer, portalId)));
    }

    @Override
    public List<EntitySnapshot> capture(MinecraftClientViewPeer peer, UUID portalId, long tick) {
        Destination destination = destination(peer, portalId);
        ServerPlayer player = peer.player();
        if (destination == null || player == null) {
            return List.of();
        }
        RenderConfig render = runtime.configuration().settings().getRender();
        ViewWindow frame = destination.frame();
        double range = Math.min(render.entitySpoofRange, frame.depth());
        MinecraftLocalEntityView view = runtime.projections().scene(destination.world(), destination.anchor(), range);
        List<EntitySnapshot> source = view.getEntities(frame.remoteOrigin().x(), frame.remoteOrigin().y(), frame.remoteOrigin().z(), range);
        List<EntitySnapshot> ordered = nearest(source, frame, render.maxSpoofedEntities);
        boolean nativeMesh = peer.meshDepth() > 0;
        boolean upsideDown = !nativeMesh && frame.transform().flipsWorldUp();
        MinecraftPacketBlobs blobs = new MinecraftPacketBlobs(destination.world().registryAccess());
        List<EntitySnapshot> local = new ArrayList<EntitySnapshot>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            EntitySnapshot visual = ordered.get(i);
            EntityType<?> type = type(visual.typeKey());
            if (type == null) {
                continue;
            }
            boolean itemFrame = type == EntityTypes.ITEM_FRAME || type == EntityTypes.GLOW_ITEM_FRAME;
            boolean hanging = itemFrame || type == EntityTypes.PAINTING;
            EntitySnapshot profiled = withProfile(visual, view.getProfile(visual.id()));
            EntityProjection.Projected projected = nativeMesh ? transform.nativeModel(profiled, frame, hanging, secret)
                : transform.project(profiled, frame, hanging, itemFrame, secret, ProjectedEntityIdentity.NAMING);
            if (projected == null) {
                continue;
            }
            EntitySnapshot projectedVisual = withMetadata(projected, visual, blobs, upsideDown, tick);
            local.add(projectedVisual);
            if (visual.isPlayer() && destination.world().getEntity(visual.id()) instanceof ServerPlayer watcher && watcher.isSpectator()) {
                spectators.put(projectedVisual.id(), tick);
            } else if (visual.isPlayer()) {
                spectators.remove(projectedVisual.id());
            }
        }
        sweepMetadata(tick);
        return local;
    }


    @Override
    public UUID projectedId(UUID sourceId) {
        return EntityProjection.opaque(secret, sourceId);
    }

    @Override
    public boolean visible(MinecraftClientViewPeer peer, EntitySnapshot visual) {
        if (!spectators.containsKey(visual.id())) {
            return true;
        }
        ServerPlayer player = peer.player();
        return player != null && player.isSpectator();
    }

    @Override
    public boolean isObserver(MinecraftClientViewPeer peer, EntitySnapshot visual) {
        return EntityProjection.opaque(secret, peer.id()).equals(visual.id());
    }

    @Override
    public List<FxMessage.FxEmitter> emitters(MinecraftClientViewPeer peer, UUID portalId, long tick) {
        MinecraftPortal portal = portals.portal(peer, portalId);
        ServerPlayer player = peer.player();
        if (portal == null || player == null) {
            return List.of();
        }
        AcousticsBridge.Playback bed = bed(peer, player, portal);
        if (!runtime.configuration().settings().getMain().enableParticles) {
            return bed == null ? List.of() : List.of(ClientViewEmitters.sound(bed, AcousticsBridge.AMBIENT_INTERVAL_TICKS));
        }
        List<FxMessage.FxEmitter> emitters = new ArrayList<FxMessage.FxEmitter>(16);
        if (portal.getType() == PortalType.RTP) {
            RtpRimRenderer.Sample sample = runtime.rtp().rimSample(player, portal);
            if (sample != null) {
                RtpRimRenderer.Color color = sample.color();
                ClientViewEmitters.rim(portal.getGeometry().getArea(), color.red(), color.green(), color.blue(), FidelitySettings.rtpRimIntervalTicks,
                    emitters);
            }
        }
        if (tick - outlineSweepTick >= METADATA_IDLE_TICKS) {
            outlineSweepTick = tick;
            outlines.keySet().removeIf(id -> runtime.portals().get(id) == null);
        }
        AmbientOutlineGeometry outline = outlines.computeIfAbsent(portalId, ignored -> new AmbientOutlineGeometry());
        ClientViewEmitters.ambient(new ClientViewEmitters.Ambient(portal.getAmbientStyle(), portal.getAmbientColor(), portal.isOpen(),
            FidelitySettings.ambientParticleIntervalTicks, SURFACE_CADENCE_TICKS, portal.getGeometry().getArea(),
            outline.points(portal.getGeometry().getRevision(), portal.getDirection().getAxis(), portal.getGeometry())), emitters);
        if (bed != null && emitters.size() < FxMessage.MAX_FX_EMITTERS) {
            emitters.add(ClientViewEmitters.sound(bed, AcousticsBridge.AMBIENT_INTERVAL_TICKS));
        }
        return emitters;
    }

    @Override
    public ClientViewSceneFx.Sample atmosphere(MinecraftClientViewPeer peer, UUID portalId, long tick) {
        MinecraftPortal portal = portals.portal(peer, portalId);
        Destination destination = portal == null ? null : destination(peer, portalId);
        if (destination == null) {
            return null;
        }
        ServerLevel world = destination.world();
        if (!FidelitySettings.weather || !MinecraftViewPlates.atmosphereMode(portal).relaysWeather()) {
            return new ClientViewSceneFx.Sample(0L, false, 0.0F, 0.0F, ViewStreamMessage.Atmosphere.withSkyDarken(0, world.getSkyDarken()));
        }
        boolean clock = world.dimensionType().defaultClock().isPresent();
        int flags = ViewStreamMessage.Atmosphere.FLAG_WEATHER | (clock ? ViewStreamMessage.Atmosphere.FLAG_TIME : 0);
        return new ClientViewSceneFx.Sample(clock ? world.getDefaultClockTime() : 0L, clock, world.getRainLevel(1.0F), world.getThunderLevel(1.0F),
            ViewStreamMessage.Atmosphere.withSkyDarken(flags, world.getSkyDarken()));
    }

    @Override
    public boolean environmentUnavailable(MinecraftClientViewPeer peer, UUID parent, UUID portal) {
        return environments.unavailable(peer.id(), parent, portal);
    }

    void removeObserver(UUID observer) {
        environments.removeObserver(observer);
    }

    void close() {
        environments.close();
    }

    @Override
    public ProjectionEnvironment environment(MinecraftClientViewPeer peer, UUID portalId, long tick) {
        Destination destination = destination(peer, portalId);
        ServerPlayer player = peer.player();
        if (destination == null || player == null) {
            return null;
        }
        OpticTransform affine = destination.frame().transform().normalized();
        return environments.capture(new MinecraftEnvironmentCapture.Request(peer.id(), null, portalId, destination.world(),
            affine.inverse().point(new Vec3d(player.getX(), player.getEyeY(), player.getZ())), affine, tick));
    }

    @Override
    public ProjectionEnvironment nestedEnvironment(MinecraftClientViewPeer peer, UUID parent, UUID portalId, long tick) {
        ServerPlayer player = peer.player();
        MinecraftPortal portal = portals.portal(peer, portalId);
        Destination mirror = destination(peer, parent);
        if (player == null || portal == null || mirror == null) {
            return null;
        }
        Destination destination = destination(peer, portalId, portals.reflectedFront(peer, player, parent, portal));
        if (destination == null) {
            return null;
        }
        MinecraftClientViewPeer.NestedContext context = peer.nestedContext(parent);
        Vec3d eye = context == null
            ? mirror.frame().transform().normalized().inverse().point(new Vec3d(player.getX(), player.getEyeY(), player.getZ()))
            : context.destinationEye();
        OpticTransform affine = destination.frame().transform().normalized();
        return environments.capture(new MinecraftEnvironmentCapture.Request(peer.id(), parent, portalId, destination.world(),
            affine.inverse().point(new Vec3d(eye.x(), eye.y(), eye.z())), affine, tick));
    }

    public BrickLightSource light(MinecraftClientViewPeer peer, UUID portalId, ViewPlate<BlockState> plate) {
        if (plate == null || peer.meshDepth() == 0 && !runtime.configuration().settings().getRender().lightingFidelity) {
            return BrickLightSource.NONE;
        }
        return lights.light(plate, () -> {
            Destination destination = destination(peer, portalId);
            if (destination == null) {
                return null;
            }
            ViewWindow frame = destination.frame();
            MinecraftLightSnapshot snapshot = MinecraftLightSnapshot.capture(destination.world(), PlateLight.remoteBox(plate.box(), frame));
            return new PlateLight<BlockState>(plate, frame, snapshot, false);
        });
    }

    private AcousticsBridge.Playback bed(MinecraftClientViewPeer peer, ServerPlayer player, MinecraftPortal portal) {
        if (runtime.clientViews().owns(player.getUUID(), portal.getId())) {
            Destination destination = destination(peer, portal.getId());
            if (destination != null) {
                ViewWindow frame = destination.frame();
                AcousticsProfile profile = AcousticsProfile.parse(MinecraftViewPlates.stringSetting(portal, "fidelity.acoustics"),
                    FidelitySettings.acousticsProfileDefault);
                runtime.projections().noteClientViewAcoustics(player, portal.getId(), destination.world(), frame.remoteOrigin().x(), frame.remoteOrigin().y(),
                    frame.remoteOrigin().z(), portal.getGeometry().getApertureCenter(), profile);
            }
        }
        return runtime.projections().ambientBed(player, portal.getId());
    }

    private Destination destination(MinecraftClientViewPeer peer, UUID portalId) {
        MinecraftPortal portal = portals.portal(peer, portalId);
        ServerPlayer player = peer.player();
        if (portal == null || player == null) {
            return null;
        }
        MinecraftClientViewPeer.NestedContext context = peer.nestedContext(portalId);
        Vec3d eye = context == null ? null : context.sourceEye();
        boolean front = eye == null ? MinecraftClientViewPortalAccess.front(player, portal)
            : MinecraftClientViewPortalAccess.front(eye.x(), eye.y(), eye.z(), portal);
        return destination(peer, portalId, front);
    }

    Destination destination(MinecraftClientViewPeer peer, UUID portalId, boolean front) {
        MinecraftPortal portal = portals.portal(peer, portalId);
        if (portal == null) {
            return null;
        }
        MinecraftViewPlates.Target target = portals.target(peer, portal, front);
        if (target == null || !(target.destView() instanceof MinecraftProjectionWorldView view)) {
            return null;
        }
        MinecraftPortal anchor = portal.isMirrorMode() ? portal : peer.portals().projectionDestination(portal);
        if (anchor == null) {
            return null;
        }
        Vec3d origin = portal.getOrigin();
        double depth = peer.meshDepth() > 0 ? peer.meshDepth() : portal.getNetworkViewDepth();
        ViewWindow frame = target.mirrorMode()
            ? ViewWindow.mirror(origin, portal.getFrame(), QuarterTurn.of(target.mirrorQuarterTurns()), target.front(), depth)
            : ViewWindow.between(origin, portal.getFrame(), new Vec3d(target.originX(), target.originY(), target.originZ()), target.remoteFrame(),
                target.front(), depth);
        return new Destination(view.getWorld(), anchor, frame);
    }

    private EntitySnapshot withMetadata(EntityProjection.Projected projected, EntitySnapshot source, MinecraftPacketBlobs blobs,
                                      boolean upsideDown, long tick) {
        EntitySnapshot visual = projected.visual();
        int metadataTransform = projected.metadataTransform();
        byte[] raw = source.metadata();
        boolean map = source.mapData() != null && source.mapData().length > 0;
        if (metadataTransform == ItemFrameTransform.NONE && !upsideDown && !map || raw == null || raw.length == 0) {
            return visual;
        }
        PatchedMetadata cached = metadata.get(source.id());
        if (cached != null && cached.source == raw && cached.transform == metadataTransform && cached.upsideDown == upsideDown) {
            cached.touched = tick;
            return copy(visual, cached.patched);
        }
        List<SynchedEntityData.DataValue<?>> values = blobs.readMetadata(raw);
        values = MinecraftEntityMetadata.FRAMES.transformMetadata(values, metadataTransform, null, map);
        if (upsideDown) {
            values = visual.isPlayer() ? MinecraftEntityMetadata.ENTITIES.upsideDownPlayer(values)
                : MinecraftEntityMetadata.ENTITIES.upsideDownEntity(values, false);
        }
        byte[] patched = blobs.writeMetadata(values);
        metadata.put(source.id(), new PatchedMetadata(raw, metadataTransform, upsideDown, patched, tick));
        return copy(visual, patched);
    }

    private void sweepMetadata(long tick) {
        if (tick - metadataSweepTick < METADATA_IDLE_TICKS) {
            return;
        }
        metadataSweepTick = tick;
        metadata.values().removeIf(entry -> tick - entry.touched > METADATA_IDLE_TICKS);
        spectators.values().removeIf(seen -> tick - seen > METADATA_IDLE_TICKS);
    }

    private static List<EntitySnapshot> nearest(List<EntitySnapshot> source, ViewWindow frame, int limit) {
        if (source.size() <= limit) {
            return source;
        }
        List<EntitySnapshot> sorted = new ArrayList<EntitySnapshot>(source);
        sorted.sort((left, right) -> Double.compare(distance(left, frame), distance(right, frame)));
        return sorted.subList(0, limit);
    }

    private static double distance(EntitySnapshot visual, ViewWindow frame) {
        double dx = visual.x() - frame.remoteOrigin().x();
        double dy = visual.y() - frame.remoteOrigin().y();
        double dz = visual.z() - frame.remoteOrigin().z();
        return dx * dx + dy * dy + dz * dz;
    }

    private static EntitySnapshot withProfile(EntitySnapshot visual, EntityProfile profile) {
        if (!visual.isPlayer() || profile == null || profile.textureValue() == null || profile.textureValue().isEmpty()
            || profile.textureValue().equals(visual.textureValue())) {
            return visual;
        }
        return new EntitySnapshot(visual.mode(), visual.sequence(), visual.presentMask(), visual.id(), visual.typeKey(), visual.x(), visual.y(),
            visual.z(), visual.height(), visual.lookX(), visual.lookY(), visual.lookZ(), visual.yaw(), visual.pitch(), visual.velocityX(),
            visual.velocityY(), visual.velocityZ(), visual.onGround(), profile.name(), profile.textureValue(),
            profile.textureSignature() == null ? "" : profile.textureSignature(), visual.passengerOf(), visual.leashHolder(), visual.metadata(),
            visual.equipment(), visual.mapData());
    }

    private static EntitySnapshot copy(EntitySnapshot visual, byte[] metadata) {
        if (Arrays.equals(visual.metadata(), metadata)) {
            return visual;
        }
        return new EntitySnapshot(visual.mode(), visual.sequence(), visual.presentMask(), visual.id(), visual.typeKey(), visual.x(), visual.y(),
            visual.z(), visual.height(), visual.lookX(), visual.lookY(), visual.lookZ(), visual.yaw(), visual.pitch(), visual.velocityX(),
            visual.velocityY(), visual.velocityZ(), visual.onGround(), visual.playerName(), visual.textureValue(), visual.textureSignature(),
            visual.passengerOf(), visual.leashHolder(), metadata, visual.equipment(), visual.mapData());
    }

    private static EntityType<?> type(String key) {
        Identifier id = key == null ? null : Identifier.tryParse(key);
        return id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
    }

    record Destination(ServerLevel world, MinecraftPortal anchor, ViewWindow frame) {
    }

    private record SceneKey(UUID portal, ServerLevel world, UUID anchor, ViewWindow frame, boolean nativeMesh, long routeIdentity) {
    }

    private static final class PatchedMetadata {
        private final byte[] source;
        private final int transform;
        private final boolean upsideDown;
        private final byte[] patched;
        private long touched;

        private PatchedMetadata(byte[] source, int transform, boolean upsideDown, byte[] patched, long touched) {
            this.source = source;
            this.transform = transform;
            this.upsideDown = upsideDown;
            this.patched = patched;
            this.touched = touched;
        }
    }
}
