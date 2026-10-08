package art.arcane.wormholes.modded.mixin.client;

import com.mojang.blaze3d.platform.Lighting;
import com.mojang.blaze3d.resource.CrossFrameResourcePool;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.fog.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(GameRenderer.class)
public interface PortalGameRendererAccess {
    @Accessor("resourcePool")
    CrossFrameResourcePool wormholes$resourcePool();

    @Accessor("fogRenderer")
    FogRenderer wormholes$fogRenderer();

    @Accessor("lighting")
    Lighting wormholes$lighting();
}
