package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.PortalDeferredShaderMap;
import art.arcane.wormholes.modded.client.render.PortalDeferredShaderPipeline;
import art.arcane.wormholes.modded.client.render.PortalIrisShaderLoading;
import com.google.common.collect.ImmutableList;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.backend.opengl.GlProgram;
import net.irisshaders.iris.features.FeatureFlags;
import net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder;
import net.irisshaders.iris.gl.framebuffer.GlFramebuffer;
import net.irisshaders.iris.gl.image.GlImage;
import net.irisshaders.iris.gl.program.ComputeProgram;
import net.irisshaders.iris.helpers.OptionalBoolean;
import net.irisshaders.iris.pipeline.CustomTextureManager;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.programs.ShaderKey;
import net.irisshaders.iris.pipeline.programs.ShaderMap;
import net.irisshaders.iris.shaderpack.loading.ProgramArrayId;
import net.irisshaders.iris.shaderpack.loading.ProgramId;
import net.irisshaders.iris.shaderpack.programs.ProgramFallbackResolver;
import net.irisshaders.iris.shaderpack.programs.ProgramSet;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.shaderpack.properties.PackShadowDirectives;
import net.irisshaders.iris.shaderpack.texture.TextureStage;
import net.irisshaders.iris.shadows.ShadowCompositeRenderer;
import net.irisshaders.iris.shadows.ShadowRenderTargets;
import net.irisshaders.iris.shadows.ShadowRenderer;
import net.irisshaders.iris.targets.ClearPass;
import net.irisshaders.iris.targets.ClearPassCreator;
import net.irisshaders.iris.targets.RenderTargets;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.irisshaders.iris.uniforms.CapturedRenderingState;
import org.joml.Vector3d;
import org.joml.Vector4f;
import net.irisshaders.iris.uniforms.custom.CustomUniforms;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Set;

