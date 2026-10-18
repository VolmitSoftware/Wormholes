package art.arcane.wormholes.modded.mixin.client;

import net.irisshaders.iris.gl.buffer.ShaderStorageBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder", remap = false)
public interface IrisPortalStorageBuffersAccess {
    @Accessor("ACTIVE_BUFFERS")
    static List<ShaderStorageBuffer> wormholes$activeBuffers() {
        throw new AssertionError();
    }
}
