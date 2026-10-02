package art.arcane.wormholes.modded.clientview;

import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.modded.MinecraftEntityMetadata;
import art.arcane.wormholes.modded.MinecraftLocalEntityView;
import art.arcane.wormholes.modded.MinecraftPacketBlobs;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftProjectionWorldView;
import art.arcane.wormholes.modded.MinecraftViewPlates;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.network.client.BrickLightSource;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.network.view.RemoteViewCache.RemoteProfile;
import art.arcane.wormholes.portal.AmbientOutlineGeometry;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.rtp.RtpRimRenderer;
import art.arcane.wormholes.render.FidelitySettings;
import art.arcane.wormholes.render.ProjectedItemFrameTransform;
import art.arcane.wormholes.render.acoustics.AcousticsBridge;
import art.arcane.wormholes.render.acoustics.AcousticsProfile;
import art.arcane.wormholes.render.client.ClientViewEntityTransform;
import art.arcane.wormholes.render.client.ClientViewEnvironmentTransform;
import art.arcane.wormholes.render.client.session.ClientViewEmitters;
import art.arcane.wormholes.render.client.session.ClientViewEntityFrames;
import art.arcane.wormholes.render.client.session.ClientViewPlateLight;
import art.arcane.wormholes.render.client.session.ClientViewSceneFx;
import art.arcane.wormholes.render.plate.ViewPlate;
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

