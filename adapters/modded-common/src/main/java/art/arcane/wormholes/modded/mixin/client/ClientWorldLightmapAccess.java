package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.renderer.LightmapRenderStateExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LightmapRenderStateExtractor.class)
public interface ClientWorldLightmapAccess {
    @Accessor("needsUpdate")
    void wormholes$needsUpdate(boolean value);
}
