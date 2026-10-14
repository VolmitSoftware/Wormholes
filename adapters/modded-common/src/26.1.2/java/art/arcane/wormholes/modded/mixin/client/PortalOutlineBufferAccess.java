package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.OutlineBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(OutlineBufferSource.class)
public interface PortalOutlineBufferAccess {
    @Accessor("outlineBufferSource")
    MultiBufferSource.BufferSource wormholes$buffers();
}
