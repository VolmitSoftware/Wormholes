package art.arcane.wormholes.network;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

public final class MinecraftLegacyTeleports {
    private MinecraftLegacyTeleports() {
    }

    public static Entity teleport(Entity entity, ServerLevel level, Vec3 position, Vec3 velocity, float yaw, float pitch, boolean portalTicket) {
        if (portalTicket) {
            BlockPos block = BlockPos.containing(position);
            level.getChunkSource().addRegionTicket(TicketType.PORTAL, new ChunkPos(block), 3, block);
        }
        if (entity instanceof ServerPlayer player) {
            player.teleportTo(level, position.x, position.y, position.z, yaw, pitch);
            player.setDeltaMovement(velocity);
            player.hurtMarked = true;
            return player;
        }
        float clampedPitch = Mth.clamp(pitch, -90.0F, 90.0F);
        if (entity.level() == level) {
            entity.moveTo(position.x, position.y, position.z, yaw, clampedPitch);
            entity.setYHeadRot(yaw);
            entity.setDeltaMovement(velocity);
            entity.hurtMarked = true;
            return entity;
        }
        entity.unRide();
        Entity moved = entity.getType().create(level);
        if (moved == null) {
            return null;
        }
        moved.restoreFrom(entity);
        moved.moveTo(position.x, position.y, position.z, yaw, clampedPitch);
        moved.setYHeadRot(yaw);
        moved.setDeltaMovement(velocity);
        entity.setRemoved(Entity.RemovalReason.CHANGED_DIMENSION);
        level.addDuringTeleport(moved);
        return moved;
    }
}
