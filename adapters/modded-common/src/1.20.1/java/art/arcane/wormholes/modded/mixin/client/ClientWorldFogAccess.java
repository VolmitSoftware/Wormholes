package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(FogRenderer.class)
public interface ClientWorldFogAccess {
    @Accessor("fogRed")
    static float wormholes$red() {
        throw new AssertionError("mixin accessor");
    }

    @Accessor("fogRed")
    static void wormholes$red(float value) {
        throw new AssertionError("mixin accessor");
    }

    @Accessor("fogGreen")
    static float wormholes$green() {
        throw new AssertionError("mixin accessor");
    }

    @Accessor("fogGreen")
    static void wormholes$green(float value) {
        throw new AssertionError("mixin accessor");
    }

    @Accessor("fogBlue")
    static float wormholes$blue() {
        throw new AssertionError("mixin accessor");
    }

    @Accessor("fogBlue")
    static void wormholes$blue(float value) {
        throw new AssertionError("mixin accessor");
    }

    @Accessor("targetBiomeFog")
    static int wormholes$targetBiomeFog() {
        throw new AssertionError("mixin accessor");
    }

    @Accessor("targetBiomeFog")
    static void wormholes$targetBiomeFog(int value) {
        throw new AssertionError("mixin accessor");
    }

    @Accessor("previousBiomeFog")
    static int wormholes$previousBiomeFog() {
        throw new AssertionError("mixin accessor");
    }

    @Accessor("previousBiomeFog")
    static void wormholes$previousBiomeFog(int value) {
        throw new AssertionError("mixin accessor");
    }

    @Accessor("biomeChangedTime")
    static long wormholes$biomeChangedTime() {
        throw new AssertionError("mixin accessor");
    }

    @Accessor("biomeChangedTime")
    static void wormholes$biomeChangedTime(long value) {
        throw new AssertionError("mixin accessor");
    }
}
