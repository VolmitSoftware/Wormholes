package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.CrossingBenchTap;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public abstract class CrossingBenchServerTap {
    @Inject(method = "processPacketsAndTick", at = @At("HEAD"))
    private void wormholesBench$cycleStarted(boolean sprinting, CallbackInfo callback) {
        CrossingBenchTap.serverCycleStarted(((MinecraftServer) (Object) this).getTickCount() + 1);
    }

    @Inject(method = "tickServer", at = @At("HEAD"))
    private void wormholesBench$tickStarted(BooleanSupplier haveTime, CallbackInfo callback) {
        CrossingBenchTap.serverTickStarted();
    }

    @Inject(method = "tickServer", at = @At("RETURN"))
    private void wormholesBench$tickEnded(BooleanSupplier haveTime, CallbackInfo callback) {
        CrossingBenchTap.serverTickEnded();
    }

    @WrapMethod(method = "doRunTask(Lnet/minecraft/server/TickTask;)V")
    private void wormholesBench$task(TickTask task, Operation<Void> original) {
        if (!CrossingBenchTap.active()) {
            original.call(task);
            return;
        }
        long started = System.nanoTime();
        try {
            original.call(task);
        } finally {
            CrossingBenchTap.serverTask(System.nanoTime() - started);
        }
    }
}
