package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalMainWorldAccess;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(Minecraft.class)
public abstract class IrisPortalMainMinecraftMixin implements PortalMainWorldAccess {
    @Shadow @Final @Mutable public LevelExtractor levelExtractor;

    @Override
    public void wormholes$mainExtractor(LevelExtractor extractor) {
        levelExtractor = extractor;
    }
}
