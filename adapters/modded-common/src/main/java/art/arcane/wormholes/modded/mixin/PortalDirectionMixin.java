package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftPortalMenus;
import art.arcane.wormholes.modded.MinecraftNexus;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ClientboundSetHeldSlotPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PortalDirectionMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handleSetCarriedItem", at = @At("HEAD"), cancellable = true)
    private void wormholesDialScroll(ServerboundSetCarriedItemPacket packet, CallbackInfo callback) {
        if (!player.level().getServer().isSameThread()) {
            return;
        }
        MinecraftNexus nexus = MinecraftNexus.forServer(player.level().getServer());
        if (nexus != null && nexus.scroll(player, packet.getSlot())) {
            player.connection.send(new ClientboundSetHeldSlotPacket(player.getInventory().getSelectedSlot()));
            callback.cancel();
        }
    }

    @Inject(method = "handleAnimate", at = @At("TAIL"))
    private void wormholesApplyDirection(CallbackInfo callback) {
        MinecraftPortalMenus.directionInput(player, false);
    }

    @Inject(method = {"handleUseItem", "handleUseItemOn"}, at = @At("TAIL"))
    private void wormholesCancelDirection(CallbackInfo callback) {
        MinecraftPortalMenus.directionInput(player, true);
    }
}
