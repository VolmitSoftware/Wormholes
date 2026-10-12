package art.arcane.automator.mixin;

import art.arcane.automator.CrossingCosts;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "art.arcane.wormholes.modded.client.ClientLevelSwitch", remap = false)
public abstract class CrossingCostActivationMixin {
    @Inject(method = "activate", at = @At("HEAD"))
    private static void automator$activateBegin(@Coerce Object residents, ClientLevel destination, @Coerce Object pose, @Coerce Object carry,
                                              CallbackInfo callback) {
        CrossingCosts.begin(CrossingCosts.Phase.ACTIVATE, destination.dimension().identifier().toString());
    }

    @Inject(method = "activate", at = @At("RETURN"))
    private static void automator$activateEnd(CallbackInfo callback) {
        CrossingCosts.end(CrossingCosts.Phase.ACTIVATE);
    }

    @Inject(method = "activate", at = @At(value = "INVOKE",
        target = "Lart/arcane/wormholes/modded/client/PreparedChunkColumns;wormholes$announceColumns()V"))
    private static void automator$announceBegin(CallbackInfo callback) {
        CrossingCosts.begin(CrossingCosts.Phase.ANNOUNCE_CHUNKS);
    }

    @Inject(method = "activate", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
        target = "Lart/arcane/wormholes/modded/client/PreparedChunkColumns;wormholes$announceColumns()V"))
    private static void automator$announceEnd(CallbackInfo callback) {
        CrossingCosts.end(CrossingCosts.Phase.ANNOUNCE_CHUNKS);
    }
}
