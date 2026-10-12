package art.arcane.wormholes.modded.client;

import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.crossing.LookTransfer;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import art.arcane.optics.math.Angles;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MoveSimulationType;
import net.minecraft.world.entity.PositionPath;
import net.minecraft.world.entity.PositionStep;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

public final class ClientEntityCrossing {
    private static final int MAX_PENDING_TICKS = 10;

    private final OpticTransform back;
    private final Vec3d origin;
    private final Face normal;
    private final TravelMessage.EntityCrossed crossing;
    private final AxisPermutation localRotation;
    private boolean crossed;
    private boolean echo;
    private int ticks;

    ClientEntityCrossing(TravelMessage.EntityCrossed crossed, boolean echo) {
        this.back = crossed.toward().inverse();
        this.origin = crossed.planeOrigin();
        this.normal = crossed.planeNormal();
        this.crossing = crossed;
        this.localRotation = localRotation(crossed);
        this.echo = echo;
    }

    static boolean echoed(Entity entity) {
        return !entity.getType().hasUpdateInterval() || entity.getMoveSimulationType() == MoveSimulationType.SERVER_AND_CLIENT;
    }

    public boolean absorb() {
        if (!echo) {
            return false;
        }
        echo = false;
        return true;
    }

    public boolean crossed() {
        return crossed;
    }

    void prepare(Entity entity) {
        MinecraftGravity.set(entity, crossing.sourceGravity());
    }

    public void route(Entity entity, PositionPath position, float yRot, float xRot, boolean hasRotation) {
        MinecraftGravity.set(entity, crossing.sourceGravity());
        PositionPath mapped = position == null ? null : back(position);
        if (!hasRotation || localRotation == AxisPermutation.IDENTITY) {
            entity.moveOrInterpolateTo(mapped, yRot, xRot, hasRotation);
            return;
        }
        LookTransfer look = LookTransfer.of(new Angles.Look(yRot, xRot), localRotation.inverse());
        entity.moveOrInterpolateTo(mapped, look.yaw(), look.pitch(), true);
    }

    public void resolve(Entity entity) {
        if (crossed) {
            return;
        }
        crossed = true;
        ClientEntityCrossings.cross(entity, crossing);
    }

    boolean settled() {
        return crossed && !echo;
    }

    boolean reached(Entity entity) {
        Vec3 position = entity.position();
        return normal.x() * (position.x - origin.x()) + normal.y() * (position.y - origin.y()) + normal.z() * (position.z - origin.z()) <= 0.0D;
    }

    boolean expired() {
        return ++ticks > MAX_PENDING_TICKS;
    }

    static AxisPermutation localRotation(TravelMessage.EntityCrossed crossed) {
        return crossed.destinationGravity().orientation().inverse().compose(crossed.toward().permutation())
            .compose(crossed.sourceGravity().orientation());
    }

    private PositionPath back(PositionPath path) {
        return switch (path) {
            case PositionPath.Linear linear -> PositionPath.of(back(linear.endPosition()));
            case PositionPath.Stepped stepped -> {
                List<PositionStep> steps = new ArrayList<>(stepped.steps().size());
                for (PositionStep step : stepped.steps()) {
                    steps.add(new PositionStep(back(step.position()), step.tickOffset()));
                }
                yield PositionPath.stepped(steps);
            }
        };
    }

    private Vec3 back(Vec3 point) {
        Vec3d mapped = back.point(new Vec3d(point.x, point.y, point.z));
        return new Vec3(mapped.x(), mapped.y(), mapped.z());
    }
}
