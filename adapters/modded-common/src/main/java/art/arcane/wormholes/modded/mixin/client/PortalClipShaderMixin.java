package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.stencil.PortalClipShaders;
import com.google.common.collect.ImmutableMap;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ShaderManager.class)
public abstract class PortalClipShaderMixin {
    @Inject(method = "loadConfigs", at = @At("HEAD"))
    private static void wormholes$beginClipShaders(ResourceManager manager, CallbackInfoReturnable<ShaderManager.Configs> callback) {
        PortalClipShaders.beginLoad(manager);
    }

    @Inject(method = "loadConfigs", at = @At("RETURN"))
    private static void wormholes$endClipShaders(ResourceManager manager, CallbackInfoReturnable<ShaderManager.Configs> callback) {
        PortalClipShaders.endLoad();
    }

    @ModifyVariable(method = "loadShader", at = @At("STORE"), ordinal = 0)
    private static String wormholes$clipShader(String contents, Identifier location, Resource resource, ShaderType type,
                                               ImmutableMap.Builder<?, ?> output) {
        return PortalClipShaders.shader(location, type, contents);
    }

    @ModifyVariable(method = "loadInclude", at = @At("STORE"), ordinal = 0)
    private static String wormholes$clipInclude(String contents, Identifier location, Resource resource, ImmutableMap.Builder<?, ?> output) {
        return PortalClipShaders.include(location, contents);
    }
}
