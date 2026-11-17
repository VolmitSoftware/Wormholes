package art.arcane.wormholes.modded.seamless;

import net.minecraft.world.phys.Vec3;

import java.util.Objects;

public record PositionMoveRotation(Vec3 position, Vec3 deltaMovement, float yRot, float xRot) {
    public PositionMoveRotation {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(deltaMovement, "deltaMovement");
    }
}
