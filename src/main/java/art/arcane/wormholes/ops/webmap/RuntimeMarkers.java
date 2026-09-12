package art.arcane.wormholes.ops.webmap;

import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.Wormholes;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.ITunnel;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.UniversalTunnel;
import art.arcane.wormholes.portal.rtp.RtpSettings;
import art.arcane.wormholes.survival.doors.dimension.PocketWorldService;
import org.bukkit.Location;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Reads the live portal list into map markers. No chunk loads: everything comes from portal state. */
public final class RuntimeMarkers {
    /** The mirrored settings key the access lane writes when a portal is hidden from listings. */
    static final String LISTED_KEY = "access.listed";

    private RuntimeMarkers() {
    }

    public static List<MarkerSnapshot> markers() {
        PortalManager manager = Wormholes.portalManager;
        if (manager == null) {
            return List.of();
        }
        List<MarkerSnapshot> markers = new ArrayList<>();
        for (ILocalPortal portal : manager.getLocalPortals()) {
            Location center = portal.getCenter();
            if (center == null || center.getWorld() == null) {
                continue;
            }
            markers.add(new MarkerSnapshot(portal.getId(), portal.getName(), portal.getType().name(),
                center.getWorld().getName(), center.getX(), center.getY(), center.getZ(), portal.isOpen(),
                destination(portal), listed(portal), rtpRadius(portal),
                PocketWorldService.isPocketWorld(center.getWorld())));
        }
        return markers;
    }

    private static String destination(ILocalPortal portal) {
        ITunnel tunnel = portal.getTunnel();
        if (tunnel == null) {
            return "";
        }
        if (tunnel instanceof UniversalTunnel universal) {
            String server = universal.getServerName();
            IPortal remote = universal.getDestination();
            String name = remote == null ? String.valueOf(universal.getDestinationPortalId()) : remote.getName();
            return server == null ? name : server + ":" + name;
        }
        IPortal destination = tunnel.getDestination();
        return destination == null ? "" : destination.getName();
    }

    private static boolean listed(ILocalPortal portal) {
        if (!(portal instanceof LocalPortal local)) {
            return true;
        }
        Map<String, String> settings = new LinkedHashMap<>();
        local.extensions().collectSync(settings);
        String listed = settings.get(LISTED_KEY);
        return listed == null || !"false".equalsIgnoreCase(listed);
    }

    private static int rtpRadius(ILocalPortal portal) {
        if (portal.getType() != PortalType.RTP || !(portal instanceof LocalPortal local)) {
            return 0;
        }
        RtpSettings settings = local.getRtpSettings();
        return settings == null ? 0 : settings.getMaximumRadius();
    }
}
