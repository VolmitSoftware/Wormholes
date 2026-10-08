package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LevelRenderer.class)
public interface ClientWorldLevelRendererAccess {
    @Accessor("levelRenderState")
    LevelRenderState wormholes$levelRenderState();

    @Mutable
    @Accessor("levelRenderState")
    void wormholes$levelRenderState(LevelRenderState state);

    @Invoker("compileSections")
    void wormholes$compileSections(CameraRenderState camera);
}
