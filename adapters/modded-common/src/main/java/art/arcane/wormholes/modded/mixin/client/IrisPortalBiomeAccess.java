package art.arcane.wormholes.modded.mixin.client;

import net.irisshaders.iris.parsing.BiomeCategories;
import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.BiomeUniforms", remap = false)
public interface IrisPortalBiomeAccess {
    @Invoker("getBiomeCategory")
    static BiomeCategories wormholes$category(Holder<Biome> biome) {
        throw new UnsupportedOperationException();
    }
}
