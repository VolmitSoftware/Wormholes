package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.TravelTap;
import art.arcane.wormholes.modded.client.WormholesClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class ResidentRemovalTap {
    @Inject(method = "removeEntity", at = @At("HEAD"))
    private void wormholesTest$residentRemoved(int id, Entity.RemovalReason reason, CallbackInfo callback) {
        ClientLevel level = (ClientLevel) (Object) this;
        if (WormholesClient.activeLevel(level)) {
            return;
        }
        Entity entity = level.getEntity(id);
        if (entity != null) {
            TravelTap.residentRemoved(entity.getUUID());
        }
    }
}
