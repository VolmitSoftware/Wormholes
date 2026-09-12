package art.arcane.wormholes.door.view;

import art.arcane.wormholes.door.DoorVisualAnimationBudget;
import art.arcane.wormholes.door.DoorwayPlane;
import art.arcane.wormholes.door.RuntimeDoor;
import art.arcane.wormholes.portal.ILocalPortal;
import org.bukkit.World;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Every projectable door aperture, and which of them get a projector this pass.
 *
 * <p>Two hundred doors would starve the frame portals they share a budget with, so admission runs
 * through the same rotation the door animations use: a slice of the doors has its attendance checked
 * each pass, and only attended doors up to {@code [doors] projection-max-active} are handed out.</p>
 */
public final class DoorProjectionRegistry {
    private final ConcurrentHashMap<UUID, DoorProjectionAdapter> adapters;
    private final Attendance attendance;
    private final AtomicBoolean closed;

    private volatile DoorVisualAnimationBudget<UUID> budget;
    private volatile List<DoorVisualAnimationBudget.Admission<UUID>> inFlight;
    private volatile List<UUID> admitted;

    public DoorProjectionRegistry(int maxActive, int attendanceSlots, Attendance attendance) {
        this.attendance = Objects.requireNonNull(attendance, "attendance");
        adapters = new ConcurrentHashMap<>();
        closed = new AtomicBoolean();
        inFlight = List.of();
        admitted = List.of();
        budget = newBudget(maxActive, attendanceSlots);
    }

    /** Registers or re-aims the aperture for one placed door. */
    public DoorProjectionAdapter install(RuntimeDoor door, DoorwayPlane plane, World world) {
        Objects.requireNonNull(door, "door");
        Objects.requireNonNull(plane, "plane");
        Objects.requireNonNull(world, "world");
        if (closed.get()) {
            return null;
        }
        UUID doorItemId = door.endpoint().identity().itemId();
        DoorProjectionAdapter existing = adapters.get(doorItemId);
        if (existing != null && existing.getWorld().equals(world)) {
            existing.refresh(plane);
            return existing;
        }
        DoorProjectionAdapter adapter = new DoorProjectionAdapter(door, plane, world);
        DoorProjectionAdapter replaced = adapters.put(doorItemId, adapter);
        if (replaced != null) {
            replaced.destroy();
        } else {
            budget.register(doorItemId);
        }
        return adapter;
    }

    public void remove(UUID doorItemId) {
        Objects.requireNonNull(doorItemId, "doorItemId");
        DoorProjectionAdapter removed = adapters.remove(doorItemId);
        if (removed != null) {
            removed.destroy();
        }
        budget.retire(doorItemId);
    }

    public DoorProjectionAdapter adapter(UUID doorItemId) {
        return adapters.get(Objects.requireNonNull(doorItemId, "doorItemId"));
    }

    public int size() {
        return adapters.size();
    }

    /**
     * Whether the opaque backing pane behind this door should stay hidden.
     *
     * <p>Deliberately independent of the attendance rotation: a pane that blinked back in whenever a
     * door lost its admission slot would flicker.</p>
     */
    public boolean hidesBacking(UUID doorItemId, boolean globalEnabled, boolean hideBacking) {
        if (!globalEnabled || !hideBacking) {
            return false;
        }
        DoorProjectionAdapter adapter = adapters.get(Objects.requireNonNull(doorItemId, "doorItemId"));
        return adapter != null && adapter.projectionState().projects(true);
    }

    /** Runs one admission pass and answers with the apertures that earned a projector. */
    public List<ILocalPortal> advance(boolean globalEnabled) {
        if (closed.get() || !globalEnabled || adapters.isEmpty()) {
            releaseInFlight();
            admitted = List.of();
            return List.of();
        }
        DoorVisualAnimationBudget<UUID> currentBudget = budget;
        for (DoorVisualAnimationBudget.AttendanceCheck<UUID> check : currentBudget.advanceAttendanceChecks()) {
            currentBudget.reportAttendance(check, attended(check.key()));
        }
        releaseInFlight();
        List<DoorVisualAnimationBudget.Admission<UUID>> acquired = currentBudget.acquire();
        inFlight = acquired;
        List<UUID> keys = new ArrayList<>(acquired.size());
        List<ILocalPortal> active = new ArrayList<>(acquired.size());
        for (DoorVisualAnimationBudget.Admission<UUID> admission : acquired) {
            DoorProjectionAdapter adapter = adapters.get(admission.key());
            if (adapter == null || !adapter.projectionState().projects(true) || !adapter.isOpen()) {
                continue;
            }
            keys.add(admission.key());
            active.add(adapter);
        }
        admitted = List.copyOf(keys);
        return List.copyOf(active);
    }

    /** The apertures the last pass admitted, for diagnostics. */
    public List<UUID> admitted() {
        return admitted;
    }

    /** Rebuilds the budget after {@code [doors]} caps change. */
    public void reconfigure(int maxActive, int attendanceSlots) {
        DoorVisualAnimationBudget<UUID> replacement = newBudget(maxActive, attendanceSlots);
        for (UUID doorItemId : adapters.keySet()) {
            replacement.register(doorItemId);
        }
        DoorVisualAnimationBudget<UUID> previous = budget;
        budget = replacement;
        inFlight = List.of();
        admitted = List.of();
        previous.close();
    }

    /**
     * Drops every aperture but stays usable, which is what turning the global flag off does. A door
     * that comes back is installed again on its next reconcile.
     */
    public void clear() {
        for (DoorProjectionAdapter adapter : adapters.values()) {
            adapter.destroy();
            budget.retire(adapter.getId());
        }
        adapters.clear();
        inFlight = List.of();
        admitted = List.of();
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        for (DoorProjectionAdapter adapter : adapters.values()) {
            adapter.destroy();
        }
        adapters.clear();
        inFlight = List.of();
        admitted = List.of();
        budget.close();
    }

    private void releaseInFlight() {
        List<DoorVisualAnimationBudget.Admission<UUID>> previous = inFlight;
        if (previous.isEmpty()) {
            return;
        }
        for (DoorVisualAnimationBudget.Admission<UUID> admission : previous) {
            budget.complete(admission);
        }
        inFlight = List.of();
    }

    private boolean attended(UUID doorItemId) {
        DoorProjectionAdapter adapter = adapters.get(doorItemId);
        if (adapter == null || adapter.isDestroyed() || !adapter.projectionState().projects(true)) {
            return false;
        }
        Vector origin = adapter.getOrigin();
        double range = adapter.getEffectiveActivationRange();
        return attendance.hasPlayerWithin(
            adapter.getWorld().getUID(), origin.getX(), origin.getY(), origin.getZ(), range * range);
    }

    private static DoorVisualAnimationBudget<UUID> newBudget(int maxActive, int attendanceSlots) {
        int active = Math.max(1, maxActive);
        return new DoorVisualAnimationBudget<>(new DoorVisualAnimationBudget.Policy(
            active, active, Math.max(1, attendanceSlots), 1));
    }

    /** Who is close enough to a door for it to be worth projecting. */
    @FunctionalInterface
    public interface Attendance {
        boolean hasPlayerWithin(UUID worldId, double x, double y, double z, double rangeSquared);
    }
}
