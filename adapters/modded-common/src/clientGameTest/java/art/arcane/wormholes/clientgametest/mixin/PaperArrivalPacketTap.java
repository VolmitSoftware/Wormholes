package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.PaperArrivalFrames;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class PaperArrivalPacketTap {
    @Inject(method = "handleRespawn", at = @At("HEAD"))
    private void wormholesTest$arrivalRespawn(ClientboundRespawnPacket packet, CallbackInfo callback) {
        if (Minecraft.getInstance().isSameThread()) {
            PaperArrivalFrames.respawn(packet);
        }
    }
}
