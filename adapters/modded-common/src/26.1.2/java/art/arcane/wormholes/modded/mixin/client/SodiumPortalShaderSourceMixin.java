package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.sodium.SodiumClipShaders;
import art.arcane.wormholes.modded.client.render.stencil.ClipShaderTransformation;
import art.arcane.wormholes.modded.client.render.stencil.PortalClipShaders;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.caffeinemc.mods.sodium.client.gl.shader.ShaderLoader;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(value = ShaderLoader.class, remap = false)
public abstract class SodiumPortalShaderSourceMixin {
    @Unique private static final String GLOBALS = "include/globals.glsl";
    @Unique private static final String VERTEX = ".vsh";

    @ModifyReturnValue(method = "getShaderSource", at = @At("RETURN"))
    private static String wormholes$clipPlane(String source, Identifier name) {
        if (!"sodium".equals(name.getNamespace()) || !PortalClipShaders.openGl()) {
            return source;
        }
        if (GLOBALS.equals(name.getPath())) {
            return SodiumClipShaders.include(source);
        }
        return name.getPath().endsWith(VERTEX) ? ClipShaderTransformation.vertex(name.toString(), source).orElse(source) : source;
    }
}
