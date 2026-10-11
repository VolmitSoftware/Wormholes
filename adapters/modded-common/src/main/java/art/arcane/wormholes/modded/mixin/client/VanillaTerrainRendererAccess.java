package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LevelRenderer.class)
public interface VanillaTerrainRendererAccess {
    @Accessor("sectionRenderDispatcher")
    SectionRenderDispatcher wormholes$terrainDispatcher();

    @Invoker("compileSections")
    void wormholes$compileTerrain(CameraRenderState camera);
}
