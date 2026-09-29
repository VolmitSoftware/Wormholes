package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftPortalMenus;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class PortalInteractionMixin {
    @Shadow public ServerPlayer player;

    @Inject(method = "handlePunch", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;resetLastActionTime()V"), cancellable = true)
    private void wormholesPunch(ServerboundPunchPacket packet, CallbackInfo callback) {
        if (MinecraftPortalMenus.packetPunch(player)) {
            player.resetLastActionTime();
            callback.cancel();
        }
    }

    @Inject(method = "handleUseItem", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayerGameMode;useItem(Lnet/minecraft/server/level/ServerPlayer;Lnet/minecraft/world/level/Level;Lnet/minecraft/world/item/ItemStack;Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/InteractionResult;"), cancellable = true)
    private void wormholesUse(ServerboundUseItemPacket packet, CallbackInfo callback) {
        if (MinecraftPortalMenus.packetUse(player, packet.hand())) {
            callback.cancel();
        }
    }

    @Inject(method = "handleInteract", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;resetLastActionTime()V"), cancellable = true)
    private void wormholesEntity(ServerboundInteractPacket packet, CallbackInfo callback) {
        if (MinecraftPortalMenus.packetUse(player, packet.hand())) {
            player.resetLastActionTime();
            callback.cancel();
        }
    }
}
