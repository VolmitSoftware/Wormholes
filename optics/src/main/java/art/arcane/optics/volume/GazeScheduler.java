package art.arcane.optics.volume;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;


public final class GazeScheduler {
    public static final double REUSE_EYE_EPSILON_SQUARED = 0.0625D;
    private static final double IN_VIEW_WEIGHT = 1.0D;
    private static final double PERIPHERAL_WEIGHT = 0.35D;
    private static final double BEHIND_WEIGHT = 0.1D;
    private static final double PERIPHERAL_BAND_DEGREES = 35.0D;
    private static final double MAX_HALF_ANGLE_DEGREES = 89.0D;
    private static final double SCREEN_ASPECT = 9.0D / 16.0D;
    private static final double PENDING_SCAN_MULTIPLIER = 2.0D;
    private static final double STALENESS_TICKS = 4.0D;
    private static final double REFRESH_URGENCY = IN_VIEW_WEIGHT * (1.0D + 1.0D / STALENESS_TICKS);
    private static final double STARVED_PRIORITY = 1.0E6D;
    private static final double RETIRING_PRIORITY = 1.0E9D;
    private static final double YAW_VELOCITY_SMOOTHING = 0.5D;
    private static final double MIN_ANGULAR_SIZE = 1.0E-4D;
    private static final double MAX_ANGULAR_SIZE = 2.0D * Math.PI;
    private static final Comparator<Scored<?>> PRIORITY_ORDER = Comparator
        .comparingDouble((Scored<?> scored) -> -scored.score())
        .thenComparingInt(Scored::order);

    private final Map<UUID, ObserverGaze> observers = new ConcurrentHashMap<UUID, ObserverGaze>();

