package art.arcane.wormholes.clientgametest.mixin;

import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import net.minecraft.client.Minecraft;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@Mixin(Minecraft.class)
public abstract class DistantHorizonsClientShutdownMixin {
    @Unique
    private static final Logger wormholesTest$logger = LoggerFactory.getLogger("WormholesClientGameTest");

    @Inject(method = "close()V", at = @At("RETURN"))
    private void wormholesTest$closeBeaconExecutor(CallbackInfo callback) {
        if (!Boolean.getBoolean("wormholes.test.dhShutdown")) {
            return;
        }
        ThreadPoolExecutor executor = ThreadPoolUtil.getBeaconCullingExecutor();
        if (executor == null) {
            return;
        }
        int discardedTasks = executor.shutdownNow().size();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Distant Horizons beacon executor did not terminate after client close");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while closing the Distant Horizons beacon executor", exception);
        }
        wormholesTest$logger.info("Distant Horizons beacon executor terminated after client close; discarded {} queued tasks", discardedTasks);
    }
}
