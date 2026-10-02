package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalIrisBiomes;
import art.arcane.wormholes.modded.client.render.PortalShaderContext;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.mixinterface.ExtendedBiome;
import net.irisshaders.iris.uniforms.BiomeUniforms;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.uniforms.BiomeUniforms", remap = false)
public abstract class IrisPortalBiomeMixin {
    @Inject(method = "addBiomeUniforms", at = @At("HEAD"), cancellable = true)
    private static void wormholes$uniforms(UniformHolder uniforms, CallbackInfo callback) {
        if (PortalShaderContext.current() == null) {
            return;
        }
        uniforms.uniform1i(UniformUpdateFrequency.PER_TICK, "biome", () ->
            BiomeUniforms.getBiomeMap().getInt(PortalIrisBiomes.current().unwrapKey().orElse(null)))
            .uniform1i(UniformUpdateFrequency.PER_TICK, "biome_category", () ->
                IrisPortalBiomeAccess.wormholes$category(PortalIrisBiomes.current()).ordinal())
            .uniform1i(UniformUpdateFrequency.PER_TICK, "biome_precipitation", PortalIrisBiomes::precipitation)
            .uniform1f(UniformUpdateFrequency.PER_TICK, "rainfall", () ->
                ((ExtendedBiome) (Object) PortalIrisBiomes.current().value()).getDownfall())
            .uniform1f(UniformUpdateFrequency.PER_TICK, "temperature", () -> PortalIrisBiomes.current().value().getBaseTemperature());
        callback.cancel();
    }
}
