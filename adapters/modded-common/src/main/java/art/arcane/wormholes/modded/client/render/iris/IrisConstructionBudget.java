package art.arcane.wormholes.modded.client.render.iris;

import java.util.Objects;
import java.util.function.Predicate;

final class IrisConstructionBudget {
    private final Predicate<String> readiness;
    private int frame;
    private boolean spent;

    IrisConstructionBudget(Predicate<String> readiness) {
        this.readiness = Objects.requireNonNull(readiness);
    }

    boolean available(int frame, String dimension) {
        return (!spent || this.frame != frame) && readiness.test(dimension);
    }

    void spend(int frame) {
        this.frame = frame;
        spent = true;
    }

    void clear() {
        spent = false;
    }
}
