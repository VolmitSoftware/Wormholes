package art.arcane.wormholes.modded.client;

import art.arcane.optics.crossing.GravityFrame;
import art.arcane.wormholes.modded.gravity.MinecraftGravity;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

public final class GravityAutoJump {
    private GravityAutoJump() {
    }

    public static boolean shouldJump(LocalPlayer player, float x, float z) {
        GravityFrame frame = MinecraftGravity.frame(player);
        Vec3 movement = new Vec3(x, 0.0D, z);
        float speed = player.getSpeed();
        double distanceSquared = movement.lengthSqr();
        if (distanceSquared <= 0.001D) {
            Vec2 input = player.input.getMoveVector();
            float sine = Mth.sin(player.getYRot() * Mth.DEG_TO_RAD);
            float cosine = Mth.cos(player.getYRot() * Mth.DEG_TO_RAD);
            movement = new Vec3(speed * (input.x * cosine - input.y * sine), 0.0D, speed * (input.y * cosine + input.x * sine));
            distanceSquared = movement.lengthSqr();
            if (distanceSquared <= 0.001D) {
                return false;
            }
        }
        Vec3 direction = movement.normalize();
        float yaw = player.getYRot() * Mth.DEG_TO_RAD;
        if (direction.z * Mth.cos(yaw) - direction.x * Mth.sin(yaw) < -0.15D) {
            return false;
        }
        double jumpHeight = 1.2D;
        if (player.hasEffect(MobEffects.JUMP_BOOST)) {
            jumpHeight += (player.getEffect(MobEffects.JUMP_BOOST).getAmplifier() + 1) * 0.75D;
        }
        double lookAhead = Math.max(speed * 7.0D, Math.sqrt(distanceSquared));
        Vec3 endpoint = movement.add(direction.scale(lookAhead));
        double width = player.getBbWidth();
        double height = player.getBbHeight();
        AABB localSearch = new AABB(Vec3.ZERO, endpoint.add(0.0D, height + jumpHeight, 0.0D)).inflate(width, 0.0D, width);
        AABB worldSearch = MinecraftGravity.transformBox(frame, localSearch, Vec3.ZERO, false).move(player.position());
        List<VoxelShape> colliders = Entity.collectAllColliders(player, player.level(), worldSearch);
        Vec3 right = direction.cross(new Vec3(0.0D, 1.0D, 0.0D)).scale(width * 0.5D);
        Vec3 begin = new Vec3(0.0D, 0.51D, 0.0D);
        Vec3 end = endpoint.add(0.0D, 0.51D, 0.0D);
        double obstacleHeight = Double.POSITIVE_INFINITY;
        for (VoxelShape shape : colliders) {
            for (AABB worldBox : shape.toAabbs()) {
                AABB box = MinecraftGravity.transformBox(frame, worldBox, player.position(), true);
                if ((box.intersects(begin.subtract(right), end.subtract(right)) || box.intersects(begin.add(right), end.add(right)))
                    && box.maxY > 0.5D) {
                    obstacleHeight = Math.min(obstacleHeight, box.maxY);
                }
            }
        }
        if (obstacleHeight <= 0.5D || obstacleHeight > jumpHeight) {
            return false;
        }
        Vec3 rise = MinecraftGravity.world(frame, new Vec3(0.0D, obstacleHeight, 0.0D));
        if (!player.level().noCollision(player, player.getBoundingBox().expandTowards(rise))) {
            return false;
        }
        Vec3 landing = MinecraftGravity.world(frame, endpoint.add(0.0D, obstacleHeight, 0.0D));
        return player.level().noCollision(player, player.getBoundingBox().move(landing));
    }
}
