package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Camera.class)
public interface CameraPoseAccess {
    @Invoker("setPosition")
    void wormholes$position(Vec3 position);

    @Invoker("setRotation")
    void wormholes$rotation(float yaw, float pitch);
}
