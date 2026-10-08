package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.renderer.state.level.LevelRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelExtractor.class)
public interface PortalLevelExtractorAccess {
    @Accessor("levelRenderState")
    LevelRenderState wormholes$portalState();

    @Accessor("level")
    ClientLevel wormholes$portalLevel();
}
