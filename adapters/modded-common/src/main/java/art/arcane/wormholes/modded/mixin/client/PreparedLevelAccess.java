package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientLevel.class)
public interface PreparedLevelAccess {
    @Accessor("levelExtractor")
    LevelExtractor wormholes$extractor();

    @Mutable
    @Accessor("clientLevelData")
    void wormholes$data(ClientLevel.ClientLevelData data);

    @Mutable
    @Accessor("levelExtractor")
    void wormholes$extractor(LevelExtractor extractor);
}