    public <T> List<T> select(UUID observerId, Eye eye, List<Candidate<T>> candidates, int limit, long frameTick, Options options) {
        ObserverGaze gaze = observers.computeIfAbsent(observerId, ignored -> new ObserverGaze());
        gaze.observe(eye.yaw(), frameTick);
        if (candidates.isEmpty() || limit <= 0) {
            return List.of();
        }
        double halfFov = Math.min(MAX_HALF_ANGLE_DEGREES, options.fovDegrees() * 0.5D);
        double verticalHalfFov = Math.toDegrees(Math.atan(Math.tan(Math.toRadians(halfFov)) * SCREEN_ASPECT));
        ViewCone view = new ViewCone(eye.yaw(), eye.pitch(), halfFov, verticalHalfFov);
        ViewCone ahead = new ViewCone(eye.yaw() + gaze.yawVelocity * options.lookaheadTicks(), eye.pitch(),
            halfFov, verticalHalfFov);
        int maxStarveTicks = Math.max(1, options.maxStarveTicks());
        List<Scored<T>> eligible = new ArrayList<Scored<T>>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            Candidate<T> candidate = candidates.get(index);
            Slot slot = gaze.lastScheduled.get(candidate.id());
            long age = slot == null ? maxStarveTicks : Math.max(0L, frameTick - slot.tick());
            boolean starved = age >= maxStarveTicks;
            boolean settled = slot != null && !candidate.pendingScan() && slot.sameEye(eye);
            double weight = Math.max(view.weight(eye, candidate), ahead.weight(eye, candidate))
                * (settled ? PERIPHERAL_WEIGHT : 1.0D);
            double urgency = weight * (1.0D + Math.min(age, maxStarveTicks) / STALENESS_TICKS)
                * (candidate.pendingScan() ? PENDING_SCAN_MULTIPLIER : 1.0D);
            if (!candidate.retiring() && !starved && !candidate.pendingScan() && urgency < REFRESH_URGENCY) {
                continue;
            }
            double score = angularSize(eye, candidate) * urgency;
            if (starved) {
                score += STARVED_PRIORITY;
            }
            if (candidate.retiring()) {
                score += RETIRING_PRIORITY;
            }
            eligible.add(new Scored<T>(candidate, score, index));
        }
        eligible.sort(PRIORITY_ORDER);
        int selectedCount = Math.min(limit, eligible.size());
        List<T> selected = new ArrayList<T>(selectedCount);
        for (int index = 0; index < selectedCount; index++) {
            Candidate<T> candidate = eligible.get(index).candidate();
            gaze.lastScheduled.put(candidate.id(), new Slot(frameTick, eye.x(), eye.y(), eye.z()));
            selected.add(candidate.value());
        }
        return selected;
    }

    public void retain(UUID observerId, Set<UUID> portalIds) {
        if (portalIds.isEmpty()) {
            observers.remove(observerId);
            return;
        }
        ObserverGaze gaze = observers.get(observerId);
        if (gaze != null) {
            gaze.lastScheduled.keySet().retainAll(portalIds);
        }
    }

    public void forget(UUID observerId) {
        observers.remove(observerId);
    }

    public void clear() {
        observers.clear();
    }

    private static double angularSize(Eye eye, Candidate<?> candidate) {
        double sizeX = candidate.maxX() - candidate.minX();
        double sizeY = candidate.maxY() - candidate.minY();
        double sizeZ = candidate.maxZ() - candidate.minZ();
        double dx = (candidate.minX() + candidate.maxX()) * 0.5D - eye.x();
        double dy = (candidate.minY() + candidate.maxY()) * 0.5D - eye.y();
        double dz = (candidate.minZ() + candidate.maxZ()) * 0.5D - eye.z();
        double distanceSquared = dx * dx + dy * dy + dz * dz;
        if (!(distanceSquared > 1.0E-9D)) {
            return MAX_ANGULAR_SIZE;
        }
        double distance = Math.sqrt(distanceSquared);
        double projectedArea = Math.abs(dx) / distance * sizeY * sizeZ
            + Math.abs(dy) / distance * sizeX * sizeZ
            + Math.abs(dz) / distance * sizeX * sizeY;
        return Math.max(MIN_ANGULAR_SIZE, Math.min(MAX_ANGULAR_SIZE, projectedArea / Math.max(1.0D, distanceSquared)));
    }

    private static double wrapDegrees(double degrees) {
        double wrapped = degrees % 360.0D;
        if (wrapped >= 180.0D) {
            wrapped -= 360.0D;
        }
        if (wrapped < -180.0D) {
            wrapped += 360.0D;
        }
        return wrapped;
    }

    public record Eye(double x, double y, double z, float yaw, float pitch) {
    }

    public record Candidate<T>(T value, UUID id, double minX, double minY, double minZ,
                        double maxX, double maxY, double maxZ, boolean pendingScan, boolean retiring) {
    }

    public record Options(double fovDegrees, int lookaheadTicks, int maxStarveTicks) {
        public Options {
            fovDegrees = Double.isFinite(fovDegrees) ? Math.clamp(fovDegrees, 30.0D, 170.0D) : 110.0D;
            lookaheadTicks = Math.clamp(lookaheadTicks, 0, 20);
            maxStarveTicks = Math.clamp(maxStarveTicks, 1, 200);
        }
    }

    private record Scored<T>(Candidate<T> candidate, double score, int order) {
    }

    private record Slot(long tick, double eyeX, double eyeY, double eyeZ) {
        private boolean sameEye(Eye eye) {
            double dx = eye.x() - eyeX;
            double dy = eye.y() - eyeY;
            double dz = eye.z() - eyeZ;
            return dx * dx + dy * dy + dz * dz < REUSE_EYE_EPSILON_SQUARED;
        }
    }

    private static final class ViewCone {
        private final double forwardX;
        private final double forwardY;
        private final double forwardZ;
        private final double rightX;
        private final double rightZ;
        private final double upX;
        private final double upY;
        private final double upZ;
        private final double tanHorizontal;
        private final double tanVertical;
        private final double tanPeripheralHorizontal;
        private final double tanPeripheralVertical;

        private ViewCone(double yawDegrees, double pitchDegrees, double halfFovDegrees, double verticalHalfFovDegrees) {
            double yaw = Math.toRadians(yawDegrees);
            double pitch = Math.toRadians(pitchDegrees);
            double cosPitch = Math.cos(pitch);
            forwardX = -Math.sin(yaw) * cosPitch;
            forwardY = -Math.sin(pitch);
            forwardZ = Math.cos(yaw) * cosPitch;
            rightX = Math.cos(yaw);
            rightZ = Math.sin(yaw);
            upX = forwardY * rightZ;
            upY = forwardZ * rightX - forwardX * rightZ;
            upZ = -forwardY * rightX;
            tanHorizontal = Math.tan(Math.toRadians(halfFovDegrees));
            tanVertical = Math.tan(Math.toRadians(verticalHalfFovDegrees));
            tanPeripheralHorizontal = Math.tan(Math.toRadians(
                Math.min(MAX_HALF_ANGLE_DEGREES, halfFovDegrees + PERIPHERAL_BAND_DEGREES)));
            tanPeripheralVertical = Math.tan(Math.toRadians(
                Math.min(MAX_HALF_ANGLE_DEGREES, verticalHalfFovDegrees + PERIPHERAL_BAND_DEGREES)));
        }

        private double weight(Eye eye, Candidate<?> candidate) {
            if (intersects(eye, candidate, tanHorizontal, tanVertical)) {
                return IN_VIEW_WEIGHT;
            }
            return intersects(eye, candidate, tanPeripheralHorizontal, tanPeripheralVertical)
                ? PERIPHERAL_WEIGHT : BEHIND_WEIGHT;
        }

        private boolean intersects(Eye eye, Candidate<?> candidate, double tanRight, double tanUp) {
            int behind = 0;
            int left = 0;
            int right = 0;
            int below = 0;
            int above = 0;
            for (int corner = 0; corner < 8; corner++) {
                double x = ((corner & 1) == 0 ? candidate.minX() : candidate.maxX()) - eye.x();
                double y = ((corner & 2) == 0 ? candidate.minY() : candidate.maxY()) - eye.y();
                double z = ((corner & 4) == 0 ? candidate.minZ() : candidate.maxZ()) - eye.z();
                double forward = x * forwardX + y * forwardY + z * forwardZ;
                double lateral = x * rightX + z * rightZ;
                double vertical = x * upX + y * upY + z * upZ;
                if (forward <= 0.0D) {
                    behind++;
                }
                if (lateral > tanRight * forward) {
                    right++;
                }
                if (-lateral > tanRight * forward) {
                    left++;
                }
                if (vertical > tanUp * forward) {
                    above++;
                }
                if (-vertical > tanUp * forward) {
                    below++;
                }
            }
            return behind < 8 && left < 8 && right < 8 && below < 8 && above < 8;
        }
    }

    private static final class ObserverGaze {
        private final Map<UUID, Slot> lastScheduled = new HashMap<UUID, Slot>();
        private boolean observed;
        private float lastYaw;
        private long lastTick;
        private double yawVelocity;

        private void observe(float yaw, long frameTick) {
            if (observed && frameTick <= lastTick) {
                return;
            }
            if (observed) {
                double perTick = wrapDegrees(yaw - lastYaw) / (frameTick - lastTick);
                yawVelocity += (perTick - yawVelocity) * YAW_VELOCITY_SMOOTHING;
            }
            observed = true;
            lastYaw = yaw;
            lastTick = frameTick;
        }
    }
}
