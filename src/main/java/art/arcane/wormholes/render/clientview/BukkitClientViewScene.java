package art.arcane.wormholes.render.clientview;

import art.arcane.wormholes.render.view.RemoteWorldView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;

import org.bukkit.World;
import org.bukkit.Location;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import art.arcane.wormholes.Settings;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.stream.EntityScenes;
import art.arcane.optics.stream.EnvironmentState;
import art.arcane.optics.stream.BrickLightSource;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.entity.EntitySnapshot;
import art.arcane.wormholes.portal.AmbientOutlineGeometry;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.rtp.RtpRimRenderer;
import art.arcane.wormholes.render.ClientViewPortalSource;
import art.arcane.wormholes.render.ClientViewSceneCapture;
import art.arcane.wormholes.render.FidelitySubsystem;
import art.arcane.optics.fidelity.AcousticsBridge;
import art.arcane.wormholes.render.client.session.ClientViewEmitters;
import art.arcane.wormholes.render.client.session.ClientViewSceneFx;
import art.arcane.optics.plate.ViewPlate;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.ViewWindow;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.network.client.FxMessage;

final class BukkitClientViewScene implements EntityScenes<ClientViewObserver>, ClientViewSceneFx.Effects<ClientViewObserver> {
    private static final int AMBIENT_CADENCE_TICKS = 1;

    private final BukkitClientViewPortalAccess portals;
    private final ClientViewSceneCapture capture;
    private final BukkitEnvironmentCapture environments = new BukkitEnvironmentCapture();
    private final Map<PortalStructure, AmbientOutlineGeometry> outlines;

    BukkitClientViewScene(BukkitClientViewPortalAccess portals) {
        this.portals = Objects.requireNonNull(portals, "portals");
        this.capture = new ClientViewSceneCapture();
        this.outlines = Collections.synchronizedMap(new WeakHashMap<PortalStructure, AmbientOutlineGeometry>());
    }

    void removeObserver(UUID observer) {
        environments.removeObserver(observer);
    }

    void close() {
        environments.close();
    }

    @Override
    public Object sceneKey(ClientViewObserver observer, UUID portalId) {
        ClientViewPortalSource source = portals.source(observer, portalId);
        ViewWindow frame = frame(observer, source);
        if (frame == null || !Settings.ENTITY_SPOOFING) {
            return null;
        }
        IPortal anchor = source.destinationAnchor();
        return new SceneKey(portalId, source.destinationView(), anchor == null ? null : anchor.getId(), frame, observer.meshDepth() > 0);
    }

    @Override
    public List<EntitySnapshot> capture(ClientViewObserver observer, UUID portalId, long tick) {
        ClientViewPortalSource source = portals.source(observer, portalId);
        return source == null ? List.of() : capture.entities(source, frame(observer, source), tick, observer.meshDepth() > 0);
    }

    @Override
    public UUID projectedId(UUID sourceId) {
        return capture.projectedId(sourceId);
    }

    @Override
    public boolean visible(ClientViewObserver observer, EntitySnapshot visual) {
        return capture.visible(observer.player(), visual.id());
    }

    @Override
    public boolean isObserver(ClientViewObserver observer, EntitySnapshot visual) {
        return capture.isObserver(observer.player(), visual.id());
    }

    @Override
    public List<FxMessage.FxEmitter> emitters(ClientViewObserver observer, UUID portalId, long tick) {
        ILocalPortal portal = portals.portal(observer, portalId);
        if (portal == null) {
            return List.of();
        }
        AcousticsBridge.Playback bed = bed(observer, portal);
        PortalStructure structure = portal.getStructure();
        if (!Settings.ENABLE_PARTICLES || structure == null || structure.getArea() == null) {
            return bed == null ? List.of() : List.of(ClientViewEmitters.sound(bed, AcousticsBridge.AMBIENT_INTERVAL_TICKS));
        }
        List<FxMessage.FxEmitter> emitters = new ArrayList<FxMessage.FxEmitter>(16);
        RtpRimRenderer.Sample rim = observer.rim(portalId);
        if (rim != null) {
            RtpRimRenderer.Color color = rim.color();
            ClientViewEmitters.rim(structure.getArea(), color.red(), color.green(), color.blue(), Settings.RTP_RIM_INTERVAL_TICKS, emitters);
        }
        List<double[]> outline = List.of();
        if (portal.getFrame() != null) {
            outline = outlines.computeIfAbsent(structure, ignored -> new AmbientOutlineGeometry())
                .points(structure.getRevision(), portal.getFrame().getNormal().getAxis(), structure.geometry(), structure.shapeOutline());
        }
        ClientViewEmitters.ambient(new ClientViewEmitters.Ambient(portal.getAmbientStyle(), portal.getAmbientColor(), portal.isOpen(),
            Settings.AMBIENT_PARTICLE_INTERVAL_TICKS, AMBIENT_CADENCE_TICKS, structure.getArea(), outline), emitters);
        if (bed != null && emitters.size() < FxMessage.MAX_FX_EMITTERS) {
            emitters.add(ClientViewEmitters.sound(bed, AcousticsBridge.AMBIENT_INTERVAL_TICKS));
        }
        return emitters;
    }

