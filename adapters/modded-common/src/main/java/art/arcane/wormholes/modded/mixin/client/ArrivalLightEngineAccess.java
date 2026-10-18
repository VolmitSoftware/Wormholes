package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.lighting.LevelLightEngine;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LevelLightEngine.class)
public interface ArrivalLightEngineAccess {
    @Mutable
    @Accessor("levelHeightAccessor")
    void wormholes$arrivalHeight(LevelHeightAccessor level);
}
