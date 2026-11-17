package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameRenderer.class)
public interface ClientWorldGameRendererAccess {
    @Mutable
    @Accessor("lightTexture")
    void wormholes$lightTexture(LightTexture lightTexture);
}
