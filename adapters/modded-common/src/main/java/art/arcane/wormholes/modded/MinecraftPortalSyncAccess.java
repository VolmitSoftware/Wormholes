package art.arcane.wormholes.modded;

import art.arcane.wormholes.network.PortalInfo;
import art.arcane.wormholes.network.PortalSettingsCodec;
import art.arcane.wormholes.network.PortalSyncAccess;
import art.arcane.wormholes.network.PortalSyncService;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.render.atmosphere.AtmosphereMode;
import art.arcane.wormholes.render.acoustics.AcousticsProfile;
import art.arcane.wormholes.render.lod.LodProfile;
import art.arcane.wormholes.transit.MomentumPolicy;
import art.arcane.wormholes.transit.OrientationPolicy;
import art.arcane.wormholes.transit.TransitionProfile;
import java.util.List;
import art.arcane.wormholes.util.AxisAlignedBB;

import java.util.Map;
import java.util.UUID;

public final class MinecraftPortalSyncAccess implements PortalSyncAccess<MinecraftPortal> {
    private final WormholesModRuntime runtime;

    public MinecraftPortalSyncAccess(WormholesModRuntime runtime) {
        this.runtime = runtime;
    }

    @Override
    public boolean shareable(MinecraftPortal portal) {
        return gateway(portal) && portal.getGeometry().getArea() != null && runtime.portals().resolveLevel(portal) != null;
    }

    @Override
    public PortalInfo describe(MinecraftPortal portal) {
        PortalFrame frame = portal.getFrame();
        AxisAlignedBB area = portal.getGeometry().getArea();
        return new PortalInfo(portal.getId(), portal.getName(), portal.getWorldKey(), portal.getType().name(), portal.isOpen(),
            frame.getNormal().name(), frame.getRight().name(), frame.getUp().name(),
            portal.getOrigin().getX(), portal.getOrigin().getY(), portal.getOrigin().getZ(),
            Math.min(area.getXa(), area.getXb()), Math.min(area.getYa(), area.getYb()), Math.min(area.getZa(), area.getZb()),
            Math.max(area.getXa(), area.getXb()), Math.max(area.getYa(), area.getYb()), Math.max(area.getZa(), area.getZb()));
    }

    @Override
    public boolean supportsSettings(MinecraftPortal portal) {
        return true;
    }

    @Override
    public boolean settingsSyncEnabled(MinecraftPortal portal) {
        return portal.isSettingsSyncEnabled();
    }

    @Override
    public boolean receiverOnly(MinecraftPortal portal) {
        return "END_ARRIVAL".equals(portal.setting("dimensionalPortalKind"));
    }

    @Override
    public UUID counterpartId(MinecraftPortal portal) {
        return portal.setting("dimensionalCounterpartId") instanceof String id ? UUID.fromString(id) : null;
    }

    @Override
    public UUID forwardLinkId(MinecraftPortal portal) {
        return "UNIVERSAL".equals(portal.getTunnelType()) ? null : portal.getDestinationId();
    }

    @Override
    public boolean rtp(MinecraftPortal portal) {
        return portal.getType() == PortalType.RTP;
    }

    @Override
    public boolean gateway(MinecraftPortal portal) {
        return portal.getType() == PortalType.GATEWAY;
    }

    @Override
    public String linkedPeer(MinecraftPortal portal) {
        return "UNIVERSAL".equals(portal.getTunnelType()) ? portal.getDestinationServer() : null;
    }

    @Override
    public UUID remoteDestinationId(MinecraftPortal portal) {
        return "UNIVERSAL".equals(portal.getTunnelType()) ? portal.getDestinationId() : null;
    }

    @Override
    public Map<String, String> collectSettings(MinecraftPortal portal) {
        Map<String, String> settings = PortalSettingsCodec.collectSettings(portal);
        for (String key : List.of("fidelity.atmosphere", "fidelity.acoustics", "fidelity.lod", "fidelity.block_entities")) {
            Object value = portal.setting(key);
            if (value != null) {
                settings.put(key, value.toString());
            }
        }
        MomentumPolicy momentum = MomentumPolicy.decode(string(portal, "transit.momentum"));
        OrientationPolicy orientation = OrientationPolicy.parse(string(portal, "transit.orientation"), null);
        boolean membrane = Boolean.TRUE.equals(portal.setting("transit.membrane"));
        TransitionProfile profile = TransitionProfile.decode(string(portal, "transit.profile"));
        if (momentum != null || orientation != null || membrane || !profile.isNone()) {
            settings.put("transit.momentum", momentum == null ? "" : momentum.encode());
            settings.put("transit.orientation", orientation == null ? "" : orientation.name());
            settings.put("transit.membrane", Boolean.toString(membrane));
            settings.put("transit.profile", profile.encode());
        }
        return settings;
    }

    @Override
    public void applySettings(MinecraftPortal portal, Map<String, String> settings) {
        PortalSyncService.applyRemote(() -> {
            PortalSettingsCodec.applyToLocal(portal, settings);
            portal.setAtmosphereMode(AtmosphereMode.parse(settings.get("fidelity.atmosphere"), null));
            portal.setAcousticsProfile(AcousticsProfile.parse(settings.get("fidelity.acoustics"), null));
            portal.setLodProfile(LodProfile.parse(settings.get("fidelity.lod"), null));
            portal.setBlockEntities(settings.containsKey("fidelity.block_entities") ? Boolean.valueOf(settings.get("fidelity.block_entities")) : null);
            if (settings.containsKey("transit.momentum")) {
                portal.setMomentum(MomentumPolicy.decode(settings.get("transit.momentum")));
            }
            if (settings.containsKey("transit.orientation")) {
                portal.setOrientation(OrientationPolicy.parse(settings.get("transit.orientation"), null));
            }
            if (settings.containsKey("transit.membrane")) {
                portal.setMembrane(Boolean.parseBoolean(settings.get("transit.membrane")));
            }
            if (settings.containsKey("transit.profile")) {
                portal.setTransitionProfile(TransitionProfile.decode(settings.get("transit.profile")));
            }
            runtime.portals().save(portal);
        });
    }

    private static String string(MinecraftPortal portal, String key) {
        return portal.setting(key) instanceof String value ? value : "";
    }

    @Override
    public void refreshMenus(MinecraftPortal portal) {
        runtime.menus().refresh(portal.getId());
    }
}