    @Override
    public ClientViewSceneFx.Sample atmosphere(ClientViewObserver observer, UUID portalId, long tick) {
        ClientViewPortalSource source = portals.source(observer, portalId);
        World world = source == null ? null : source.destinationWorld();
        ProjectionWorldView view = source == null ? null : source.destinationView();
        if (view == null) {
            return null;
        }
        int darken = view.getSkyDarken();
        if (world == null && view instanceof RemoteWorldView remote) {
            EnvironmentState environment = remote.environment(OpticTransform.IDENTITY);
            if (environment == null) {
                return null;
            }
            boolean weather = source.relaysWeather();
            boolean clock = weather && environment.sky().skybox() == EnvironmentState.Skybox.OVERWORLD;
            int flags = (weather ? ViewStreamMessage.Atmosphere.FLAG_WEATHER : 0) | (clock ? ViewStreamMessage.Atmosphere.FLAG_TIME : 0);
            return new ClientViewSceneFx.Sample(clock ? environment.gameTime() : 0L, clock,
                weather ? environment.sky().rain() : 0.0F, weather ? environment.sky().thunder() : 0.0F,
                ViewStreamMessage.Atmosphere.withSkyDarken(flags, darken));
        }
        if (world == null) {
            return null;
        }
        if (!source.relaysWeather()) {
            return new ClientViewSceneFx.Sample(0L, false, 0.0F, 0.0F, ViewStreamMessage.Atmosphere.withSkyDarken(0, darken));
        }
        boolean clock = world.getEnvironment() == World.Environment.NORMAL;
        int flags = ViewStreamMessage.Atmosphere.FLAG_WEATHER | (clock ? ViewStreamMessage.Atmosphere.FLAG_TIME : 0);
        return new ClientViewSceneFx.Sample(clock ? world.getFullTime() : 0L, clock, world.hasStorm() ? 1.0F : 0.0F,
            world.isThundering() ? 1.0F : 0.0F, ViewStreamMessage.Atmosphere.withSkyDarken(flags, darken));
    }

    @Override
    public boolean environmentUnavailable(ClientViewObserver observer, UUID parent, UUID portal) {
        return environments.unavailable(observer.id(), parent, portal);
    }

    @Override
    public EnvironmentState environment(ClientViewObserver observer, UUID portalId, long tick) {
        return environment(observer, null, portalId, portals.source(observer, portalId), observer.eye(), tick);
    }

    @Override
    public EnvironmentState nestedEnvironment(ClientViewObserver observer, UUID parent, UUID portalId, long tick) {
        return environment(observer, parent, portalId, portals.nestedSource(observer, parent, portalId), observer.reflectedEye(parent), tick);
    }

    private EnvironmentState environment(ClientViewObserver observer, UUID parent, UUID portalId, ClientViewPortalSource source,
                                               Location eye, long tick) {
        if (source == null || eye == null || source.transformFrame() == null) {
            return null;
        }
        OpticTransform transform = source.transformFrame().transform().normalized();
        if (source.destinationView() instanceof RemoteWorldView remote) {
            return remote.environment(transform);
        }
        if (source.destinationWorld() == null) {
            return null;
        }
        Vec3d destinationEye = transform.inverse().point(new Vec3d(eye.getX(), eye.getY(), eye.getZ()));
        return environments.capture(new BukkitEnvironmentCapture.Request(observer.id(), parent, portalId, source.destinationWorld(),
            destinationEye, transform, tick));
    }

    private static ViewWindow frame(ClientViewObserver observer, ClientViewPortalSource source) {
        ViewWindow frame = source == null ? null : source.transformFrame();
        return frame != null && observer.meshDepth() > 0 ? frame.withDepth(observer.meshDepth()) : frame;
    }

    private static AcousticsBridge.Playback bed(ClientViewObserver observer, ILocalPortal portal) {
        AcousticsBridge<Player> bridge = FidelitySubsystem.acoustics();
        if (bridge == null) {
            return null;
        }
        ClientViewPortalSource source = observer.ownedPortals().contains(portal.getId()) ? observer.source(portal.getId()) : null;
        if (source != null) {
            source.noteAcoustics(bridge, System.currentTimeMillis());
        }
        return bridge.ambientBed(portal.getId());
    }

    BrickLightSource light(ClientViewObserver observer, UUID portalId, ViewPlate<BlockData> plate) {
        ClientViewPortalSource source = portals.source(observer, portalId);
        return source == null ? BrickLightSource.NONE : capture.light(source, plate, observer.meshDepth() > 0);
    }

    private record SceneKey(UUID portal, Object view, UUID anchor, ViewWindow frame, boolean nativeMesh) {
    }
}
