package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.renderer.FogRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The fog colour and biome blend statics a portal layer saves and restores around its {@code renderLevel}. */
@Mixin(FogRenderer.class)
public interface PortalFogRendererAccess {
    @Accessor("fogRed")
    static float wormholes$red() {
        throw new AssertionError();
    }

    @Accessor("fogRed")
    static void wormholes$red(float value) {
        throw new AssertionError();
    }

    @Accessor("fogGreen")
    static float wormholes$green() {
        throw new AssertionError();
    }

    @Accessor("fogGreen")
    static void wormholes$green(float value) {
        throw new AssertionError();
    }

    @Accessor("fogBlue")
    static float wormholes$blue() {
        throw new AssertionError();
    }

    @Accessor("fogBlue")
    static void wormholes$blue(float value) {
        throw new AssertionError();
    }

    @Accessor("targetBiomeFog")
    static int wormholes$targetBiome() {
        throw new AssertionError();
    }

    @Accessor("targetBiomeFog")
    static void wormholes$targetBiome(int value) {
        throw new AssertionError();
    }

    @Accessor("previousBiomeFog")
    static int wormholes$previousBiome() {
        throw new AssertionError();
    }

    @Accessor("previousBiomeFog")
    static void wormholes$previousBiome(int value) {
        throw new AssertionError();
    }

    @Accessor("biomeChangedTime")
    static long wormholes$biomeChanged() {
        throw new AssertionError();
    }

    @Accessor("biomeChangedTime")
    static void wormholes$biomeChanged(long value) {
        throw new AssertionError();
    }
}
