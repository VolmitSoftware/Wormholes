package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.HardcodedCustomUniforms", remap = false)
public interface IrisPortalHardcodedAccess {
    @Accessor("storedBiome")
    static Holder<Biome> wormholes$biome() {
        throw new UnsupportedOperationException();
    }

    @Accessor("storedBiome")
    static void wormholes$biome(Holder<Biome> biome) {
        throw new UnsupportedOperationException();
    }
}
