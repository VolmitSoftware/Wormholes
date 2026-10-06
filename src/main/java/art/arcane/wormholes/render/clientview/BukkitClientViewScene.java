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
import art.arcane.optics.math.Vec3;
import art.arcane.optics.stream.ProjectionEnvironment;
import art.arcane.optics.client.ClientViewEnvironmentTransform;
import art.arcane.optics.stream.BrickLightSource;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.optics.stream.ViewStreamLimits;
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
import art.arcane.optics.client.ClientViewEntityTransform;
import art.arcane.wormholes.render.client.session.ClientViewEmitters;
import art.arcane.wormholes.render.client.session.ClientViewEntityFrames;
import art.arcane.wormholes.render.client.session.ClientViewSceneFx;
import art.arcane.optics.plate.ViewPlate;
import art.arcane.wormholes.render.view.ProjectionWorldView;

final class BukkitClientViewScene implements ClientViewEntityFrames.Scenes<ClientViewObserver>, ClientViewSceneFx.Effects<ClientViewObserver> {
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
        ClientViewEntityTransform.EntityFrame frame = frame(observer, source);
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
    public List<ClientViewMessage.FxEmitter> emitters(ClientViewObserver observer, UUID portalId, long tick) {
        ILocalPortal portal = portals.portal(observer, portalId);
        if (portal == null) {
            return List.of();
        }
        AcousticsBridge.Playback bed = bed(observer, portal);
        PortalStructure structure = portal.getStructure();
        if (!Settings.ENABLE_PARTICLES || structure == null || structure.getArea() == null) {
            return bed == null ? List.of() : List.of(ClientViewEmitters.sound(bed, AcousticsBridge.AMBIENT_INTERVAL_TICKS));
        }
        List<ClientViewMessage.FxEmitter> emitters = new ArrayList<ClientViewMessage.FxEmitter>(16);
        RtpRimRenderer.Sample rim = observer.rim(portalId);
        if (rim != null) {
            RtpRimRenderer.Color color = rim.color();
            ClientViewEmitters.rim(structure.getArea(), color.red(), color.green(), color.blue(), Settings.RTP_RIM_INTERVAL_TICKS, emitters);
        }
        List<double[]> outline = List.of();
        if (portal.getFrame() != null) {
            outline = outlines.computeIfAbsent(structure, ignored -> new AmbientOutlineGeometry())
                .points(structure.getRevision(), portal.getFrame().getNormal().getAxis(), structure.geometry());
        }
        ClientViewEmitters.ambient(new ClientViewEmitters.Ambient(portal.getAmbientStyle(), portal.getAmbientColor(), portal.isOpen(),
            Settings.AMBIENT_PARTICLE_INTERVAL_TICKS, AMBIENT_CADENCE_TICKS, structure.getArea(), outline), emitters);
        if (bed != null && emitters.size() < ViewStreamLimits.MAX_FX_EMITTERS) {
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
            ProjectionEnvironment environment = remote.environment(ProjectionEnvironment.Transform.IDENTITY);
            if (environment == null) {
                return null;
            }
            boolean weather = source.relaysWeather();
            boolean clock = weather && environment.sky().skybox() == ProjectionEnvironment.Skybox.OVERWORLD;
            int flags = (weather ? ClientViewMessage.Atmosphere.FLAG_WEATHER : 0) | (clock ? ClientViewMessage.Atmosphere.FLAG_TIME : 0);
            return new ClientViewSceneFx.Sample(clock ? environment.gameTime() : 0L, clock,
                weather ? environment.sky().rain() : 0.0F, weather ? environment.sky().thunder() : 0.0F,
                ClientViewMessage.Atmosphere.withSkyDarken(flags, darken));
        }
        if (world == null) {
            return null;
        }
        if (!source.relaysWeather()) {
            return new ClientViewSceneFx.Sample(0L, false, 0.0F, 0.0F, ClientViewMessage.Atmosphere.withSkyDarken(0, darken));
        }
        boolean clock = world.getEnvironment() == World.Environment.NORMAL;
        int flags = ClientViewMessage.Atmosphere.FLAG_WEATHER | (clock ? ClientViewMessage.Atmosphere.FLAG_TIME : 0);
        return new ClientViewSceneFx.Sample(clock ? world.getFullTime() : 0L, clock, world.hasStorm() ? 1.0F : 0.0F,
            world.isThundering() ? 1.0F : 0.0F, ClientViewMessage.Atmosphere.withSkyDarken(flags, darken));
    }

    @Override
    public boolean environmentUnavailable(ClientViewObserver observer, UUID parent, UUID portal) {
        return environments.unavailable(observer.id(), parent, portal);
    }

    @Override
    public ProjectionEnvironment environment(ClientViewObserver observer, UUID portalId, long tick) {
        return environment(observer, null, portalId, portals.source(observer, portalId), observer.eye(), tick);
    }

    @Override
    public ProjectionEnvironment nestedEnvironment(ClientViewObserver observer, UUID parent, UUID portalId, long tick) {
        return environment(observer, parent, portalId, portals.nestedSource(observer, parent, portalId), observer.reflectedEye(parent), tick);
    }

    private ProjectionEnvironment environment(ClientViewObserver observer, UUID parent, UUID portalId, ClientViewPortalSource source,
                                               Location eye, long tick) {
        if (source == null || eye == null || source.transformFrame() == null) {
            return null;
        }
        ProjectionEnvironment.Transform transform = ClientViewEnvironmentTransform.of(source.transformFrame());
        if (source.destinationView() instanceof RemoteWorldView remote) {
            return remote.environment(transform);
        }
        if (source.destinationWorld() == null) {
            return null;
        }
        Vec3 destinationEye = transform.destinationPoint(eye.getX(), eye.getY(), eye.getZ());
        return environments.capture(new BukkitEnvironmentCapture.Request(observer.id(), parent, portalId, source.destinationWorld(),
            destinationEye, transform, tick));
    }

    private static ClientViewEntityTransform.EntityFrame frame(ClientViewObserver observer, ClientViewPortalSource source) {
        ClientViewEntityTransform.EntityFrame frame = source == null ? null : source.transformFrame();
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

    private record SceneKey(UUID portal, Object view, UUID anchor, ClientViewEntityTransform.EntityFrame frame, boolean nativeMesh) {
    }
}
