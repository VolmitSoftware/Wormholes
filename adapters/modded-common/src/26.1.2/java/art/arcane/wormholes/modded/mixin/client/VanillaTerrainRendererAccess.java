package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(LevelRenderer.class)
public interface VanillaTerrainRendererAccess {
    @Accessor("sectionRenderDispatcher")
    SectionRenderDispatcher wormholes$terrainDispatcher();

    @Accessor("viewArea")
    ViewArea wormholes$viewArea();

    @Invoker("compileSections")
    void wormholes$compileTerrain(Camera camera);
}
