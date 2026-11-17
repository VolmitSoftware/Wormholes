package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.world.ClientWorldLoader;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;

/**
 * Vanilla terrain only: Sodium and Embeddium overwrite {@code setupRender}, and Mixin refuses to inject into a method
 * another mixin overwrote at the same priority, so PortalMixinPlugin skips this class when either is installed.
 */
@Mixin(LevelRenderer.class)
public abstract class VanillaTerrainSectionDiscoveryMixin {
    @WrapOperation(method = "setupRender", at = @At(value = "INVOKE",
        target = "Ljava/util/concurrent/ExecutorService;submit(Ljava/lang/Runnable;)Ljava/util/concurrent/Future;"))
    private Future<?> wormholes$forcedSectionDiscovery(ExecutorService executor, Runnable discovery, Operation<Future<?>> original) {
        return ClientWorldLoader.discoverSections((LevelRenderer) (Object) this, executor, discovery);
    }
}
