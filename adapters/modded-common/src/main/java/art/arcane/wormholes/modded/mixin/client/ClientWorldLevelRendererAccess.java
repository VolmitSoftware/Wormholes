package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelRenderer.class)
public interface ClientWorldLevelRendererAccess {
    @Mutable
    @Accessor("levelRenderState")
    void wormholes$levelRenderState(LevelRenderState state);
}
