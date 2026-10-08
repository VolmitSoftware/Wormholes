package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Minecraft.class)
public interface ClientWorldMinecraftAccess {
    @Mutable
    @Accessor("levelRenderer")
    void wormholes$levelRenderer(LevelRenderer renderer);

    @Mutable
    @Accessor("levelExtractor")
    void wormholes$levelExtractor(LevelExtractor extractor);
}