public final class MinecraftClientViewScene implements ClientViewEntityFrames.Scenes<MinecraftClientViewPeer>,
    ClientViewSceneFx.Effects<MinecraftClientViewPeer> {
    private static final int SURFACE_CADENCE_TICKS = 5;
    private static final long METADATA_IDLE_TICKS = 200L;

    private final WormholesModRuntime runtime;
    private final MinecraftClientViewPortalAccess portals;
    private final long secret;
    private final ClientViewEntityTransform transform;
    private final ClientViewPlateLight.Cache<BlockState> lights;
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
        this.transform = new ClientViewEntityTransform();
        this.lights = new ClientViewPlateLight.Cache<BlockState>();
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
        ClientViewEntityTransform.Frame frame = destination.frame();
        return new SceneKey(portalId, destination.world(), destination.anchor().getId(), frame, peer.meshDepth() > 0,
            peer.portals().routeIdentity(portals.portal(peer, portalId)));
    }

    @Override
    public List<EntityVisual> capture(MinecraftClientViewPeer peer, UUID portalId, long tick) {
        Destination destination = destination(peer, portalId);
        ServerPlayer player = peer.player();
        if (destination == null || player == null) {
            return List.of();
        }
        RenderConfig render = runtime.configuration().settings().getRender();
        ClientViewEntityTransform.Frame frame = destination.frame();
        double range = Math.min(render.entitySpoofRange, frame.depth());
        MinecraftLocalEntityView view = runtime.projections().scene(destination.world(), destination.anchor(), range);
        List<EntityVisual> source = view.getEntities(frame.remoteOriginX(), frame.remoteOriginY(), frame.remoteOriginZ(), range);
        List<EntityVisual> ordered = nearest(source, frame, render.maxSpoofedEntities);
        boolean nativeMesh = peer.meshDepth() > 0;
        boolean upsideDown = !nativeMesh && transform.upsideDown(frame);
        MinecraftPacketBlobs blobs = new MinecraftPacketBlobs(destination.world().registryAccess());
        List<EntityVisual> local = new ArrayList<EntityVisual>(ordered.size());
        for (int i = 0; i < ordered.size(); i++) {
            EntityVisual visual = ordered.get(i);
            EntityType<?> type = type(visual.typeKey());
            if (type == null) {
                continue;
            }
            boolean itemFrame = type == EntityTypes.ITEM_FRAME || type == EntityTypes.GLOW_ITEM_FRAME;
            boolean hanging = itemFrame || type == EntityTypes.PAINTING;
            EntityVisual profiled = withProfile(visual, view.getProfile(visual.id()));
            ClientViewEntityTransform.Projected projected = nativeMesh ? transform.nativeModel(profiled, frame, hanging, secret)
                : transform.project(profiled, frame, hanging, itemFrame, secret);
            if (projected == null) {
                continue;
            }
            EntityVisual projectedVisual = withMetadata(projected, visual, blobs, upsideDown, tick);
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
    public boolean visible(MinecraftClientViewPeer peer, EntityVisual visual) {
        if (!spectators.containsKey(visual.id())) {
            return true;
        }
        ServerPlayer player = peer.player();
        return player != null && player.isSpectator();
    }

    @Override
    public boolean isObserver(MinecraftClientViewPeer peer, EntityVisual visual) {
        return ClientViewEntityTransform.opaque(secret, peer.id()).equals(visual.id());
    }

    @Override
    public List<ClientViewMessage.FxEmitter> emitters(MinecraftClientViewPeer peer, UUID portalId, long tick) {
        MinecraftPortal portal = portals.portal(peer, portalId);
        ServerPlayer player = peer.player();
        if (portal == null || player == null) {
            return List.of();
        }
        AcousticsBridge.Playback bed = bed(peer, player, portal);
        if (!runtime.configuration().settings().getMain().enableParticles) {
            return bed == null ? List.of() : List.of(ClientViewEmitters.sound(bed, AcousticsBridge.AMBIENT_INTERVAL_TICKS));
        }
        List<ClientViewMessage.FxEmitter> emitters = new ArrayList<ClientViewMessage.FxEmitter>(16);
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
        if (bed != null && emitters.size() < ClientViewProtocol.MAX_FX_EMITTERS) {
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
            return new ClientViewSceneFx.Sample(0L, false, 0.0F, 0.0F, ClientViewMessage.Atmosphere.withSkyDarken(0, world.getSkyDarken()));
        }
        boolean clock = world.dimensionType().defaultClock().isPresent();
        int flags = ClientViewMessage.Atmosphere.FLAG_WEATHER | (clock ? ClientViewMessage.Atmosphere.FLAG_TIME : 0);
        return new ClientViewSceneFx.Sample(clock ? world.getDefaultClockTime() : 0L, clock, world.getRainLevel(1.0F), world.getThunderLevel(1.0F),
            ClientViewMessage.Atmosphere.withSkyDarken(flags, world.getSkyDarken()));
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
    public ClientViewEnvironment environment(MinecraftClientViewPeer peer, UUID portalId, long tick) {
        Destination destination = destination(peer, portalId);
        ServerPlayer player = peer.player();
        if (destination == null || player == null) {
            return null;
        }
        ClientViewEnvironment.Transform affine = ClientViewEnvironmentTransform.of(destination.frame());
        return environments.capture(new MinecraftEnvironmentCapture.Request(peer.id(), null, portalId, destination.world(),
            affine.destinationPoint(player.getX(), player.getEyeY(), player.getZ()), affine, tick));
    }

    @Override
    public ClientViewEnvironment nestedEnvironment(MinecraftClientViewPeer peer, UUID parent, UUID portalId, long tick) {
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
        GeometryVector eye = context == null
            ? ClientViewEnvironmentTransform.of(mirror.frame()).destinationPoint(player.getX(), player.getEyeY(), player.getZ())
            : context.destinationEye();
        ClientViewEnvironment.Transform affine = ClientViewEnvironmentTransform.of(destination.frame());
        return environments.capture(new MinecraftEnvironmentCapture.Request(peer.id(), parent, portalId, destination.world(),
            affine.destinationPoint(eye.x(), eye.y(), eye.z()), affine, tick));
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
            ClientViewEntityTransform.Frame frame = destination.frame();
            MinecraftLightSnapshot snapshot = MinecraftLightSnapshot.capture(destination.world(), ClientViewPlateLight.remoteBox(plate.box(), frame));
            return new ClientViewPlateLight<BlockState>(plate, frame, snapshot, false);
        });
    }

    private AcousticsBridge.Playback bed(MinecraftClientViewPeer peer, ServerPlayer player, MinecraftPortal portal) {
        if (runtime.clientViews().owns(player.getUUID(), portal.getId())) {
            Destination destination = destination(peer, portal.getId());
            if (destination != null) {
                ClientViewEntityTransform.Frame frame = destination.frame();
                AcousticsProfile profile = AcousticsProfile.parse(MinecraftViewPlates.stringSetting(portal, "fidelity.acoustics"),
                    FidelitySettings.acousticsProfileDefault);
                runtime.projections().noteClientViewAcoustics(player, portal.getId(), destination.world(), frame.remoteOriginX(), frame.remoteOriginY(),
                    frame.remoteOriginZ(), portal.getGeometry().getApertureCenter(), profile);
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
        GeometryVector eye = context == null ? null : context.sourceEye();
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
        GeometryVector origin = portal.getOrigin();
        ClientViewEntityTransform.Frame frame = new ClientViewEntityTransform.Frame(origin.x(), origin.y(), origin.z(), portal.getFrame(),
            target.originX(), target.originY(), target.originZ(), target.remoteFrame(), target.mirrorMode(), target.mirrorQuarterTurns(),
            target.front(), peer.meshDepth() > 0 ? peer.meshDepth() : portal.getNetworkViewDepth());
        return new Destination(view.getWorld(), anchor, frame);
    }

    private EntityVisual withMetadata(ClientViewEntityTransform.Projected projected, EntityVisual source, MinecraftPacketBlobs blobs,
                                      boolean upsideDown, long tick) {
        EntityVisual visual = projected.visual();
        int metadataTransform = projected.metadataTransform();
        byte[] raw = source.metadata();
        boolean map = source.mapData() != null && source.mapData().length > 0;
        if (metadataTransform == ProjectedItemFrameTransform.NONE && !upsideDown && !map || raw == null || raw.length == 0) {
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

    private static List<EntityVisual> nearest(List<EntityVisual> source, ClientViewEntityTransform.Frame frame, int limit) {
        if (source.size() <= limit) {
            return source;
        }
        List<EntityVisual> sorted = new ArrayList<EntityVisual>(source);
        sorted.sort((left, right) -> Double.compare(distance(left, frame), distance(right, frame)));
        return sorted.subList(0, limit);
    }

    private static double distance(EntityVisual visual, ClientViewEntityTransform.Frame frame) {
        double dx = visual.x() - frame.remoteOriginX();
        double dy = visual.y() - frame.remoteOriginY();
        double dz = visual.z() - frame.remoteOriginZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static EntityVisual withProfile(EntityVisual visual, RemoteProfile profile) {
        if (!visual.isPlayer() || profile == null || profile.textureValue() == null || profile.textureValue().isEmpty()
            || profile.textureValue().equals(visual.textureValue())) {
            return visual;
        }
        return new EntityVisual(visual.mode(), visual.sequence(), visual.presentMask(), visual.id(), visual.typeKey(), visual.x(), visual.y(),
            visual.z(), visual.height(), visual.lookX(), visual.lookY(), visual.lookZ(), visual.yaw(), visual.pitch(), visual.velocityX(),
            visual.velocityY(), visual.velocityZ(), visual.onGround(), profile.name(), profile.textureValue(),
            profile.textureSignature() == null ? "" : profile.textureSignature(), visual.passengerOf(), visual.leashHolder(), visual.metadata(),
            visual.equipment(), visual.mapData());
    }

    private static EntityVisual copy(EntityVisual visual, byte[] metadata) {
        if (Arrays.equals(visual.metadata(), metadata)) {
            return visual;
        }
        return new EntityVisual(visual.mode(), visual.sequence(), visual.presentMask(), visual.id(), visual.typeKey(), visual.x(), visual.y(),
            visual.z(), visual.height(), visual.lookX(), visual.lookY(), visual.lookZ(), visual.yaw(), visual.pitch(), visual.velocityX(),
            visual.velocityY(), visual.velocityZ(), visual.onGround(), visual.playerName(), visual.textureValue(), visual.textureSignature(),
            visual.passengerOf(), visual.leashHolder(), metadata, visual.equipment(), visual.mapData());
    }

    private static EntityType<?> type(String key) {
        Identifier id = key == null ? null : Identifier.tryParse(key);
        return id == null ? null : BuiltInRegistries.ENTITY_TYPE.getOptional(id).orElse(null);
    }

    record Destination(ServerLevel world, MinecraftPortal anchor, ClientViewEntityTransform.Frame frame) {
    }

    private record SceneKey(UUID portal, ServerLevel world, UUID anchor, ClientViewEntityTransform.Frame frame, boolean nativeMesh, long routeIdentity) {
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
