package art.arcane.wormholes.clientgametest.mixin;

import net.minecraft.client.server.IntegratedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(IntegratedServer.class)
public abstract class FabricGameTestShutdownMixin {
    @Inject(method = "halt(Z)V", at = @At("HEAD"), cancellable = true)
    private void wormholesTest$shutdownOnServer(boolean waitForServer, CallbackInfo callback) {
        if (waitForServer || !Boolean.getBoolean("wormholes.test.fabricPhasedShutdown")) {
            return;
        }
        IntegratedServer server = (IntegratedServer) (Object) this;
        if (server.isSameThread() || server.isStopped()) {
            return;
        }
        server.execute(() -> server.halt(false));
        callback.cancel();
    }
}
