package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.CardinalLightingWrites;
import com.mojang.blaze3d.platform.Lighting;
import org.joml.Vector3fc;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Lighting.class)
public abstract class CardinalLightingWritesMixin implements CardinalLightingWrites {
    @Unique
    private int wormholesTest$levelWrites;

    @Override
    public int wormholesTest$levelWrites() {
        return wormholesTest$levelWrites;
    }

    @Inject(method = "updateBuffer", at = @At("HEAD"))
    private void wormholesTest$levelWrite(Lighting.Entry entry, Vector3fc first, Vector3fc second, CallbackInfo callback) {
        if (entry == Lighting.Entry.LEVEL) {
            wormholesTest$levelWrites++;
        }
    }
}
