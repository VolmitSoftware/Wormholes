package art.arcane.optics.client;

import java.util.List;

public final class ClientOverlapResolver {
    public static final int NO_PORTAL = -1;
    private static final double PRIORITY_EPSILON = 1.0E-7D;

    private final double incumbentMargin;

    public ClientOverlapResolver(double incumbentMargin) {
        if (!Double.isFinite(incumbentMargin) || incumbentMargin < 0.0D) {
            throw new IllegalArgumentException("incumbent margin must be a finite non-negative distance");
        }
        this.incumbentMargin = incumbentMargin;
    }

    public static boolean isHigherPriority(Contender candidate, Contender current) {
        int maskTier = compareMaskTier(candidate, current);
        if (maskTier != 0) {
            return maskTier > 0;
        }
        double candidateDistance = candidate.distance();
        double currentDistance = current.distance();
        if (candidateDistance < currentDistance - PRIORITY_EPSILON) {
            return true;
        }
        if (candidateDistance > currentDistance + PRIORITY_EPSILON) {
            return false;
        }
        return candidate.portalKey() < current.portalKey();
    }

    public boolean displaces(Contender challenger, Contender incumbent) {
        if (challenger.portalKey() == incumbent.portalKey()) {
            return false;
        }
        int maskTier = compareMaskTier(challenger, incumbent);
        if (maskTier != 0) {
            return maskTier > 0;
        }
        if (incumbentMargin <= 0.0D) {
            return isHigherPriority(challenger, incumbent);
        }
        return challenger.distance() < incumbent.distance() - incumbentMargin;
    }

    public int resolve(List<Contender> contenders, int incumbentKey) {
        Contender best = null;
        Contender incumbent = null;
        int size = contenders.size();
        for (int index = 0; index < size; index++) {
            Contender contender = contenders.get(index);
            if (contender.portalKey() == incumbentKey) {
                incumbent = contender;
            }
            if (best == null || isHigherPriority(contender, best)) {
                best = contender;
            }
        }
        if (best == null) {
            return NO_PORTAL;
        }
        if (incumbent != null && incumbent != best && !displaces(best, incumbent)) {
            return incumbent.portalKey();
        }
        return best.portalKey();
    }

    private static int compareMaskTier(Contender candidate, Contender current) {
        if (candidate.maskAir() == current.maskAir()) {
            return 0;
        }
        return candidate.maskAir() ? -1 : 1;
    }

    public record Contender(int portalKey, double eyeDot, boolean maskAir) {
        public double distance() {
            return Math.abs(eyeDot);
        }
    }
}
