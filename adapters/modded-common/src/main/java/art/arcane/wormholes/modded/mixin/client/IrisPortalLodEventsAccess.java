package art.arcane.wormholes.modded.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.compat.dh.LodRendererEvents", remap = false)
public interface IrisPortalLodEventsAccess {
    @Accessor("atTranslucent")
    static boolean wormholes$atTranslucent() {
        throw new IllegalStateException("Iris Distant Horizons translucent state accessor is unavailable");
    }

    @Accessor("atTranslucent")
    static void wormholes$atTranslucent(boolean value) {
        throw new IllegalStateException("Iris Distant Horizons translucent state accessor is unavailable");
    }

    @Accessor("textureWidth")
    static int wormholes$textureWidth() {
        throw new IllegalStateException("Iris Distant Horizons texture width accessor is unavailable");
    }

    @Accessor("textureWidth")
    static void wormholes$textureWidth(int value) {
        throw new IllegalStateException("Iris Distant Horizons texture width accessor is unavailable");
    }

    @Accessor("textureHeight")
    static int wormholes$textureHeight() {
        throw new IllegalStateException("Iris Distant Horizons texture height accessor is unavailable");
    }

    @Accessor("textureHeight")
    static void wormholes$textureHeight(int value) {
        throw new IllegalStateException("Iris Distant Horizons texture height accessor is unavailable");
    }
}
