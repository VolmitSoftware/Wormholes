package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.sodium.SodiumPortalDraws;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;

@Pseudo
@Mixin(value = DefaultChunkRenderer.class, remap = false)
public abstract class SodiumPortalDrawsMixin implements SodiumPortalDraws {
    @Shadow @Final private boolean[] shouldDraw;

    @Override
    public boolean[] wormholes$draws() {
        return shouldDraw.clone();
    }

    @Override
    public void wormholes$draws(boolean[] draws) {
        System.arraycopy(draws, 0, shouldDraw, 0, Math.min(draws.length, shouldDraw.length));
    }
}
