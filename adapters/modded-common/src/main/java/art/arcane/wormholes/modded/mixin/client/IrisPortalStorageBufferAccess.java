package art.arcane.wormholes.modded.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.gl.buffer.ShaderStorageBuffer", remap = false)
public interface IrisPortalStorageBufferAccess {
    @Invoker("destroy")
    void wormholes$destroy();
}
