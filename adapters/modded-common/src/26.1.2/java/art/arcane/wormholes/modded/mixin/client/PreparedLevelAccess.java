package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientLevel.class)
public interface PreparedLevelAccess {
    @Accessor("levelRenderer")
    LevelRenderer wormholes$extractor();

    @Mutable
    @Accessor("levelRenderer")
    void wormholes$extractor(LevelRenderer extractor);
}