@Pseudo
@Mixin(targets = "net.irisshaders.iris.pipeline.IrisRenderingPipeline", remap = false)
public abstract class IrisPortalDeferredPipelineMixin implements PortalDeferredShaderPipeline {
    @Shadow @Final private RenderTargets renderTargets;
    @Shadow @Final private ShaderMap shaderMap;
    @Shadow @Final private ComputeProgram[] setup;
    @Shadow @Final private Set<GlProgram> loadedShaders;
    @Shadow @Final private CustomUniforms customUniforms;
    @Shadow @Final private PackDirectives packDirectives;
    @Shadow @Final private PackShadowDirectives shadowDirectives;
    @Shadow @Final private CustomTextureManager customTextureManager;
    @Shadow @Final private FrameUpdateNotifier updateNotifier;
    @Shadow @Final private Set<GlImage> customImages;
    @Shadow @Final private ProgramFallbackResolver resolver;
    @Shadow private ShaderStorageBufferHolder shaderStorageBufferHolder;
    @Shadow private ShadowRenderTargets shadowRenderTargets;
    @Shadow private ImmutableList<ClearPass> clearPasses;
    @Shadow private ImmutableList<ClearPass> clearPassesFull;
    @Shadow private ImmutableList<ClearPass> shadowClearPasses;
    @Shadow private ImmutableList<ClearPass> shadowClearPassesFull;
    @Shadow private GlFramebuffer defaultFBShadow;
    @Shadow @Final @Mutable private ShadowCompositeRenderer shadowCompositeRenderer;
    @Shadow @Final @Mutable private ShadowRenderer shadowRenderer;

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
        target = "Lnet/irisshaders/iris/uniforms/custom/CustomUniforms;optimise()V"))
    private void wormholes$waitForShaders(CustomUniforms uniforms, Operation<Void> original) {
        if (!PortalIrisShaderLoading.deferred()) {
            original.call(uniforms);
        }
    }

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
        target = "Lnet/irisshaders/iris/gl/program/ComputeProgram;use()V"))
    private void wormholes$waitForSetupUse(ComputeProgram program, Operation<Void> original) {
        if (!PortalIrisShaderLoading.deferred()) {
            original.call(program);
        }
    }

    @WrapOperation(method = "<init>", at = @At(value = "INVOKE",
        target = "Lnet/irisshaders/iris/gl/program/ComputeProgram;dispatch(FF)V"))
    private void wormholes$waitForSetupDispatch(ComputeProgram program, float width, float height, Operation<Void> original) {
        if (!PortalIrisShaderLoading.deferred()) {
            original.call(program, width, height);
        }
    }

    @Override
    public void wormholes$shader(ShaderKey key, GlProgram program) {
        ((PortalDeferredShaderMap) shaderMap).wormholes$shader(key, program);
        loadedShaders.add(program);
    }

    @Override
    public boolean wormholes$hasShadows() {
        return shadowRenderTargets != null;
    }

    @Override
    public void wormholes$finishShaders(ProgramSet programs) {
        IrisRenderingPipeline pipeline = (IrisRenderingPipeline) (Object) this;
        if (shadowRenderTargets != null && shadowCompositeRenderer == null) {
            shadowCompositeRenderer = new ShadowCompositeRenderer(pipeline, packDirectives,
                programs.getComposite(ProgramArrayId.ShadowComposite), programs.getCompute(ProgramArrayId.ShadowComposite),
                shadowRenderTargets, shaderStorageBufferHolder, customTextureManager.getNoiseTexture(), updateNotifier,
                customTextureManager.getCustomTextureIdMap(TextureStage.SHADOWCOMP), customImages,
                packDirectives.getExplicitFlips("shadowcomp_pre"), customTextureManager.getIrisCustomTextures(), customUniforms);
            if (shadowDirectives.isShadowEnabled() != OptionalBoolean.FALSE) {
                shadowRenderer = new ShadowRenderer(pipeline, resolver.resolveNullable(ProgramId.ShadowSolid),
                    packDirectives, shadowRenderTargets, shadowCompositeRenderer, customUniforms,
                    programs.getPack().hasFeature(FeatureFlags.SEPARATE_HARDWARE_SAMPLERS));
            }
            defaultFBShadow = shadowRenderTargets.createFramebufferWritingToMain(new int[]{0});
        }
        if (shadowRenderTargets != null) {
            shadowClearPasses = ClearPassCreator.createShadowClearPasses(shadowRenderTargets, false, shadowDirectives);
            shadowClearPassesFull = ClearPassCreator.createShadowClearPasses(shadowRenderTargets, true, shadowDirectives);
        }
        for (ClearPass pass : clearPassesFull) {
            renderTargets.destroyFramebuffer(pass.getFramebuffer());
        }
        for (ClearPass pass : clearPasses) {
            renderTargets.destroyFramebuffer(pass.getFramebuffer());
        }
        clearPassesFull = ClearPassCreator.createClearPasses(renderTargets, true, packDirectives.getRenderTargetDirectives());
        clearPasses = ClearPassCreator.createClearPasses(renderTargets, false, packDirectives.getRenderTargetDirectives());
        customUniforms.optimise();
    }

    @Override
    public void wormholes$resetShaders() {
        updateNotifier.onNewFrame();
        customUniforms.update();
        for (GlImage image : customImages) {
            image.clear();
        }
        Vector3d color = CapturedRenderingState.INSTANCE.getFogColor();
        Vector4f fog = new Vector4f((float) color.x, (float) color.y, (float) color.z, 1.0f);
        renderTargets.onFullClear();
        for (ClearPass pass : clearPassesFull) {
            pass.execute(fog);
        }
        boolean dispatched = false;
        for (ComputeProgram program : setup) {
            if (program != null) {
                program.use();
                program.dispatch(1, 1);
                dispatched = true;
            }
        }
        if (dispatched) {
            ComputeProgram.unbind();
        }
    }
}
