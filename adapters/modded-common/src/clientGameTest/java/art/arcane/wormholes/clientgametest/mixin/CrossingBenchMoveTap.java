package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.CrossingBenchTap;
import art.arcane.wormholes.modded.seamless.MinecraftSeamlessMove;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(MinecraftSeamlessMove.class)
public abstract class CrossingBenchMoveTap {
    @WrapMethod(method = "run")
    private static boolean wormholesBench$move(MinecraftSeamlessMove.Context context, Operation<Boolean> original) {
        if (!CrossingBenchTap.active()) {
            return original.call(context);
        }
        String from = context.player().level().dimension().identifier().toString();
        long started = System.nanoTime();
        boolean moved = false;
        try {
            moved = original.call(context);
            return moved;
        } finally {
            CrossingBenchTap.seamlessMove(started, System.nanoTime() - started, from, context.destination().dimension().identifier().toString(), moved);
        }
    }
}
