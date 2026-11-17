package art.arcane.wormholes.modded.mixin.client;

import com.mojang.blaze3d.vertex.VertexBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(VertexBuffer.class)
public interface PreparedVertexBufferAccess {
    @Accessor("indexCount")
    int wormholes$indexCount();
}
