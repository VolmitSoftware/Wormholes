package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.CrossingBenchTap;
import art.arcane.wormholes.modded.WormholesModRuntime;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(WormholesModRuntime.class)
public abstract class CrossingBenchWormholesTap {
    @WrapMethod(method = "tick")
    private void wormholesBench$tick(Operation<Void> original) {
        if (!CrossingBenchTap.active()) {
            original.call();
            return;
        }
        long started = System.nanoTime();
        try {
            original.call();
        } finally {
            CrossingBenchTap.wormholesTick(System.nanoTime() - started);
        }
    }
}
