package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.stencil.PortalClipShaders;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = "net.minecraft.client.renderer.ShaderManager$1")
public abstract class PortalClipIncludeMixin {
    @ModifyReturnValue(method = "applyImport", at = @At("RETURN"))
    private String wormholes$clipInclude(String contents, boolean relative, String path) {
        if (contents == null || relative) {
            return contents;
        }
        return PortalClipShaders.include(Identifier.parse(path).withPrefix("shaders/include/"), contents);
    }
}
