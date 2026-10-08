package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.client.ClientPortal;
import art.arcane.wormholes.modded.client.ResidentLevels;
import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class PortalViews {
    public static final int DEFAULT_RECURSION = 3;
    private static final long RETURN_VIEW_MILLIS = 30_000L;

    private final ResidentLevels residents;
    private final Map<Object, PortalView> views = new HashMap<>();
    private final List<PortalView> current = new ArrayList<>();
    private final Set<Object> live = new HashSet<>();
    private PortalView returning;
    private long returningUntil;
    private ClientLevel lastHome;
    private Vec3d lastEye;

    public PortalViews(ResidentLevels residents) {
        this.residents = residents;
    }

    public void clear() {
        for (PortalView view : views.values()) {
            view.close();
        }
        views.clear();
        current.clear();
        closeReturn();
        lastHome = null;
        lastEye = null;
    }

    public void frame(ClientLevel home, Vec3d eye, Collection<TravelMessage.TravelBegin> arms, boolean mirrors, boolean recursion,
                      Collection<ClientPortal> portals) {
        long now = System.currentTimeMillis();
        if (lastHome != null && home != lastHome && lastEye != null) {
            startReturn(lastHome, home, lastEye, now);
        }
        live.clear();
        current.clear();
        String dimension = home.dimension().identifier().toString();
        for (TravelMessage.TravelBegin arm : arms) {
            if (!arm.sourceWorld().equals(dimension)) {
                continue;
            }
            ClientLevel destination = arm.resident() ? residents.level(arm.levelHandle()) : home;
            if (destination == null) {
                continue;
            }
            track(arm.token(), PortalView.Kind.ARM, home, destination, arm.sourceGeometry(), arm.sourceToDestination(), recursion);
        }
        if (mirrors) {
            for (ClientPortal portal : portals) {
                ApertureDescriptor geometry = portal.geometry();
                if (portal.nested() || !geometry.mirror()) {
                    continue;
                }
                track(new MirrorKey(portal.portalKey(), portal.geometryRevision()), PortalView.Kind.MIRROR, home, home, geometry,
                    Similarity.of(geometry.mirrorTransform(), 1.0D), recursion);
            }
        }
        keepReturn(home, now);
        Iterator<Map.Entry<Object, PortalView>> iterator = views.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Object, PortalView> entry = iterator.next();
            if (!live.contains(entry.getKey())) {
                entry.getValue().close();
                iterator.remove();
            }
        }
        lastHome = home;
        lastEye = eye;
    }

    public List<PortalView> current() {
        return current;
    }

    private void track(Object key, PortalView.Kind kind, ClientLevel home, ClientLevel destination, ApertureDescriptor geometry,
                       Similarity toDestination, boolean recursion) {
        PortalView view = views.get(key);
        if (view == null || view.source() != home || view.destination() != destination) {
            if (view != null) {
                view.close();
            }
            view = new PortalView(kind, key, home, destination, PortalSurface.of(geometry), toDestination, depth(geometry, recursion));
            views.put(key, view);
        }
        live.add(key);
        current.add(view);
    }

    private void startReturn(ClientLevel departed, ClientLevel home, Vec3d eye, long now) {
        PortalView crossed = null;
        double nearest = Double.POSITIVE_INFINITY;
        for (PortalView view : current) {
            if (view.kind() != PortalView.Kind.ARM || view.source() != departed || view.destination() != home) {
                continue;
            }
            double distance = Math.abs(view.surface().signedDistance(eye));
            if (distance < nearest) {
                nearest = distance;
                crossed = view;
            }
        }
        closeReturn();
        if (crossed == null || ClientWorldLoader.residentRenderer(departed) == null) {
            return;
        }
        returning = new PortalView(PortalView.Kind.RETURN, new ReturnKey(crossed.key()), home, departed,
            crossed.surface().through(crossed.toDestination()), crossed.toDestination().inverse(), crossed.recursion());
        returningUntil = now + RETURN_VIEW_MILLIS;
    }

    private void keepReturn(ClientLevel home, long now) {
        if (returning == null) {
            return;
        }
        if (now > returningUntil || returning.source() != home || ClientWorldLoader.residentRenderer(returning.destination()) == null
            || armed(returning.surface())) {
            closeReturn();
            return;
        }
        current.add(returning);
    }

    private boolean armed(PortalSurface surface) {
        for (PortalView view : current) {
            if (view.kind() == PortalView.Kind.ARM && view.surface().coincides(surface)) {
                return true;
            }
        }
        return false;
    }

    private void closeReturn() {
        if (returning != null) {
            returning.close();
            returning = null;
        }
    }

    private static int depth(ApertureDescriptor geometry, boolean recursion) {
        if (!recursion) {
            return 1;
        }
        return geometry.recursionDepth() > 0 ? geometry.recursionDepth() : DEFAULT_RECURSION;
    }

    private record MirrorKey(int portalKey, int geometryRevision) {
    }

    private record ReturnKey(Object crossed) {
    }
}
