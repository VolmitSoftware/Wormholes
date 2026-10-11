package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.client.ClientPortal;
import art.arcane.wormholes.modded.client.ClientViewSession;
import art.arcane.wormholes.modded.client.ResidentLevels;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PortalViews {
    public static final int DEFAULT_RECURSION = 3;
    private static final double RETURN_VIEW_BLOCKS = 16.0D;
    private static final Vec3d ZERO = new Vec3d(0.0D, 0.0D, 0.0D);

    private final ResidentLevels residents;
    private final Map<Object, PortalView> views = new HashMap<>();
    private final List<PortalView> current = new ArrayList<>();
    private final Set<Object> live = new HashSet<>();
    private final List<CameraContinuation> continuations = new ArrayList<>();
    private PortalView returning;
    private PortalView returnOrigin;
    private ClientLevel lastHome;

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
        continuations.clear();
        lastHome = null;
    }

    public void frame(ClientLevel home, Vec3d eye, Collection<TravelMessage.TravelBegin> arms, boolean mirrors, boolean recursion,
                      Collection<ClientPortal> portals, Crossing crossing) {
        continuations.clear();
        follow(home);
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
            Similarity transform = arm.sourceToDestination();
            track(new ArmKey(arm.sourcePortal(), arm.sourceGeometry(), transform), PortalView.Kind.ARM, home, destination,
                arm.sourceGeometry(), transform, recursion);
        }
        if (mirrors) {
            ClientViewSession session = WormholesClient.instance().session();
            for (ClientPortal portal : portals) {
                ApertureDescriptor geometry = portal.geometry();
                if (portal.nested() || !geometry.mirror() || !session.matchesSource(portal.portalKey(), home)) {
                    continue;
                }
                track(new MirrorKey(cameraGeometry(geometry)), PortalView.Kind.MIRROR, home, home, geometry,
                    Similarity.of(geometry.mirrorTransform(), 1.0D), recursion);
            }
        }
        if (crossing != null && crossing.level() != home) {
            track(new CrossingKey(enteredKey(crossing.key(), arms)), PortalView.Kind.CROSSING, crossing.level(), home, crossing.surface().through(crossing.toward()),
                crossing.toward().inverse(), depth(crossing.surface().geometry(), recursion));
        }
        keepReturn(home, eye);
        Iterator<Map.Entry<Object, PortalView>> iterator = views.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Object, PortalView> entry = iterator.next();
            if (!live.contains(entry.getKey())) {
                entry.getValue().close();
                iterator.remove();
            }
        }
        lastHome = home;
    }

    public List<PortalView> current() {
        return current;
    }

    public List<CameraContinuation> takeCameraContinuations() {
        List<CameraContinuation> pending = List.copyOf(continuations);
        continuations.clear();
        return pending;
    }

    public static List<PortalView> futureTravelViews(PortalView approached) {
        if (approached.source() == approached.destination()
            || approached.kind() != PortalView.Kind.ARM && approached.kind() != PortalView.Kind.RETURN) {
            return List.of();
        }
        PortalView returning = returnView(approached);
        PortalView reverse = new PortalView(PortalView.Kind.CROSSING, new CrossingKey(approached.key()), approached.destination(),
            approached.source(), returning.surface(), returning.toDestination(), approached.recursion());
        if (approached.kind() == PortalView.Kind.RETURN) {
            return List.of(reverse);
        }
        PortalView forward = new PortalView(PortalView.Kind.CROSSING, new CrossingKey(returning.key()), approached.source(),
            approached.destination(), returning.surface().through(returning.toDestination()), returning.toDestination().inverse(),
            approached.recursion());
        return List.of(forward, reverse, returning);
    }

    public PortalView follow(ClientLevel home) {
        if (home == null) {
            return null;
        }
        if (home != lastHome) {
            resetReturn(home);
        }
        return returning != null && returning.source() == home ? returning : null;
    }

    public void crossed(TravelMessage.TravelBegin arm, ClientLevel departed, ClientLevel home, boolean recursion) {
        resetReturn(home);
        if (arm == null || departed == null || home == null || departed == home || !arm.resident()
            || !arm.sourceWorld().equals(departed.dimension().identifier().toString())
            || residents.level(arm.levelHandle()) != home || ClientWorldLoader.residentRenderer(departed) == null) {
            return;
        }
        Similarity transform = arm.sourceToDestination();
        ArmKey key = new ArmKey(arm.sourcePortal(), arm.sourceGeometry(), transform);
        PortalView crossed = views.get(key);
        int recursionDepth = depth(arm.sourceGeometry(), recursion);
        if (crossed == null || crossed.source() != departed || crossed.destination() != home
            || !crossed.surface().geometry().equals(arm.sourceGeometry()) || crossed.recursion() != recursionDepth) {
            crossed = new PortalView(PortalView.Kind.ARM, key, departed, home, PortalSurface.of(arm.sourceGeometry()), transform,
                recursionDepth);
        }
        returning = returnView(crossed);
        returnOrigin = crossed;
    }

    public void resetReturn(ClientLevel home) {
        closeReturn();
        continuations.clear();
        current.clear();
        lastHome = home;
    }

    private void track(Object key, PortalView.Kind kind, ClientLevel home, ClientLevel destination, ApertureDescriptor geometry,
                       Similarity toDestination, boolean recursion) {
        track(key, kind, home, destination, PortalSurface.of(geometry), toDestination, depth(geometry, recursion));
    }

    private void track(Object key, PortalView.Kind kind, ClientLevel source, ClientLevel destination, PortalSurface surface,
                       Similarity toDestination, int recursion) {
        PortalView view = views.get(key);
        if (view == null || view.source() != source || view.destination() != destination
            || !view.surface().geometry().equals(surface.geometry()) || !view.surface().placement().equals(surface.placement())
            || view.surface().reversed() != surface.reversed() || !view.toDestination().equals(toDestination)
            || view.recursion() != Math.max(1, recursion)) {
            if (view != null) {
                view.close();
            }
            view = new PortalView(kind, key, source, destination, surface, toDestination, recursion);
            views.put(key, view);
        }
        live.add(key);
        current.add(view);
    }

    private void keepReturn(ClientLevel home, Vec3d eye) {
        if (returning == null) {
            return;
        }
        if (returning.source() != home || returning.surface().distance(eye) > RETURN_VIEW_BLOCKS
            || ClientWorldLoader.residentRenderer(returning.destination()) == null) {
            closeReturn();
            return;
        }
        PortalView armed = armed(returning);
        if (armed != null) {
            continueCameras(armed);
            closeReturn();
            return;
        }
        current.add(returning);
    }

    private PortalView armed(PortalView returning) {
        PortalView matched = null;
        for (PortalView view : current) {
            if (view.kind() == PortalView.Kind.ARM && sameCamera(returning, view)) {
                if (matched != null) {
                    return null;
                }
                matched = view;
            }
        }
        return matched;
    }

    private void continueCameras(PortalView armed) {
        List<PortalView> previous = new ArrayList<>(4);
        previous.add(returnOrigin);
        previous.addAll(futureTravelViews(returnOrigin));
        List<PortalView> next = new ArrayList<>(4);
        next.add(armed);
        next.addAll(futureTravelViews(armed));
        if (previous.size() != 4 || next.size() != 4) {
            return;
        }
        int[] replacements = {3, 2, 1, 0};
        for (int index = 0; index < replacements.length; index++) {
            if (!sameCamera(previous.get(index), next.get(replacements[index]))) {
                return;
            }
        }
        for (int index = 0; index < replacements.length; index++) {
            continuations.add(new CameraContinuation(previous.get(index), next.get(replacements[index])));
        }
    }

    static boolean sameCamera(PortalView first, PortalView second) {
        Similarity from = first.toDestination();
        Similarity to = second.toDestination();
        if (first.source() != second.source() || first.destination() != second.destination()
            || from.scale() != to.scale() || from.rigid().permutation() != to.rigid().permutation()
            || !samePoint(from.point(ZERO), to.point(ZERO))) {
            return false;
        }
        PortalSurface left = first.surface();
        PortalSurface right = second.surface();
        ApertureDescriptor a = left.geometry();
        ApertureDescriptor b = right.geometry();
        return a.apertureWidth() == b.apertureWidth() && a.apertureHeight() == b.apertureHeight()
            && a.shape().equals(b.shape()) && Arrays.equals(a.apertureMask(), b.apertureMask())
            && samePoint(left.servedNormal(), right.servedNormal()) && samePoint(left.planePoint(), right.planePoint())
            && sameSurfacePoint(left, right, 0.0D, 0.0D)
            && sameSurfacePoint(left, right, a.apertureWidth(), 0.0D)
            && sameSurfacePoint(left, right, 0.0D, a.apertureHeight());
    }

    private static boolean sameSurfacePoint(PortalSurface first, PortalSurface second, double column, double row) {
        return samePoint(first.placement().point(first.aperture().point(column, row)),
            second.placement().point(second.aperture().point(column, row)));
    }

    private static boolean samePoint(Vec3d first, Vec3d second) {
        return first.x() == second.x() && first.y() == second.y() && first.z() == second.z();
    }

    private void closeReturn() {
        if (returning != null) {
            returning.close();
            returning = null;
        }
        returnOrigin = null;
    }

    private static PortalView returnView(PortalView crossed) {
        return new PortalView(PortalView.Kind.RETURN, new ReturnKey(crossed.key()), crossed.destination(), crossed.source(),
            crossed.surface().through(crossed.toDestination()), crossed.toDestination().inverse(), crossed.recursion());
    }

    private static Object enteredKey(Object entered, Collection<TravelMessage.TravelBegin> arms) {
        for (TravelMessage.TravelBegin arm : arms) {
            if (arm.token().equals(entered)) {
                return new ArmKey(arm.sourcePortal(), arm.sourceGeometry(), arm.sourceToDestination());
            }
        }
        return entered;
    }

    private static int depth(ApertureDescriptor geometry, boolean recursion) {
        if (!recursion) {
            return 1;
        }
        return geometry.recursionDepth() > 0 ? geometry.recursionDepth() : DEFAULT_RECURSION;
    }

    private static ApertureDescriptor cameraGeometry(ApertureDescriptor geometry) {
        return new ApertureDescriptor(geometry.originX(), geometry.originY(), geometry.originZ(), geometry.facing(), geometry.frontSide(),
            geometry.quarterTurns(), geometry.mirror(), geometry.apertureWidth(), geometry.apertureHeight(), geometry.apertureMask(),
            geometry.shape(), 0.0F, 0.0F, 0.0F, 0, 0, 0, 0, 0, 0, 0, 0, geometry.planeOffset(), 0, 0L, List.of());
    }

    private record ArmKey(UUID portal, ApertureDescriptor geometry, Similarity transform) {
        ArmKey {
            geometry = cameraGeometry(geometry);
        }
    }

    private record MirrorKey(ApertureDescriptor geometry) {
    }

    private record ReturnKey(Object crossed) {
    }

    private record CrossingKey(Object entered) {
    }

    public record Crossing(Object key, ClientLevel level, PortalSurface surface, Similarity toward) {
    }

    public record CameraContinuation(PortalView previous, PortalView next) {
    }
}
