package art.arcane.wormholes.modded.client.render.iris;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

final class IrisDimensionLoans {
    private final Map<String, IrisViewPipelinePath> holders = new LinkedHashMap<>();
    private final Map<String, IrisViewPipelinePath> drawn = new HashMap<>();
    private int frame;
    private boolean framed;
    private long loans;

    void plan(Collection<IrisViewPipelinePath> actual, Predicate<String> lenders, Predicate<IrisViewPipelinePath> owned) {
        holders.entrySet().removeIf(holder -> !lenders.test(holder.getKey()) || owned.test(holder.getValue()));
        Set<String> claimed = new HashSet<>();
        for (Map.Entry<String, IrisViewPipelinePath> holder : holders.entrySet()) {
            if (actual.contains(holder.getValue())) {
                claimed.add(holder.getKey());
            }
        }
        for (IrisViewPipelinePath path : actual) {
            String dimension = path.dimension();
            if (claimed.contains(dimension) || owned.test(path) || !lenders.test(dimension)) {
                continue;
            }
            holders.put(dimension, path);
            claimed.add(dimension);
        }
    }

    boolean holds(IrisViewPipelinePath path) {
        return path.equals(holders.get(path.dimension()));
    }

    boolean claim(IrisViewPipelinePath path, int frame, Predicate<IrisViewPipelinePath> expected) {
        if (!framed || frame != this.frame) {
            drawn.clear();
            this.frame = frame;
            framed = true;
        }
        String dimension = path.dimension();
        IrisViewPipelinePath user = drawn.get(dimension);
        if (user != null) {
            return user.equals(path);
        }
        IrisViewPipelinePath holder = holders.get(dimension);
        if (holder != null && !holder.equals(path) && expected.test(holder)) {
            return false;
        }
        holders.put(dimension, path);
        drawn.put(dimension, path);
        loans++;
        return true;
    }

    void forget(Predicate<IrisViewPipelinePath> stale) {
        holders.values().removeIf(stale);
        drawn.values().removeIf(stale);
    }

    void clear() {
        holders.clear();
        drawn.clear();
        framed = false;
    }

    List<IrisViewPipelinePath> holders() {
        return List.copyOf(holders.values());
    }

    long loans() {
        return loans;
    }
}
