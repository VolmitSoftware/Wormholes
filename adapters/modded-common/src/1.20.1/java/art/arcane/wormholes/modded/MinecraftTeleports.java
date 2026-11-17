package art.arcane.wormholes.modded;

import art.arcane.wormholes.network.MinecraftLegacyTeleports;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Moves a whole rig to a position in any level and returns the root that arrived. Crossing levels replaces
 * non-player entities with copies, so passengers are carried one by one and mounted again on the new root.
 */
final class MinecraftTeleports {
    private MinecraftTeleports() {
    }

    static Entity teleport(Entity entity, ServerLevel level, Vec3 position, Vec3 velocity, float yaw, float pitch) {
        return move(entity, level, position, velocity, yaw, pitch, true);
    }

    private static Entity move(Entity entity, ServerLevel level, Vec3 position, Vec3 velocity, float yaw, float pitch, boolean ticket) {
        List<Entity> passengers = List.copyOf(entity.getPassengers());
        for (Entity passenger : passengers) {
            passenger.stopRiding();
        }
        Entity arrived = MinecraftLegacyTeleports.teleport(entity, level, position, velocity, yaw, pitch, ticket);
        for (Entity passenger : passengers) {
            Entity moved = move(passenger, level, position, velocity, passenger.getYRot(), passenger.getXRot(), false);
            if (moved != null && arrived != null) {
                moved.startRiding(arrived, true);
            }
        }
        return arrived;
    }
}
