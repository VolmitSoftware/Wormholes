package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.CrossingBenchTap;
import art.arcane.wormholes.modded.seamless.RemoteRoute;
import art.arcane.wormholes.modded.seamless.RoutedSends;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(RoutedSends.class)
public abstract class CrossingBenchRoutedTap {
    @WrapMethod(method = "send")
    private int wormholesBench$routed(RemoteRoute route, Packet<? super ClientGamePacketListener> packet, Operation<Integer> original) {
        int bytes = original.call(route, packet);
        if (CrossingBenchTap.active() && packet != null && bytes != 0) {
            CrossingBenchTap.routed(packet, bytes);
        }
        return bytes;
    }
}
