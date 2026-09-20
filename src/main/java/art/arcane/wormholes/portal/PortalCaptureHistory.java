package art.arcane.wormholes.portal;

import art.arcane.wormholes.TraversableManager;
import art.arcane.wormholes.TraversableManager.Movement;
import art.arcane.wormholes.TraversableManager.EntityContinuity;
import art.arcane.wormholes.util.AxisAlignedBB;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

final class PortalCaptureHistory {
    private static final long MAX_SWEEP_AGE_MILLIS = 5_000L;

    private final Map<UUID, Capture> captures = new HashMap<>();
    private final Map<UUID, EntityCapture> entityCaptures = new HashMap<>();
    private final AtomicLong generation = new AtomicLong();
    private long captureGeneration;
    private long pass;

    void beginPass() {
        long currentGeneration = generation.get();
        if (captureGeneration != currentGeneration) {
            captures.clear();
            entityCaptures.clear();
            captureGeneration = currentGeneration;
        }
        pass++;
        entityCaptures.entrySet().removeIf(entry -> entry.getValue().pass() < pass - 1L);
    }

    void clear() {
        captures.clear();
        entityCaptures.clear();
        captureGeneration = generation.incrementAndGet();
    }

    void invalidate() {
        generation.incrementAndGet();
    }

    Location capture(Movement movement, Location location, long nowMillis) {
        if (!movement.worldId().equals(location.getWorld().getUID())
            || (movement.velocityX() == 0.0D && movement.velocityY() == 0.0D && movement.velocityZ() == 0.0D
                && (movement.x() != location.getX() || movement.y() != location.getY() || movement.z() != location.getZ()))) {
            captures.remove(movement.player().getUniqueId());
            return null;
        }
        Capture next = new Capture(movement, location.clone(), pass, nowMillis);
        Capture previous = captures.put(movement.player().getUniqueId(), next);
        return continuous(previous, movement, nowMillis) ? previous.location().clone() : null;
    }

    Location capture(EntityContinuity movement, Location location, long nowMillis) {
        EntityCapture previous = entityCaptures.put(movement.entity().getUniqueId(),
            new EntityCapture(movement, location.clone(), pass));
        return previous != null && previous.movement().entity() == movement.entity()
            && previous.movement().continuity() == movement.continuity()
            && previous.movement().worldId().equals(movement.worldId())
            && nowMillis - previous.movement().capturedAtMillis() <= MAX_SWEEP_AGE_MILLIS
            ? previous.location().clone() : location.clone();
    }

    List<Pending> departed(TraversableManager manager, PortalStructure structure, long nowMillis) {
        List<Pending> pending = new ArrayList<>();
        World world = structure.getWorld();
        Iterator<Map.Entry<UUID, Capture>> iterator = captures.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, Capture> entry = iterator.next();
            Capture capture = entry.getValue();
            if (capture.pass() == pass) {
                continue;
            }
            iterator.remove();
            Movement movement = manager.movement(entry.getKey());
            if (!continuous(capture, movement, nowMillis)) {
                continue;
            }
            Location end = movement.location(world);
            if (clip(capture.location(), end, structure.getArea()) != null) {
                pending.add(new Pending(entry.getKey(), movement, capture.location(), capture.capturedAtMillis(), generation, captureGeneration));
            }
        }
        return pending;
    }

    static Segment clip(Location start, Location end, AxisAlignedBB area) {
        double first = 0.0D;
        double last = 1.0D;
        for (int axis = 0; axis < 3; axis++) {
            double from = coordinate(start, axis);
            double delta = coordinate(end, axis) - from;
            double low = switch (axis) { case 0 -> area.getXa(); case 1 -> area.getYa(); default -> area.getZa(); };
            double high = switch (axis) { case 0 -> area.getXb(); case 1 -> area.getYb(); default -> area.getZb(); };
            if (delta == 0.0D) {
                if (from < low || from > high) {
                    return null;
                }
                continue;
            }
            double enter = (low - from) / delta;
            double exit = (high - from) / delta;
            first = Math.max(first, Math.min(enter, exit));
            last = Math.min(last, Math.max(enter, exit));
            if (first > last) {
                return null;
            }
        }
        return new Segment(interpolate(start, end, first), interpolate(start, end, last));
    }

    private static boolean continuous(Capture previous, Movement current, long nowMillis) {
        return previous != null && current != null && previous.movement().player() == current.player()
            && previous.movement().continuity() == current.continuity()
            && previous.movement().worldId().equals(current.worldId())
            && nowMillis - previous.capturedAtMillis() <= MAX_SWEEP_AGE_MILLIS;
    }

    private static double coordinate(Location location, int axis) {
        return switch (axis) { case 0 -> location.getX(); case 1 -> location.getY(); default -> location.getZ(); };
    }

    private static Location interpolate(Location start, Location end, double fraction) {
        return new Location(start.getWorld(), start.getX() + (end.getX() - start.getX()) * fraction,
            start.getY() + (end.getY() - start.getY()) * fraction,
            start.getZ() + (end.getZ() - start.getZ()) * fraction, start.getYaw(), start.getPitch());
    }

    record Pending(UUID playerId, Movement movement, Location start, long capturedAtMillis,
        AtomicLong generation, long captureGeneration) {
        Player player() {
            return movement.player();
        }

        boolean stillContinuous(Movement current, long nowMillis) {
            return generation.get() == captureGeneration && current != null && movement.player() == current.player()
                && movement.continuity() == current.continuity()
                && movement.worldId().equals(current.worldId())
                && nowMillis - capturedAtMillis <= MAX_SWEEP_AGE_MILLIS;
        }
    }

    record Segment(Location start, Location end) {
    }

    private record Capture(Movement movement, Location location, long pass, long capturedAtMillis) {
    }

    private record EntityCapture(EntityContinuity movement, Location location, long pass) {
    }
}
