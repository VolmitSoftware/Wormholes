package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.renderer.LightTexture;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LightTexture.class)
public interface ClientWorldLightmapAccess {
    @Accessor("updateLightTexture")
    void wormholes$needsUpdate(boolean value);

    @Accessor("lightTextureLocation")
    ResourceLocation wormholes$location();
}
