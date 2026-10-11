package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.dh.DhBlazeClipTransformation;
import art.arcane.wormholes.modded.client.render.stencil.PortalClipShaders;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(targets = {"net.minecraft.client.renderer.ShaderManager$Configs", "net.minecraft.client.renderer.GameRenderer$1"})
public abstract class DhPortalBlazeShaderMixin {
    @ModifyReturnValue(method = "getShader", at = @At("RETURN"))
    private String wormholes$clipBlaze(String contents, Identifier location, ShaderType type) {
        if (contents == null || type != ShaderType.VERTEX || !"distanthorizons".equals(location.getNamespace())
            || !PortalClipShaders.openGl()) {
            return contents;
        }
        return DhBlazeClipTransformation.transform(location.getPath(), contents);
    }
}
