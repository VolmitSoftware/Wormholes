package art.arcane.wormholes.clientgametest.mixin;

import com.mojang.blaze3d.opengl.GlBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GlBuffer.class)
public interface GlBufferHandleAccess {
    @Accessor("handle")
    int wormholesTest$handle();
}
