package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.Camera;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Camera.class)
public interface CameraRotationAccess {
    @Accessor("up")
    Vector3f wormholes$up();

    @Accessor("left")
    Vector3f wormholes$left();
}
