package art.arcane.wormholes.render.clientview;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.WeakHashMap;

import org.bukkit.World;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Player;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.network.client.BrickLightSource;
import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.client.ClientViewProtocol;
import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.portal.AmbientOutlineGeometry;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.portal.rtp.RtpRimRenderer;
import art.arcane.wormholes.render.ClientViewPortalSource;
import art.arcane.wormholes.render.ClientViewSceneCapture;
import art.arcane.wormholes.render.FidelitySubsystem;
import art.arcane.wormholes.render.acoustics.AcousticsBridge;
import art.arcane.wormholes.render.client.ClientViewEntityTransform;
import art.arcane.wormholes.render.client.session.ClientViewEmitters;
import art.arcane.wormholes.render.client.session.ClientViewEntityFrames;
import art.arcane.wormholes.render.client.session.ClientViewSceneFx;
import art.arcane.wormholes.render.plate.ViewPlate;
import art.arcane.wormholes.render.view.ProjectionWorldView;

final class BukkitClientViewScene implements ClientViewEntityFrames.Scenes<ClientViewObserver>, ClientViewSceneFx.Effects<ClientViewObserver> {
    private static final int AMBIENT_CADENCE_TICKS = 1;

    private final BukkitClientViewPortalAccess portals;
    private final ClientViewSceneCapture capture;
    private final Map<PortalStructure, AmbientOutlineGeometry> outlines;

    BukkitClientViewScene(BukkitClientViewPortalAccess portals) {
        this.portals = Objects.requireNonNull(portals, "portals");
        this.capture = new ClientViewSceneCapture();
        this.outlines = Collections.synchronizedMap(new WeakHashMap<PortalStructure, AmbientOutlineGeometry>());
    }

    @Override
    public Object sceneKey(ClientViewObserver observer, UUID portalId) {
        ClientViewPortalSource source = portals.source(observer, portalId);
        ClientViewEntityTransform.Frame frame = source == null ? null : source.transformFrame();
        if (frame == null || !Settings.ENTITY_SPOOFING) {
            return null;
        }
        IPortal anchor = source.destinationAnchor();
        return new SceneKey(portalId, source.destinationView(), anchor == null ? null : anchor.getId(), frame.frontSide(), frame.mirror(),
            frame.quarterTurns(), Double.doubleToLongBits(frame.remoteOriginX()), Double.doubleToLongBits(frame.remoteOriginY()),
            Double.doubleToLongBits(frame.remoteOriginZ()));
    }

    @Override
    public List<EntityVisual> capture(ClientViewObserver observer, UUID portalId, long tick) {
        ClientViewPortalSource source = portals.source(observer, portalId);
        return source == null ? List.of() : capture.entities(source.portal(), source, tick);
    }

    @Override
    public boolean visible(ClientViewObserver observer, EntityVisual visual) {
        return capture.visible(observer.player(), visual.id());
    }

    @Override
    public boolean isObserver(ClientViewObserver observer, EntityVisual visual) {
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
        if (bed != null && emitters.size() < ClientViewProtocol.MAX_FX_EMITTERS) {
            emitters.add(ClientViewEmitters.sound(bed, AcousticsBridge.AMBIENT_INTERVAL_TICKS));
        }
        return emitters;
    }

    @Override
    public ClientViewSceneFx.Sample atmosphere(ClientViewObserver observer, UUID portalId, long tick) {
        ClientViewPortalSource source = portals.source(observer, portalId);
        World world = source == null ? null : source.destinationWorld();
        ProjectionWorldView view = source == null ? null : source.destinationView();
        if (world == null || view == null) {
            return null;
        }
        int darken = view.getSkyDarken();
        if (!source.relaysWeather()) {
            return new ClientViewSceneFx.Sample(0L, false, 0.0F, 0.0F, ClientViewMessage.Atmosphere.withSkyDarken(0, darken));
        }
        boolean clock = world.getEnvironment() == World.Environment.NORMAL;
        int flags = ClientViewMessage.Atmosphere.FLAG_WEATHER | (clock ? ClientViewMessage.Atmosphere.FLAG_TIME : 0);
        return new ClientViewSceneFx.Sample(clock ? world.getFullTime() : 0L, clock, world.hasStorm() ? 1.0F : 0.0F,
            world.isThundering() ? 1.0F : 0.0F, ClientViewMessage.Atmosphere.withSkyDarken(flags, darken));
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
        return source == null ? BrickLightSource.NONE : capture.light(source, plate);
    }

    private record SceneKey(UUID portal, Object view, UUID anchor, boolean front, boolean mirror, int quarterTurns, long originX, long originY,
                            long originZ) {
    }
}
