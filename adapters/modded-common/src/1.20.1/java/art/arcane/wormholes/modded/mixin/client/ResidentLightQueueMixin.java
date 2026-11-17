package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Queued chunk light work reads {@code ClientPacketListener.level} when it runs, not when it was queued. A level whose
 * queue is polled while another level is the connection's (a resident level drawn through a portal) runs it with
 * itself swapped in, so its light lands in its own light engine.
 */
@Mixin(ClientLevel.class)
public abstract class ResidentLightQueueMixin {
    @Inject(method = "pollLightUpdates", at = @At("HEAD"), cancellable = true)
    private void wormholes$ownLightQueue(CallbackInfo callback) {
        ClientLevel level = (ClientLevel) (Object) this;
        Minecraft minecraft = Minecraft.getInstance();
        ClientPacketListener connection = minecraft.getConnection();
        if (connection == null || connection.getLevel() == level || level.isLightUpdateQueueEmpty()) {
            return;
        }
        callback.cancel();
        PreparedPacketAccess access = (PreparedPacketAccess) connection;
        ClientLevel previousLevel = minecraft.level;
        ClientLevel previousConnectionLevel = connection.getLevel();
        ClientLevel.ClientLevelData previousData = access.wormholes$data();
        minecraft.level = level;
        access.wormholes$level(level);
        access.wormholes$data(level.getLevelData());
        try {
            level.pollLightUpdates();
        } finally {
            minecraft.level = previousLevel;
            access.wormholes$level(previousConnectionLevel);
            access.wormholes$data(previousData);
        }
    }
}
