package art.arcane.wormholes.modded.gravity;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.optics.crossing.Pose;
import art.arcane.optics.crossing.PoseTransform;
import art.arcane.optics.crossing.LookTransfer;
import art.arcane.optics.frame.AxisPermutation;
import art.arcane.optics.math.Box;
import art.arcane.optics.math.Face;
import art.arcane.optics.math.Vec3d;
import art.arcane.optics.math.Angles;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.server.level.ServerLevel;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.MinecraftScaleAccess;
import art.arcane.wormholes.modded.MinecraftArrivalPose;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.Objects;
import java.util.Optional;

public final class MinecraftGravity {
    private static final GravityFrame[] FRAMES = frames();

    private MinecraftGravity() {
    }

    public static GravityFrame frame(Entity entity) {
        return entity instanceof GravityHolder holder ? holder.wormholesGravity() : GravityFrame.WORLD;
    }

    public static GravityFrame fromIndex(int index) {
        AxisPermutation orientation = AxisPermutation.ofIndex(index);
        GravityFrame frame = FRAMES[orientation.index()];
        if (frame == null) {
            throw new IllegalArgumentException("Gravity orientation must be a rotation");
        }
        return frame;
    }

    public static void set(Entity entity, GravityFrame frame) {
        updateState(entity, frame, false);
    }

    public static void reset(Entity entity) {
        GravityFrame frame = frame(entity);
        if (frame.equals(GravityFrame.WORLD)) {
            return;
        }
        float yaw = entity.getYRot();
        float previousYaw = entity.yRotO;
        float body = yaw;
        float previousBody = previousYaw;
        float head = yaw;
        float previousHead = previousYaw;
        if (entity instanceof LivingEntity living) {
            body = living.yBodyRot;
            previousBody = living.yBodyRotO;
            head = living.yHeadRot;
            previousHead = living.yHeadRotO;
        }
        Pose pose = new Pose(Vec3d.ZERO, Vec3d.ZERO, Vec3d.ZERO, Vec3d.ZERO, yaw, entity.getXRot(), previousYaw, entity.xRotO,
            body, previousBody, head, previousHead);
        MinecraftArrivalPose.apply(entity, PoseTransform.orient(pose, frame.orientation()));
        updateState(entity, GravityFrame.WORLD, true);
    }

    public static Vec3 local(GravityFrame frame, Vec3 vector) {
        return transform(frame.orientation().inverse().x(), frame.orientation().inverse().y(), frame.orientation().inverse().z(), vector);
    }

    public static Vec3 world(GravityFrame frame, Vec3 vector) {
        return transform(frame.orientation().x(), frame.orientation().y(), frame.orientation().z(), vector);
    }

    public static Angles.Look worldLook(Entity entity) {
        Angles.Look look = new Angles.Look(entity.getYRot(), entity.getXRot());
        GravityFrame frame = frame(entity);
        return frame.equals(GravityFrame.WORLD) ? look : LookTransfer.of(look, frame.orientation()).look();
    }

    public static Angles.Look localLook(Entity entity, Angles.Look world) {
        GravityFrame frame = frame(entity);
        return frame.equals(GravityFrame.WORLD) ? world : LookTransfer.of(world, frame.orientation().inverse()).look();
    }

    public static AABB bounds(Entity entity, Vec3 feet) {
        EntityDimensions dimensions = entity.getDimensions(entity.getPose());
        return bounds(frame(entity), dimensions, feet);
    }

    public static AABB bounds(GravityFrame frame, EntityDimensions dimensions, Vec3 feet) {
        Box box = frame.bounds(new Vec3d(feet.x, feet.y, feet.z), dimensions.width(), dimensions.height());
        return new AABB(box.getXa(), box.getYa(), box.getZa(), box.getXb(), box.getYb(), box.getZb());
    }

    public static AABB arrivalBounds(Entity entity, Vec3 feet, GravityFrame frame, double scaleFactor) {
        if (!Double.isFinite(scaleFactor) || scaleFactor <= 0.0D) {
            throw new IllegalArgumentException("Arrival scale must be finite and positive");
        }
        EntityDimensions dimensions = entity.getDimensions(entity.getPose());
        if (entity instanceof LivingEntity living) {
            AttributeInstance scale = living.getAttribute(Attributes.SCALE);
            if (scale != null) {
                dimensions = dimensions.scale((float) (arrivalScale(scale, scaleFactor) / scale.getValue()));
            }
        }
        return bounds(frame, dimensions, feet);
    }

