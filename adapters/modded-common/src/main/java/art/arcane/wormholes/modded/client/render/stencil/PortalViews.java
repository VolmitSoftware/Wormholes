package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.frame.Frame;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.stream.EnvironmentState;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.wormholes.modded.client.ClientPortal;
import art.arcane.wormholes.modded.client.ClientViewSession;
import art.arcane.wormholes.modded.client.ResidentLevels;
import art.arcane.wormholes.modded.client.WormholesClient;
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
import java.util.Objects;
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
    private final Map<Object, List<PortalView>> children = new HashMap<>();
    private final Map<Object, Integer> streamKeys = new HashMap<>();
    private final List<CameraContinuation> continuations = new ArrayList<>();
    private final List<TraversalContinuation> traversalContinuations = new ArrayList<>();
    private final List<List<PortalView>> arrivalPaths = new ArrayList<>();
    private PortalView returning;
    private PortalView returnOrigin;
    private ClientLevel lastHome;
    private TraversalHistory traversalHistory;
    private long continuityEpoch;

    public PortalViews(ResidentLevels residents) {
        this.residents = residents;
    }

    public void clear() {
        for (PortalView view : views.values()) {
            view.close();
        }
        views.clear();
        current.clear();
        children.clear();
        streamKeys.clear();
        closeReturn();
        continuations.clear();
        traversalContinuations.clear();
        arrivalPaths.clear();
        invalidateHistory();
        lastHome = null;
    }

    public void frame(ClientLevel home, Vec3d eye, Collection<TravelMessage.TravelBegin> arms, boolean mirrors, boolean recursion,
                      Collection<ClientPortal> portals, Crossing crossing) {
        continuations.clear();
        traversalContinuations.clear();
        follow(home);
        live.clear();
        current.clear();
        children.clear();
        streamKeys.clear();
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
                track(new MirrorKey(dimension, cameraGeometry(geometry)), PortalView.Kind.MIRROR, home, home, geometry,
                    Similarity.of(PortalSurface.mirrorTransform(geometry), 1.0D), recursion);
            }
        }
        if (crossing != null && crossing.level() != home) {
            track(new CrossingKey(enteredKey(crossing.key(), arms)), PortalView.Kind.CROSSING, crossing.level(), home, crossing.surface().through(crossing.toward()),
                crossing.toward().inverse(), depth(crossing.surface().geometry(), recursion));
        }
        keepReturn(home, eye);
        if (recursion && !portals.isEmpty() && !current.isEmpty()) {
            discoverChildren(home, arms, portals, mirrors);
        }
        bindMirrorHistories(home, portals);
        validateHistory(home);
        continueArrival();
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

    public List<PortalView> children(PortalView parent) {
        return children.getOrDefault(parent.key(), List.of());
    }

    public int streamKey(PortalView view) {
        return streamKeys.getOrDefault(view.key(), 0);
    }

    public List<CameraContinuation> takeCameraContinuations() {
        List<CameraContinuation> pending = List.copyOf(continuations);
        continuations.clear();
        return pending;
    }

    public List<TraversalContinuation> takeTraversalContinuations() {
        List<TraversalContinuation> pending = List.copyOf(traversalContinuations);
        traversalContinuations.clear();
        return pending;
    }

    public long continuityEpoch() {
        return continuityEpoch;
    }

    public boolean hasTraversalHistory() {
        return traversalHistory != null;
    }

    public static boolean uniqueSourceCamera(PortalView view, List<PortalView> candidates) {
        PortalView match = null;
        for (PortalView candidate : candidates) {
            if (candidate.kind() != view.kind() || candidate.source() != view.source()
                || candidate.destination() != view.destination() || !candidate.toDestination().equals(view.toDestination())
                || candidate.surface().geometry().mirror() != view.surface().geometry().mirror()
                || !candidate.surface().sameOpening(view.surface())) {
                continue;
            }
            if (match != null && !match.key().equals(candidate.key())) {
                return false;
            }
            match = candidate;
        }
        return match != null && match.key().equals(view.key()) && match.recursion() == view.recursion();
    }

    public boolean retainsPreparation(PortalView anchor, ClientLevel home, Vec3d eye, double range, double reach) {
        if (anchor.failing(System.currentTimeMillis())) {
            return false;
        }
        if (anchor.source() == home) {
            return uniqueSourceCamera(anchor, current) && anchor.surface().servesEye(eye)
                && withinPreparationReach(anchor, eye, range, reach) && preparationAvailable(anchor.destination(), home);
        }
        if (traversalHistory == null || traversalHistory.crossed().destination() != home
            || traversalHistory.crossed().source() != anchor.source()
            || !uniqueSourceCamera(anchor, traversalHistory.roots())
            || !preparationAvailable(anchor.destination(), home)) {
            return false;
        }
        PortalView inverse = uniqueCamera(traversalHistory.inverse(), current);
        if (inverse == null || inverse.source() != home || inverse.destination() != anchor.source()
            || inverse.surface().distance(eye) > range) {
            return false;
        }
        Vec3d sourceEye = inverse.toDestination().point(eye);
        return anchor.surface().servesEye(sourceEye) && withinPreparationReach(anchor, sourceEye, range, reach);
    }

    public List<PortalView> crossingTraversal(List<Object> keys) {
        if (traversalHistory == null || keys.isEmpty()) {
            return List.of();
        }
        List<PortalView> previous = previousTraversal(keys);
        return previous == null ? List.of() : List.copyOf(previous);
    }

    public TraversalContinuation continuedTraversal(List<Object> keys) {
        if (traversalHistory == null || keys.isEmpty()) {
            return null;
        }
        List<PortalView> previous = previousTraversal(keys);
        if (previous == null) {
            return null;
        }
        List<PortalView> next = new ArrayList<>(previous.size() + 1);
        int offset;
        if (previous.getFirst().key().equals(traversalHistory.crossed().key())) {
            offset = 1;
        } else {
            PortalView inverse = uniqueCamera(traversalHistory.inverse(), current);
            if (inverse == null) {
                return null;
            }
            next.add(inverse);
            offset = 0;
        }
        for (int index = offset; index < previous.size(); index++) {
            List<PortalView> candidates = next.isEmpty() ? current : cameraCandidates(next.getLast(), children, current);
            PortalView match = uniqueCamera(previous.get(index), candidates);
            if (match == null || !withinRecursion(next, match)) {
                return null;
            }
            next.add(match);
        }
        return next.isEmpty() ? null : new TraversalContinuation(previous, next);
    }

    public static Object continuedKey(Object key, Map<Object, Object> replacements) {
        Object replacement = replacements.get(key);
        if (replacement != null) {
            return replacement;
        }
        if (key instanceof ChildKey child) {
            Object parent = continuedKey(child.parent(), replacements);
            return parent.equals(child.parent()) ? key : new ChildKey(parent, child.source(), child.destination(), child.geometry(),
                child.targetIdentity(), child.transform());
        }
        if (key instanceof ReturnKey returning) {
            Object crossed = continuedKey(returning.crossed(), replacements);
            return crossed.equals(returning.crossed()) ? key : new ReturnKey(crossed);
        }
        if (key instanceof CrossingKey crossing) {
            Object entered = continuedKey(crossing.entered(), replacements);
            return entered.equals(crossing.entered()) ? key : new CrossingKey(entered);
        }
        return key;
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
        List<PortalView> previousRoots = List.copyOf(current);
        Map<Object, List<PortalView>> previousChildren = new HashMap<>(children);
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
        PortalView entered = uniqueCamera(crossed, previousRoots);
        traversalHistory = new TraversalHistory(previousRoots, Map.copyOf(previousChildren), entered == null ? crossed : entered, returning);
        if (entered != null) {
            captureArrival(new ArrayList<>(List.of(entered)), previousChildren);
        }
    }

    public void resetReturn(ClientLevel home) {
        closeReturn();
        continuations.clear();
        traversalContinuations.clear();
        arrivalPaths.clear();
        invalidateHistory();
        current.clear();
        children.clear();
        streamKeys.clear();
        lastHome = home;
    }

    private void track(Object key, PortalView.Kind kind, ClientLevel home, ClientLevel destination, ApertureDescriptor geometry,
                       Similarity toDestination, boolean recursion) {
        track(key, kind, home, destination, PortalSurface.of(geometry), toDestination, depth(geometry, recursion));
    }

    private void track(Object key, PortalView.Kind kind, ClientLevel source, ClientLevel destination, PortalSurface surface,
                       Similarity toDestination, int recursion) {
        current.add(retain(key, kind, source, destination, surface, toDestination, recursion));
    }

    private void captureArrival(List<PortalView> traversal, Map<Object, List<PortalView>> previousChildren) {
        for (PortalView child : previousChildren.getOrDefault(traversal.getLast().key(), List.of())) {
            traversal.add(child);
            arrivalPaths.add(List.copyOf(traversal));
            captureArrival(traversal, previousChildren);
            traversal.removeLast();
        }
    }

    private List<PortalView> previousTraversal(List<Object> keys) {
        List<PortalView> previous = new ArrayList<>(keys.size());
        List<PortalView> candidates = traversalHistory.roots();
        for (Object key : keys) {
            PortalView match = null;
            for (PortalView candidate : candidates) {
                if (matchesTraversalKey(candidate, key)) {
                    if (match != null && !match.historyKey().equals(candidate.historyKey())) {
                        return null;
                    }
                    if (match == null) {
                        match = candidate;
                    }
                }
            }
            if (match == null || previous.isEmpty() && match.source() != traversalHistory.crossed().source()
                || !previous.isEmpty() && match.source() != previous.getLast().destination()) {
                return null;
            }
            previous.add(match);
            candidates = cameraCandidates(match, traversalHistory.children(), traversalHistory.roots());
        }
        return previous;
    }

    private static List<PortalView> cameraCandidates(PortalView parent, Map<Object, List<PortalView>> branches, List<PortalView> roots) {
        List<PortalView> nested = branches.getOrDefault(parent.key(), List.of());
        if (nested.isEmpty()) {
            return roots;
        }
        if (roots.isEmpty()) {
            return nested;
        }
        List<PortalView> candidates = new ArrayList<>(nested.size() + roots.size());
        candidates.addAll(nested);
        candidates.addAll(roots);
        return candidates;
    }

    private static boolean withinRecursion(List<PortalView> traversal, PortalView next) {
        if (traversal.size() >= next.recursion()) {
            return false;
        }
        for (PortalView ancestor : traversal) {
            if (traversal.size() >= ancestor.recursion()) {
                return false;
            }
        }
        return true;
    }

    private void validateHistory(ClientLevel home) {
        if (traversalHistory != null && (traversalHistory.crossed().destination() != home
            || ClientWorldLoader.residentRenderer(traversalHistory.crossed().source()) == null
            || returning == null && uniqueCamera(traversalHistory.inverse(), current) == null)) {
            invalidateHistory();
        }
    }

    private void invalidateHistory() {
        traversalHistory = null;
        continuityEpoch++;
    }

    private void continueArrival() {
        for (List<PortalView> previous : arrivalPaths) {
            List<PortalView> next = arrivalTraversal(previous);
            if (next != null) {
                traversalContinuations.add(new TraversalContinuation(previous, next));
            }
        }
        arrivalPaths.clear();
    }

    private List<PortalView> arrivalTraversal(List<PortalView> previous) {
        List<PortalView> next = new ArrayList<>(previous.size() - 1);
        List<PortalView> candidates = current;
        for (int index = 1; index < previous.size(); index++) {
            PortalView match = uniqueCamera(previous.get(index), candidates);
            if (match == null) {
                return null;
            }
            next.add(match);
            if (index + 1 < previous.size()) {
                candidates = new ArrayList<>(children(match));
                candidates.addAll(current);
            }
        }
        return next;
    }

    private static PortalView uniqueCamera(PortalView previous, List<PortalView> candidates) {
        PortalView matched = null;
        for (PortalView candidate : candidates) {
            if (sameCamera(previous, candidate)) {
                if (matched != null && !matched.historyKey().equals(candidate.historyKey())) {
                    return null;
                }
                if (matched == null) {
                    matched = candidate;
                }
            }
        }
        return matched;
    }

    private static boolean matchesTraversalKey(PortalView view, Object key) {
        return key instanceof NativeMirrorHistory.Key ? view.historyKey().equals(key) : view.key().equals(key);
    }

    private void bindMirrorHistories(ClientLevel home, Collection<ClientPortal> portals) {
        WormholesClient client = WormholesClient.instance();
        if (client == null) {
            return;
        }
        rebindMirrorHistories(mirrorProofs(home, portals, client.session()));
    }

    private Map<Object, NativeMirrorHistory.Proof> mirrorProofs(ClientLevel home, Collection<ClientPortal> portals, ClientViewSession session) {
        Map<Integer, ClientPortal> streams = new HashMap<>(portals.size());
        for (ClientPortal portal : portals) {
            streams.put(portal.portalKey(), portal);
        }
        Map<Object, NativeMirrorHistory.Proof> candidates = new HashMap<>();
        for (PortalView view : current) {
            if (view.key() instanceof MirrorKey) {
                ClientPortal root = mirrorRoot(view, portals, session);
                NativeMirrorHistory.Proof proof = root == null || session.affine(root.portalKey()) != null ? null
                    : NativeMirrorHistory.prove(view, root, session.environment(root.portalKey()), true);
                if (proof != null) {
                    candidates.put(view.key(), proof);
                }
            }
        }
        for (PortalView view : views.values()) {
            if (!live.contains(view.key()) || !(view.key() instanceof ChildKey) || view.kind() != PortalView.Kind.MIRROR) {
                continue;
            }
            ClientPortal portal = streams.get(streamKey(view));
            boolean unique = uniqueMirrorTarget(view, home, candidates);
            NativeMirrorHistory.Proof proof = portal == null || session.affine(portal.portalKey()) != null ? null
                : NativeMirrorHistory.prove(view, portal, session.environment(portal.portalKey()), unique);
            if (proof != null) {
                candidates.put(view.key(), proof);
            }
        }
        return NativeMirrorHistory.unambiguous(candidates);
    }

    private void rebindMirrorHistories(Map<Object, NativeMirrorHistory.Proof> proven) {
        Map<Object, PortalView> replacements = new HashMap<>();
        for (Map.Entry<Object, PortalView> entry : views.entrySet()) {
            PortalView view = entry.getValue();
            if (!live.contains(entry.getKey()) || view.kind() != PortalView.Kind.MIRROR
                || Objects.equals(view.mirrorHistory(), proven.get(entry.getKey()))) {
                continue;
            }
            PortalView replacement = new PortalView(view.kind(), new PortalView.Identity(view.key(), proven.get(entry.getKey())),
                view.source(), view.destination(), view.surface(), view.toDestination(), view.recursion());
            view.close();
            entry.setValue(replacement);
            replacements.put(entry.getKey(), replacement);
        }
        if (replacements.isEmpty()) {
            return;
        }
        for (int index = 0; index < current.size(); index++) {
            PortalView view = current.get(index);
            current.set(index, replacements.getOrDefault(view.key(), view));
        }
        for (Map.Entry<Object, List<PortalView>> branch : children.entrySet()) {
            List<PortalView> rebound = new ArrayList<>(branch.getValue().size());
            for (PortalView child : branch.getValue()) {
                rebound.add(replacements.getOrDefault(child.key(), child));
            }
            branch.setValue(List.copyOf(rebound));
        }
    }

    private static ClientPortal mirrorRoot(PortalView view, Collection<ClientPortal> portals, ClientViewSession session) {
        ClientPortal matched = null;
        ApertureDescriptor geometry = cameraGeometry(view.surface().geometry());
        for (ClientPortal portal : portals) {
            if (!portal.nested() && portal.geometry().mirror() && session.matchesSource(portal.portalKey(), view.source())
                && geometry.equals(cameraGeometry(portal.geometry()))) {
                if (matched != null) {
                    return null;
                }
                matched = portal;
            }
        }
        return matched;
    }

    private boolean uniqueMirrorTarget(PortalView child, ClientLevel home, Map<Object, NativeMirrorHistory.Proof> candidates) {
        if (child.source() != home) {
            return true;
        }
        ApertureDescriptor geometry = cameraGeometry(child.surface().geometry());
        PortalView matched = null;
        for (PortalView root : current) {
            if (root.kind() == PortalView.Kind.MIRROR && root.source() == child.source()
                && geometry.equals(cameraGeometry(root.surface().geometry()))) {
                if (matched != null || !candidates.containsKey(root.key())) {
                    return false;
                }
                matched = root;
            }
        }
        return true;
    }

    private PortalView retain(Object key, PortalView.Kind kind, ClientLevel source, ClientLevel destination, PortalSurface surface,
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
        return view;
    }

    private void discoverChildren(ClientLevel home, Collection<TravelMessage.TravelBegin> arms, Collection<ClientPortal> portals,
                                  boolean mirrors) {
        ClientViewSession session = WormholesClient.instance().session();
        Map<String, ClientLevel> levels = new HashMap<>();
        Set<String> ambiguous = new HashSet<>();
        registerLevel(home, levels, ambiguous);
        for (PortalView view : current) {
            registerLevel(view.source(), levels, ambiguous);
            registerLevel(view.destination(), levels, ambiguous);
        }
        for (TravelMessage.TravelBegin arm : arms) {
            if (arm.resident()) {
                registerLevel(residents.level(arm.levelHandle()), levels, ambiguous);
            }
        }
        Map<Integer, List<ClientPortal>> branches = new HashMap<>();
        for (ClientPortal portal : portals) {
            branches.computeIfAbsent(portal.geometry().parentPortalKey(), ignored -> new ArrayList<>()).add(portal);
        }
        Discovery discovery = new Discovery(branches, levels, ambiguous, session, mirrors);
        for (PortalView view : current) {
            ClientPortal root = streamRoot(view, branches.getOrDefault(0, List.of()), discovery);
            if (root == null) {
                continue;
            }
            streamKeys.put(view.key(), root.portalKey());
            descend(view, root.portalKey(), 1, discovery);
        }
    }

    private ClientPortal streamRoot(PortalView view, List<ClientPortal> roots, Discovery discovery) {
        ClientViewSession session = discovery.session();
        boolean placed = view.kind() == PortalView.Kind.RETURN || view.kind() == PortalView.Kind.CROSSING;
        ApertureDescriptor geometry = cameraGeometry(view.surface().geometry());
        ClientPortal matched = null;
        for (ClientPortal portal : roots) {
            if (session.affine(portal.portalKey()) != null || !session.matchesSource(portal.portalKey(), view.source())
                || portal.geometry().mirror() != view.surface().geometry().mirror()) {
                continue;
            }
            EnvironmentState environment = session.environment(portal.portalKey());
            if (placed) {
                if (!placedRoot(view, portal, environment, discovery)) {
                    continue;
                }
            } else if (!view.surface().placement().equals(Similarity.IDENTITY) || view.surface().reversed()
                || !geometry.equals(cameraGeometry(portal.geometry()))) {
                continue;
            }
            if (environment != null && (!environment.world().dimensionKey().equals(view.destination().dimension().identifier().toString())
                || !sameTransform(view.toDestination(), toward(portal.geometry(), environment)))) {
                continue;
            }
            if (matched != null) {
                return null;
            }
            matched = portal;
        }
        return matched;
    }

    private static boolean placedRoot(PortalView view, ClientPortal portal, EnvironmentState environment, Discovery discovery) {
        if (environment == null || portal.geometry().mirror()
            || !view.surface().sameOpening(PortalSurface.of(portal.geometry()))) {
            return false;
        }
        String destination = environment.world().dimensionKey();
        return destination.equals(view.destination().dimension().identifier().toString())
            && destination.equals(discovery.session().localDestinationWorld(portal.portalKey()));
    }

    private void descend(PortalView parent, int parentKey, int depth, Discovery discovery) {
        if (depth >= parent.recursion() || depth >= ViewStreamLimits.MAX_GEOMETRY_DEPTH) {
            return;
        }
        List<PortalView> discovered = new ArrayList<>();
        for (ClientPortal portal : discovery.branches().getOrDefault(parentKey, List.of())) {
            ApertureDescriptor geometry = portal.geometry();
            EnvironmentState environment = discovery.session().environment(portal.portalKey());
            if (discovery.session().affine(portal.portalKey()) != null || geometry.mirror() && !discovery.mirrors() || environment == null) {
                continue;
            }
            String world = environment.world().dimensionKey();
            ClientLevel destination = childDestination(parent, portal, world, discovery);
            if (destination == null) {
                continue;
            }
            int ceiling = Math.min(parent.recursion(), depth + Math.max(1, geometry.recursionDepth()));
            Similarity transform = geometry.mirror() ? Similarity.of(PortalSurface.mirrorTransform(geometry), 1.0D)
                : toward(geometry, environment);
            if (geometry.mirror() && !sameTransform(transform, toward(geometry, environment))) {
                continue;
            }
            Object key = new ChildKey(parent.key(), parent.destination().dimension().identifier().toString(),
                world, cameraGeometry(geometry), geometry.targetIdentity(), transform);
            PortalView child = retain(key, geometry.mirror() ? PortalView.Kind.MIRROR : PortalView.Kind.ARM, parent.destination(),
                destination, PortalSurface.of(geometry), transform, ceiling);
            streamKeys.put(key, portal.portalKey());
            discovered.add(child);
            if (geometry.recursionDepth() > 0) {
                descend(child, portal.portalKey(), depth + 1, discovery);
            }
        }
        if (!discovered.isEmpty()) {
            children.put(parent.key(), List.copyOf(discovered));
        }
    }

    private static ClientLevel childDestination(PortalView parent, ClientPortal portal, String world, Discovery discovery) {
        ClientLevel source = parent.destination();
        if (portal.geometry().mirror()) {
            return world.equals(source.dimension().identifier().toString()) ? source : null;
        }
        if (!world.equals(discovery.session().localDestinationWorld(portal.portalKey()))) {
            return null;
        }
        if (world.equals(source.dimension().identifier().toString())) {
            return source;
        }
        return discovery.ambiguous().contains(world) ? null : discovery.levels().get(world);
    }

    private static void registerLevel(ClientLevel level, Map<String, ClientLevel> levels, Set<String> ambiguous) {
        if (level == null) {
            return;
        }
        String world = level.dimension().identifier().toString();
        ClientLevel previous = levels.putIfAbsent(world, level);
        if (previous != null && previous != level) {
            ambiguous.add(world);
        }
    }

    static Similarity toward(ApertureDescriptor geometry, EnvironmentState environment) {
        if (environment.scale() == 1.0F) {
            return Similarity.of(environment.transform().inverse(), 1.0D);
        }
        Frame canonical = Frame.canonical(geometry.facingDirection());
        double[] coordinates = {geometry.originX(), geometry.originY(), geometry.originZ()};
        coordinates[canonical.getRight().axisIndex()] += geometry.apertureWidth() / 2.0D;
        coordinates[canonical.getUp().axisIndex()] += geometry.apertureHeight() / 2.0D;
        coordinates[geometry.facingDirection().axisIndex()] = geometry.planeCoordinate();
        Vec3d center = new Vec3d(coordinates[0], coordinates[1], coordinates[2]);
        Frame frame = geometry.frame();
        Similarity content = Similarity.between(frame, center, frame, center, environment.scale());
        return content.compose(Similarity.of(environment.transform(), 1.0D)).inverse();
    }

    private static boolean sameTransform(Similarity first, Similarity second) {
        return first.rigid().permutation() == second.rigid().permutation()
            && Math.abs(first.scale() - second.scale()) < 1.0E-6D
            && first.point(ZERO).distance(second.point(ZERO)) < 1.0E-6D;
    }

    private void keepReturn(ClientLevel home, Vec3d eye) {
        if (returning == null) {
            return;
        }
        if (returning.source() != home || returning.surface().distance(eye) > RETURN_VIEW_BLOCKS
            || ClientWorldLoader.residentRenderer(returning.destination()) == null) {
            closeReturn();
            invalidateHistory();
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
        if (first.kind() == PortalView.Kind.MIRROR || second.kind() == PortalView.Kind.MIRROR) {
            return first.kind() == second.kind() && first.source() == second.source() && first.destination() == second.destination()
                && first.historyKey().equals(second.historyKey()) && first.toDestination().equals(second.toDestination())
                && cameraGeometry(first.surface().geometry()).equals(cameraGeometry(second.surface().geometry()))
                && first.surface().placement().equals(second.surface().placement()) && first.surface().reversed() == second.surface().reversed();
        }
        Similarity from = first.toDestination();
        Similarity to = second.toDestination();
        if (first.source() != second.source() || first.destination() != second.destination()
            || !sameTransform(from, to)
            || first.key() instanceof ChildKey firstChild && second.key() instanceof ChildKey secondChild
                && firstChild.targetIdentity() != secondChild.targetIdentity()) {
            return false;
        }
        return first.surface().geometry().mirror() == second.surface().geometry().mirror()
            && first.surface().sameOpening(second.surface());
    }

    private static boolean preparationAvailable(ClientLevel destination, ClientLevel home) {
        return destination == home || destination == ClientWorldLoader.mainLevel()
            || ClientWorldLoader.residentRenderer(destination) != null;
    }

    private static boolean withinPreparationReach(PortalView anchor, Vec3d eye, double range, double reach) {
        if (anchor.surface().distance(eye) > range) {
            return false;
        }
        if (anchor.destination() != anchor.source()) {
            return true;
        }
        Vec3d mapped = anchor.toDestination().point(anchor.surface().planePoint());
        double dx = mapped.x() - eye.x();
        double dz = mapped.z() - eye.z();
        return dx * dx + dz * dz <= reach * reach;
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
        return NativeMirrorHistory.geometry(geometry);
    }

    private record ArmKey(UUID portal, ApertureDescriptor geometry, Similarity transform) {
        ArmKey {
            geometry = cameraGeometry(geometry);
        }
    }

    private record MirrorKey(String world, ApertureDescriptor geometry) {
    }

    private record ChildKey(Object parent, String source, String destination, ApertureDescriptor geometry, long targetIdentity,
                            Similarity transform) {
    }

    private record Discovery(Map<Integer, List<ClientPortal>> branches, Map<String, ClientLevel> levels, Set<String> ambiguous,
                             ClientViewSession session, boolean mirrors) {
    }

    private record TraversalHistory(List<PortalView> roots, Map<Object, List<PortalView>> children, PortalView crossed, PortalView inverse) {
    }

    private record ReturnKey(Object crossed) {
    }

    private record CrossingKey(Object entered) {
    }

    public record Crossing(Object key, ClientLevel level, PortalSurface surface, Similarity toward) {
    }

    public record CameraContinuation(PortalView previous, PortalView next) {
    }

    public record TraversalContinuation(List<PortalView> previous, List<PortalView> next) {
        public TraversalContinuation {
            previous = List.copyOf(previous);
            next = List.copyOf(next);
            if (previous.isEmpty() || next.isEmpty()) {
                throw new IllegalArgumentException("Camera continuation cannot contain the main camera");
            }
        }
    }
}
