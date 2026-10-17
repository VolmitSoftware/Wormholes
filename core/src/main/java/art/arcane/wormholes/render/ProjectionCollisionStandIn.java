package art.arcane.wormholes.render;

import java.util.Objects;
import java.util.function.Predicate;

public record ProjectionCollisionStandIn<B>(Predicate<B> air, Predicate<B> collidable, B barrier) {
    public ProjectionCollisionStandIn {
        Objects.requireNonNull(air, "air");
        Objects.requireNonNull(collidable, "collidable");
        Objects.requireNonNull(barrier, "barrier");
    }

    public boolean needsLocal(B projected) {
        return projected != null && air.test(projected);
    }

    public B outgoing(B projected, B local) {
        if (local == null || !needsLocal(projected) || !collidable.test(local)) {
            return projected;
        }
        return barrier;
    }
}
