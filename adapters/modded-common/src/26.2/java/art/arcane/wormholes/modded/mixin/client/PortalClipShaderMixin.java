package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.stencil.PortalClipShaders;
import art.arcane.wormholes.modded.client.render.dh.DhBlazeTemporalViews;
import com.google.common.collect.ImmutableMap;
import com.mojang.blaze3d.shaders.ShaderType;
import net.minecraft.client.renderer.ShaderManager;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.util.profiling.ProfilerFiller;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ShaderManager.class)
public abstract class PortalClipShaderMixin {
    @Unique
    private static final boolean wormholes$dhPresent = PortalClipShaders.class.getClassLoader()
        .getResource("com/seibel/distanthorizons/core/api/internal/ClientApi.class") != null;

    @Inject(method = "apply(Lnet/minecraft/client/renderer/ShaderManager$Configs;Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)V",
        at = @At("HEAD"))
    private void wormholes$reloadTemporalViews(CallbackInfo callback) {
        if (wormholes$dhPresent) {
            DhBlazeTemporalViews.invalidate();
        }
    }

    @Inject(method = "prepare(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)Lnet/minecraft/client/renderer/ShaderManager$Configs;", at = @At("HEAD"))
    private void wormholes$beginClipShaders(ResourceManager manager, ProfilerFiller profiler, CallbackInfoReturnable<ShaderManager.Configs> callback) {
        PortalClipShaders.beginLoad(manager);
    }

    @Inject(method = "prepare(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/util/profiling/ProfilerFiller;)Lnet/minecraft/client/renderer/ShaderManager$Configs;", at = @At("RETURN"))
    private void wormholes$endClipShaders(ResourceManager manager, ProfilerFiller profiler, CallbackInfoReturnable<ShaderManager.Configs> callback) {
        PortalClipShaders.endLoad();
    }

    @ModifyExpressionValue(method = "loadShader", at = @At(value = "INVOKE",
        target = "Lorg/apache/commons/io/IOUtils;toString(Ljava/io/Reader;)Ljava/lang/String;"))
    private static String wormholes$clipShader(String contents, Identifier location, Resource resource, ShaderType type,
                                               Map<Identifier, Resource> files, ImmutableMap.Builder<?, ?> output) {
        return PortalClipShaders.shader(location, type, contents);
    }
}
