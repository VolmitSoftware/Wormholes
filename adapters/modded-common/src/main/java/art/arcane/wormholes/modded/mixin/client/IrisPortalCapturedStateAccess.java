package art.arcane.wormholes.modded.mixin.client;

import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.CapturedRenderingState", remap = false)
public interface IrisPortalCapturedStateAccess {
    @Accessor("gbufferProjection")
    void wormholes$projection(Matrix4fc projection);

    @Accessor("fogColor")
    Vector3d wormholes$fog();

    @Accessor("fogColor")
    void wormholes$fog(Vector3d fog);
}
