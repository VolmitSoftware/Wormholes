package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.renderer.CloudRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(CloudRenderer.class)
public interface ClientWorldCloudAccess {
    @Accessor("texture")
    CloudRenderer.TextureData wormholes$texture();

    @Accessor("texture")
    void wormholes$texture(CloudRenderer.TextureData texture);
}