    public static Vec3 eye(GravityFrame frame, Vec3 feet, double height) {
        Face up = frame.up();
        return feet.add(up.x() * height, up.y() * height, up.z() * height);
    }

    public static BlockPos support(Entity entity, float offset) {
        Face down = frame(entity).down();
        return BlockPos.containing(entity.position().add(down.x() * offset, down.y() * offset, down.z() * offset));
    }

    public static AABB transformBox(GravityFrame frame, AABB box, Vec3 origin, boolean toLocal) {
        Vec3 minimum = new Vec3(box.minX, box.minY, box.minZ).subtract(origin);
        Vec3 maximum = new Vec3(box.maxX, box.maxY, box.maxZ).subtract(origin);
        minimum = toLocal ? local(frame, minimum) : world(frame, minimum);
        maximum = toLocal ? local(frame, maximum) : world(frame, maximum);
        return new AABB(Math.min(minimum.x, maximum.x), Math.min(minimum.y, maximum.y), Math.min(minimum.z, maximum.z),
            Math.max(minimum.x, maximum.x), Math.max(minimum.y, maximum.y), Math.max(minimum.z, maximum.z));
    }

    private static void updateState(Entity entity, GravityFrame frame, boolean restoreWorldOrientation) {
        Objects.requireNonNull(entity, "entity");
        Objects.requireNonNull(frame, "frame");
        boolean changed = !frame(entity).equals(frame);
        if (!changed) {
            return;
        }
        if (entity instanceof GravityHolder holder) {
            holder.wormholesGravity(frame);
            entity.mainSupportingBlockPos = Optional.empty();
            entity.setOnGround(false);
            entity.resetFallDistance();
            entity.setBoundingBox(bounds(entity, entity.position()));
            if (entity.level() instanceof ServerLevel level) {
                WormholesModRuntime runtime = WormholesModRuntime.forServer(level.getServer());
                if (runtime != null && runtime.clientViews() != null) {
                    runtime.clientViews().entityGravity(entity, restoreWorldOrientation);
                }
            }
        } else if (!frame.equals(GravityFrame.WORLD)) {
            throw new IllegalArgumentException("Entity does not support directional gravity");
        }
    }

    private static Vec3 transform(Face x, Face y, Face z, Vec3 vector) {
        return new Vec3(component(x, y, z, vector, 0), component(x, y, z, vector, 1), component(x, y, z, vector, 2));
    }

    private static double component(Face x, Face y, Face z, Vec3 vector, int axis) {
        if (x.axisIndex() == axis) {
            return x.sign() * vector.x;
        }
        if (y.axisIndex() == axis) {
            return y.sign() * vector.y;
        }
        return z.sign() * vector.z;
    }

    private static double arrivalScale(AttributeInstance attribute, double portalFactor) {
        double additive = 0.0D;
        double baseMultiplier = 0.0D;
        double totalMultiplier = portalFactor;
        for (AttributeModifier modifier : attribute.getModifiers()) {
            if (modifier.id().equals(MinecraftScaleAccess.ID)) {
                continue;
            }
            switch (modifier.operation()) {
                case ADD_VALUE -> additive += modifier.amount();
                case ADD_MULTIPLIED_BASE -> baseMultiplier += modifier.amount();
                case ADD_MULTIPLIED_TOTAL -> totalMultiplier *= 1.0D + modifier.amount();
            }
        }
        double base = attribute.getBaseValue() + additive;
        return attribute.getAttribute().value().sanitizeValue((base + base * baseMultiplier) * totalMultiplier);
    }

    private static GravityFrame[] frames() {
        GravityFrame[] frames = new GravityFrame[48];
        for (int index = 0; index < frames.length; index++) {
            AxisPermutation orientation = AxisPermutation.ofIndex(index);
            if (!orientation.reflects()) {
                frames[index] = orientation == AxisPermutation.IDENTITY ? GravityFrame.WORLD : new GravityFrame(orientation);
            }
        }
        return frames;
    }
}
