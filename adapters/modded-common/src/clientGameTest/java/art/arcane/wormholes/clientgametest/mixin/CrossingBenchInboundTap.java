package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.optics.stream.ViewStreamInbound;
import art.arcane.wormholes.clientgametest.CrossingBenchTap;
import art.arcane.wormholes.modded.clientview.MinecraftClientViewService;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(MinecraftClientViewService.class)
public abstract class CrossingBenchInboundTap {
    @WrapMethod(method = "receivePlayAt")
    private ViewStreamInbound wormholesBench$inbound(ServerPlayer player, byte[] payload, long receivedNanos, Operation<ViewStreamInbound> original) {
        if (!CrossingBenchTap.active()) {
            return original.call(player, payload, receivedNanos);
        }
        long started = System.nanoTime();
        try {
            return original.call(player, payload, receivedNanos);
        } finally {
            CrossingBenchTap.wormholesInbound(System.nanoTime() - started);
        }
    }
}
