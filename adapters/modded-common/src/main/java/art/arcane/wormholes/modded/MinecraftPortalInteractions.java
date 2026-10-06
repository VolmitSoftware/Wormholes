package art.arcane.wormholes.modded;

import art.arcane.optics.math.Vec3d;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Optional;

final class MinecraftPortalInteractions {
    private MinecraftPortalInteractions() { }

    static boolean wand(WormholesModRuntime runtime, ServerPlayer player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND || !MinecraftPortalTools.isWand(player.getMainHandItem())) {
            return false;
        }
        MinecraftPortal portal = lookingAt(runtime, player);
        if (portal == null) {
            return false;
        }
        runtime.menus().open(player, portal.getId());
        return true;
    }

    static boolean frame(WormholesModRuntime runtime, ServerPlayer player, InteractionHand hand, BlockPos position) {
        if (!player.isShiftKeyDown() || !player.getMainHandItem().isEmpty()
            || !player.isWithinBlockInteractionRange(position, 0.0)) {
            return false;
        }
        for (MinecraftPortal portal : runtime.portals().snapshot()) {
            if (runtime.portals().resolveLevel(portal) == player.level()
                && portal.getGeometry().containsOrAdjoinsBlock(position.getX(), position.getY(), position.getZ())
                && runtime.portals().canManage(player, portal)) {
                if (hand == InteractionHand.MAIN_HAND) {
                    if (!portal.getSurfaceSkin().isEmpty() && runtime.access().permission(player, "wormholes.admin")) {
                        runtime.menus().cosmetics().applySurfaceSkinFromInteraction(player, portal, "");
                    } else {
                        runtime.menus().open(player, portal.getId());
                    }
                }
                return true;
            }
        }
        return false;
    }

    private static MinecraftPortal lookingAt(WormholesModRuntime runtime, ServerPlayer player) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(16));
        BlockHitResult obstacle = player.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        double closest = obstacle.getType() == HitResult.Type.MISS ? 256.0 : eye.distanceToSqr(obstacle.getLocation()) + 0.0001;
        MinecraftPortal selected = null;
        for (MinecraftPortal portal : runtime.portals().snapshot()) {
            Vec3d center = portal.getGeometry().getApertureCenter();
            if (runtime.portals().resolveLevel(portal) != player.level()
                || player.position().distanceToSqr(center.x(), center.y(), center.z()) >= 64) {
                continue;
            }
            for (Vec3d cell : portal.getGeometry().getBlockPositions()) {
                AABB bounds = new AABB(cell.getBlockX(), cell.getBlockY(), cell.getBlockZ(),
                    cell.getBlockX() + 1, cell.getBlockY() + 1, cell.getBlockZ() + 1);
                Optional<Vec3> hit = bounds.clip(eye, end);
                double distance = bounds.contains(eye) ? 0 : hit.map(eye::distanceToSqr).orElse(Double.POSITIVE_INFINITY);
                if (distance < closest) {
                    closest = distance;
                    selected = portal;
                }
            }
        }
        return selected;
    }
}
