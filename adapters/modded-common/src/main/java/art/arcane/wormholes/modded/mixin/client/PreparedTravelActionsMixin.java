package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.WormholesClient;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class PreparedTravelActionsMixin {
    @Inject(method = {"destroyBlock", "startDestroyBlock", "continueDestroyBlock"}, at = @At("HEAD"), cancellable = true)
    private void wormholes$blockActions(CallbackInfoReturnable<Boolean> callback) {
        if (pending()) {
            callback.setReturnValue(false);
        }
    }

    @Inject(method = {"useItem", "useItemOn", "interact"}, at = @At("HEAD"), cancellable = true)
    private void wormholes$useActions(CallbackInfoReturnable<InteractionResult> callback) {
        if (pending()) {
            callback.setReturnValue(InteractionResult.PASS);
        }
    }

    @Inject(method = {"attack", "piercingAttack", "stopDestroyBlock", "dropItem", "handleCreativeModeItemDrop", "handlePickItemFromBlock", "handlePickItemFromEntity", "releaseUsingItem", "handleContainerInput", "handlePlaceRecipe", "handleInventoryButtonClick", "handleCreativeModeItemAdd", "handleSlotStateChanged", "spectate", "spectatorNoAction"},
        at = @At("HEAD"), cancellable = true)
    private void wormholes$entityActions(CallbackInfo callback) {
        if (pending()) {
            callback.cancel();
        }
    }

    private static boolean pending() {
        WormholesClient client = WormholesClient.instance();
        return client != null && client.preparedTravel().pendingCrossing();
    }
}
