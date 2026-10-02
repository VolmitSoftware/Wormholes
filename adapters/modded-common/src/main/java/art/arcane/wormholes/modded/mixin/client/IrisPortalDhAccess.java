package art.arcane.wormholes.modded.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.compat.dh.DHCompat", remap = false)
public interface IrisPortalDhAccess {
    @Accessor("lastIncompatible")
    static boolean wormholes$incompatible() {
        throw new UnsupportedOperationException();
    }

    @Accessor("lastIncompatible")
    static void wormholes$incompatible(boolean value) {
        throw new UnsupportedOperationException();
    }
}
