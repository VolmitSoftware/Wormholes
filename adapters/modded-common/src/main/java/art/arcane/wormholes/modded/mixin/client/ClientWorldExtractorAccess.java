package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelExtractor.class)
public interface ClientWorldExtractorAccess {
    @Accessor("level")
    ClientLevel wormholes$level();

    @Accessor("levelRenderState")
    LevelRenderState wormholes$levelRenderState();

    @Mutable
    @Accessor("levelRenderState")
    void wormholes$levelRenderState(LevelRenderState state);

    @Accessor("shouldResetSkyRenderer")
    void wormholes$resetSkyRenderer(boolean value);
}
