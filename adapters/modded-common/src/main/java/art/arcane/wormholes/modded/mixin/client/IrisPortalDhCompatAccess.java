package art.arcane.wormholes.modded.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.compat.dh.DHCompat", remap = false)
public interface IrisPortalDhCompatAccess {
    @Accessor("lastIncompatible")
    static boolean wormholes$lastIncompatible() {
        throw new IllegalStateException("Iris Distant Horizons compatibility state accessor is unavailable");
    }

    @Accessor("lastIncompatible")
    static void wormholes$lastIncompatible(boolean value) {
        throw new IllegalStateException("Iris Distant Horizons compatibility state accessor is unavailable");
    }
}
