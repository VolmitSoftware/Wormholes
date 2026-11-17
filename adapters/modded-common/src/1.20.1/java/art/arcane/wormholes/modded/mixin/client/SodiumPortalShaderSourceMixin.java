package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.sodium.SodiumClipShaders;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import me.jellysquid.mods.sodium.client.gl.shader.ShaderLoader;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(value = ShaderLoader.class, remap = false)
public abstract class SodiumPortalShaderSourceMixin {
    @ModifyReturnValue(method = "getShaderSource", at = @At("RETURN"))
    private static String wormholes$clipTerrain(String source, @Local(argsOnly = true) ResourceLocation name) {
        return SodiumClipShaders.source(name, source);
    }
}
