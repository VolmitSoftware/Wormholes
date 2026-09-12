package art.arcane.wormholes.render.lod;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.objects.ObjectIterator;

import art.arcane.wormholes.render.ProjectedBlockClaim;

/**
 * Per (portal, observer) admission ramp. A new view is admitted near-to-far over {@code dissolveTicks}
 * passes; a retiring view is released far-to-near over the same span. Interest returning during a
 * retire cancels it so the view never thrashes across the interest grace window.
 */
public final class DissolveSchedule {
    @FunctionalInterface
    public interface DepthFunction {
        double depth(long localKey);
    }

    private enum Phase {
        IDLE,
        ADMIT,
        RETIRE
    }

    private Phase phase = Phase.IDLE;
    private long startTick;
    private int ticks;

    public void beginAdmit(long tick, int dissolveTicks) {
        if (dissolveTicks <= 0) {
            phase = Phase.IDLE;
            return;
        }
        phase = Phase.ADMIT;
        startTick = tick;
        ticks = dissolveTicks;
    }

    public void beginRetire(long tick, int dissolveTicks) {
        phase = Phase.RETIRE;
        startTick = tick;
        ticks = Math.max(0, dissolveTicks);
    }

    public void cancelRetire() {
        if (phase == Phase.RETIRE) {
            phase = Phase.IDLE;
        }
    }

    public boolean isActive() {
        return phase != Phase.IDLE;
    }

    public boolean isRetiring() {
        return phase == Phase.RETIRE;
    }

    public boolean retireComplete(long tick) {
        return phase == Phase.RETIRE && tick - startTick >= ticks;
    }

    /** Fraction of the view depth admitted on this pass; 1.0 when idle, 0.0 once a retire completes. */
    public double admittedFraction(long tick) {
        switch (phase) {
            case ADMIT -> {
                double fraction = (tick - startTick + 1.0D) / ticks;
                if (fraction >= 1.0D) {
                    phase = Phase.IDLE;
                    return 1.0D;
                }
                return Math.max(0.0D, fraction);
            }
            case RETIRE -> {
                if (ticks <= 0) {
                    return 0.0D;
                }
                double fraction = 1.0D - ((tick - startTick + 1.0D) / ticks);
                return Math.max(0.0D, Math.min(1.0D, fraction));
            }
            default -> {
                return 1.0D;
            }
        }
    }

    /** Removes claims deeper than {@code fraction * maxDepth} from the plane, keeping the nearest first. */
    public static void filter(Long2ObjectMap<ProjectedBlockClaim> claims, double fraction, double maxDepth, DepthFunction depth) {
        if (fraction >= 1.0D || claims.isEmpty()) {
            return;
        }
        double limit = fraction * maxDepth;
        LongArrayList removed = new LongArrayList();
        ObjectIterator<Long2ObjectMap.Entry<ProjectedBlockClaim>> iterator = claims.long2ObjectEntrySet().iterator();
        while (iterator.hasNext()) {
            long key = iterator.next().getLongKey();
            if (fraction <= 0.0D || depth.depth(key) > limit) {
                removed.add(key);
            }
        }
        for (int index = 0; index < removed.size(); index++) {
            claims.remove(removed.getLong(index));
        }
    }
}
