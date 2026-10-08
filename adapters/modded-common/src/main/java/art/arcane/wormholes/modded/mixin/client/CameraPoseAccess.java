package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Camera.class)
public interface CameraPoseAccess {
    @Accessor("up")
    Vector3f wormholes$up();

    @Accessor("left")
    Vector3f wormholes$left();

    @Invoker("setPosition")
    void wormholes$position(Vec3 position);

    @Invoker("setRotation")
    void wormholes$rotation(float yaw, float pitch);

    @Invoker("createProjectionMatrixForCulling")
    Matrix4f wormholes$cullingProjection();

    @Invoker("prepareCullFrustum")
    void wormholes$cullFrustum(Matrix4fc view, Matrix4f projection, Vec3 position);
}
