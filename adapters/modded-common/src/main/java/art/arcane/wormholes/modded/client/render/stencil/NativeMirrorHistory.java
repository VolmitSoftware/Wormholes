package art.arcane.wormholes.modded.client.render.stencil;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.frame.Similarity;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.stream.EnvironmentState;
import art.arcane.wormholes.modded.client.ClientPortal;
import net.minecraft.client.multiplayer.ClientLevel;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class NativeMirrorHistory {
    private static final Vec3d ZERO = new Vec3d(0.0D, 0.0D, 0.0D);

    private NativeMirrorHistory() {
    }

    static Proof prove(PortalView view, ClientPortal portal, EnvironmentState environment, boolean uniqueTarget) {
        if (!uniqueTarget || portal == null || view.kind() != PortalView.Kind.MIRROR || view.source() != view.destination()
            || !view.surface().geometry().equals(portal.geometry()) || !portal.geometry().mirror()
            || !view.surface().placement().equals(Similarity.IDENTITY) || view.surface().reversed()) {
            return null;
        }
        ApertureDescriptor geometry = portal.geometry();
        Similarity reflection = Similarity.of(PortalSurface.mirrorTransform(geometry), 1.0D);
        if (!exactTransform(view.toDestination(), reflection)
            || portal.nested() && environment == null
            || environment != null && (!environment.world().dimensionKey().equals(view.source().dimension().identifier().toString())
                || !exactTransform(PortalViews.toward(geometry, environment), reflection))) {
            return null;
        }
        return new Proof(new Key(view, geometry(geometry)),
            geometry.targetIdentity(), geometry.parentPortalKey());
    }

    static Map<Object, Proof> unambiguous(Map<Object, Proof> candidates) {
        Map<Long, Key> targets = new HashMap<>();
        Map<Branch, Long> branches = new HashMap<>();
        Set<Key> ambiguous = new HashSet<>();
        for (Proof proof : candidates.values()) {
            if (proof.targetIdentity() != 0L) {
                Key previous = targets.putIfAbsent(proof.targetIdentity(), proof.key());
                if (previous != null && !previous.sameTarget(proof.key())) {
                    ambiguous.add(previous);
                    ambiguous.add(proof.key());
                }
            }
            if (proof.parentStreamKey() != 0) {
                Long previous = branches.putIfAbsent(new Branch(proof.key(), proof.parentStreamKey()), proof.targetIdentity());
                if (previous != null && previous.longValue() != proof.targetIdentity()) {
                    ambiguous.add(proof.key());
                }
            }
        }
        Map<Object, Proof> proven = new HashMap<>(candidates.size());
        for (Map.Entry<Object, Proof> candidate : candidates.entrySet()) {
            if (!ambiguous.contains(candidate.getValue().key())) {
                proven.put(candidate.getKey(), candidate.getValue());
            }
        }
        return proven;
    }

    static ApertureDescriptor geometry(ApertureDescriptor geometry) {
        return physicalGeometry(geometry, geometry.frontSide());
    }

    private static ApertureDescriptor physicalGeometry(ApertureDescriptor geometry, boolean frontSide) {
        return new ApertureDescriptor(geometry.originX(), geometry.originY(), geometry.originZ(), geometry.facing(), frontSide,
            geometry.quarterTurns(), geometry.mirror(), geometry.apertureWidth(), geometry.apertureHeight(), geometry.apertureMask(),
            geometry.shape(), 0.0F, 0.0F, 0.0F, 0, 0, 0, 0, 0, 0, 0, 0, geometry.planeOffset(), 0, 0L, List.of());
    }

    private static boolean exactTransform(Similarity first, Similarity second) {
        return first.rigid().permutation() == second.rigid().permutation()
            && Double.compare(first.scale(), second.scale()) == 0 && first.point(ZERO).equals(second.point(ZERO));
    }

    public static final class Key {
        private final ClientLevel level;
        private final ApertureDescriptor geometry;
        private final ApertureDescriptor targetGeometry;
        private final Similarity placement;
        private final boolean reversed;
        private final Similarity reflection;
        private final int hash;

        private Key(PortalView view, ApertureDescriptor geometry) {
            this.level = Objects.requireNonNull(view.source());
            this.geometry = Objects.requireNonNull(geometry);
            this.targetGeometry = physicalGeometry(geometry, true);
            this.placement = Objects.requireNonNull(view.surface().placement());
            this.reversed = view.surface().reversed();
            this.reflection = Similarity.of(PortalSurface.mirrorTransform(geometry), 1.0D);
            hash = Objects.hash(System.identityHashCode(level), geometry, placement, reversed, reflection);
        }

        private boolean sameTarget(Key other) {
            return level == other.level && reversed == other.reversed && targetGeometry.equals(other.targetGeometry)
                && placement.equals(other.placement) && reflection.equals(other.reflection);
        }

        @Override
        public boolean equals(Object other) {
            return this == other || other instanceof Key key && level == key.level && reversed == key.reversed
                && geometry.equals(key.geometry) && placement.equals(key.placement) && reflection.equals(key.reflection);
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public String toString() {
            return "NativeMirrorHistory[level=" + level.dimension().identifier() + "@" + System.identityHashCode(level)
                + ", geometry=" + geometry + ", placement=" + placement + ", reversed=" + reversed + ", reflection=" + reflection + "]";
        }
    }

    record Proof(Key key, long targetIdentity, int parentStreamKey) {
    }

    private record Branch(Key key, int parentStreamKey) {
    }
}
