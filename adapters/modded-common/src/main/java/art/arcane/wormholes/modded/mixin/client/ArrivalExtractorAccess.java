package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelExtractor.class)
public interface ArrivalExtractorAccess {
    @Accessor("level")
    void wormholes$arrivalLevel(ClientLevel level);

    @Accessor("shouldInvalidateCompiledGeometry")
    void wormholes$arrivalGeometryReset(boolean reset);

    @Accessor("shouldResetLevelRenderData")
    void wormholes$arrivalLevelReset(boolean reset);
}
