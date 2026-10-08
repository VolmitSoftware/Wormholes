package art.arcane.wormholes.transit;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

import art.arcane.optics.aperture.SizeRatio;
import art.arcane.optics.crossing.ScaleRule;
import art.arcane.optics.spi.ScaleAccess;

public final class TravellerScale<E> {
    private final ScaleAccess<E> access;

    public TravellerScale(ScaleAccess<E> access) {
        this.access = Objects.requireNonNull(access, "access");
    }

    public double cross(E entity, ScaleRule rule, SizeRatio ratio) {
        double current = access.scale(entity);
        if (!rule.changesEntity()) {
            return current;
        }
        double next = rule.entityFactor(current, ratio);
        if (next == current) {
            return current;
        }
        return access.scale(entity, next) ? next : current;
    }

    public double factor(E entity) {
        return access.scale(entity);
    }

    public boolean set(E entity, double factor) {
        return access.scale(entity, factor);
    }

    public boolean reset(E entity) {
        return access.reset(entity);
    }

    public int resetAll(Collection<? extends E> entities, List<E> out) {
        int restored = 0;
        for (E entity : entities) {
            if (access.reset(entity)) {
                out.add(entity);
                restored++;
            }
        }
        return restored;
    }
}
