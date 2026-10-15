package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.FrameHitchTap;
import art.arcane.wormholes.clientgametest.IrisPipelineCostTap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

public abstract class IrisConstructionPhaseTap {
    @Pseudo
    @Mixin(targets = "net.irisshaders.iris.pipeline.programs.SodiumPrograms", remap = false)
    public abstract static class SodiumPrograms {
        @Inject(method = "<init>", at = @At(value = "CTOR_HEAD", args = "enforce=POST_DELEGATE", unsafe = true), require = 0)
        private void wormholesHitch$started(CallbackInfo callback) {
            FrameHitchTap.phaseStarted("sodiumPrograms");
        }

        @Inject(method = "<init>", at = @At("RETURN"), require = 0)
        private void wormholesHitch$ended(CallbackInfo callback) {
            FrameHitchTap.phaseEnded("sodiumPrograms");
        }
    }

    @Pseudo
    @Mixin(targets = "net.irisshaders.iris.pipeline.programs.SodiumPrograms", remap = false)
    public abstract static class SodiumTransform {
        @Inject(method = "transformShaders", at = @At("HEAD"), require = 0)
        private void wormholesHitch$started(CallbackInfoReturnable<?> callback) {
            FrameHitchTap.phaseStarted("sodiumTransform");
        }

        @Inject(method = "transformShaders", at = @At("RETURN"), require = 0)
        private void wormholesHitch$ended(CallbackInfoReturnable<?> callback) {
            FrameHitchTap.phaseEnded("sodiumTransform");
        }
    }

    @Pseudo
    @Mixin(targets = "net.caffeinemc.mods.sodium.client.gl.shader.GlShader", remap = false)
    public abstract static class SodiumCompile {
        @Inject(method = "<init>", at = @At(value = "CTOR_HEAD", args = "enforce=POST_DELEGATE", unsafe = true), require = 0)
        private void wormholesHitch$started(CallbackInfo callback) {
            FrameHitchTap.phaseStarted("sodiumCompile");
        }

        @Inject(method = "<init>", at = @At("RETURN"), require = 0)
        private void wormholesHitch$ended(CallbackInfo callback) {
            FrameHitchTap.phaseEnded("sodiumCompile");
        }
    }

    @Pseudo
    @Mixin(targets = "net.caffeinemc.mods.sodium.client.gl.shader.GlProgram$Builder", remap = false)
    public abstract static class SodiumLink {
        @Unique
        private long wormholesHitch$linkStarted;

        @Inject(method = "link", at = @At("HEAD"), require = 0)
        private void wormholesHitch$started(CallbackInfoReturnable<?> callback) {
            FrameHitchTap.phaseStarted("sodiumLink");
            wormholesHitch$linkStarted = System.nanoTime();
        }

        @Inject(method = "link", at = @At("RETURN"), require = 0)
        private void wormholesHitch$ended(CallbackInfoReturnable<?> callback) {
            FrameHitchTap.phaseEnded("sodiumLink");
            IrisPipelineCostTap.linked(System.nanoTime() - wormholesHitch$linkStarted, IrisPipelineCostTap.drawing());
        }
    }

    @Pseudo
    @Mixin(targets = "net.irisshaders.iris.pipeline.programs.ShaderMap", remap = false)
    public abstract static class Shaders {
        @Inject(method = "<init>", at = @At(value = "CTOR_HEAD", args = "enforce=POST_DELEGATE", unsafe = true), require = 0)
        private void wormholesHitch$started(CallbackInfo callback) {
            FrameHitchTap.phaseStarted("shaderMap");
        }

        @Inject(method = "<init>", at = @At("RETURN"), require = 0)
        private void wormholesHitch$ended(CallbackInfo callback) {
            FrameHitchTap.phaseEnded("shaderMap");
        }
    }

    @Pseudo
    @Mixin(targets = "net.irisshaders.iris.pipeline.CompositeRenderer", remap = false)
    public abstract static class Composite {
        @Inject(method = "<init>", at = @At(value = "CTOR_HEAD", args = "enforce=POST_DELEGATE", unsafe = true), require = 0)
        private void wormholesHitch$started(CallbackInfo callback) {
            FrameHitchTap.phaseStarted("composite");
        }

        @Inject(method = "<init>", at = @At("RETURN"), require = 0)
        private void wormholesHitch$ended(CallbackInfo callback) {
            FrameHitchTap.phaseEnded("composite");
        }
    }

    @Pseudo
    @Mixin(targets = "net.irisshaders.iris.pipeline.FinalPassRenderer", remap = false)
    public abstract static class FinalPass {
        @Inject(method = "<init>", at = @At(value = "CTOR_HEAD", args = "enforce=POST_DELEGATE", unsafe = true), require = 0)
        private void wormholesHitch$started(CallbackInfo callback) {
            FrameHitchTap.phaseStarted("finalPass");
        }

        @Inject(method = "<init>", at = @At("RETURN"), require = 0)
        private void wormholesHitch$ended(CallbackInfo callback) {
            FrameHitchTap.phaseEnded("finalPass");
        }
    }

    @Pseudo
    @Mixin(targets = "net.irisshaders.iris.pipeline.CustomTextureManager", remap = false)
    public abstract static class CustomTextures {
        @Inject(method = "<init>", at = @At(value = "CTOR_HEAD", args = "enforce=POST_DELEGATE", unsafe = true), require = 0)
        private void wormholesHitch$started(CallbackInfo callback) {
            FrameHitchTap.phaseStarted("customTextures");
        }

        @Inject(method = "<init>", at = @At("RETURN"), require = 0)
        private void wormholesHitch$ended(CallbackInfo callback) {
            FrameHitchTap.phaseEnded("customTextures");
        }
    }

    @Pseudo
    @Mixin(targets = "net.irisshaders.iris.targets.RenderTargets", remap = false)
    public abstract static class Targets {
        @Inject(method = "<init>", at = @At(value = "CTOR_HEAD", args = "enforce=POST_DELEGATE", unsafe = true), require = 0)
        private void wormholesHitch$started(CallbackInfo callback) {
            FrameHitchTap.phaseStarted("renderTargets");
        }

        @Inject(method = "<init>", at = @At("RETURN"), require = 0)
        private void wormholesHitch$ended(CallbackInfo callback) {
            FrameHitchTap.phaseEnded("renderTargets");
        }
    }

    @Pseudo
    @Mixin(targets = "net.irisshaders.iris.shadows.ShadowRenderTargets", remap = false)
    public abstract static class ShadowTargets {
        @Inject(method = "<init>", at = @At(value = "CTOR_HEAD", args = "enforce=POST_DELEGATE", unsafe = true), require = 0)
        private void wormholesHitch$started(CallbackInfo callback) {
            FrameHitchTap.phaseStarted("shadowTargets");
        }

        @Inject(method = "<init>", at = @At("RETURN"), require = 0)
        private void wormholesHitch$ended(CallbackInfo callback) {
            FrameHitchTap.phaseEnded("shadowTargets");
        }
    }

    @Pseudo
    @Mixin(targets = "net.irisshaders.iris.shadows.ShadowRenderer", remap = false)
    public abstract static class Shadow {
        @Inject(method = "<init>", at = @At(value = "CTOR_HEAD", args = "enforce=POST_DELEGATE", unsafe = true), require = 0)
        private void wormholesHitch$started(CallbackInfo callback) {
            FrameHitchTap.phaseStarted("shadowRenderer");
        }

        @Inject(method = "<init>", at = @At("RETURN"), require = 0)
        private void wormholesHitch$ended(CallbackInfo callback) {
            FrameHitchTap.phaseEnded("shadowRenderer");
        }
    }

    @Pseudo
    @Mixin(targets = "net.irisshaders.iris.shadows.ShadowCompositeRenderer", remap = false)
    public abstract static class ShadowComposite {
        @Inject(method = "<init>", at = @At(value = "CTOR_HEAD", args = "enforce=POST_DELEGATE", unsafe = true), require = 0)
        private void wormholesHitch$started(CallbackInfo callback) {
            FrameHitchTap.phaseStarted("shadowComposite");
        }

        @Inject(method = "<init>", at = @At("RETURN"), require = 0)
        private void wormholesHitch$ended(CallbackInfo callback) {
            FrameHitchTap.phaseEnded("shadowComposite");
        }
    }

    @Pseudo
    @Mixin(targets = "net.irisshaders.iris.uniforms.custom.CustomUniforms$Builder", remap = false)
    public abstract static class Uniforms {
        @Inject(method = "build([Ljava/util/function/Consumer;)Lnet/irisshaders/iris/uniforms/custom/CustomUniforms;", at = @At("HEAD"), require = 0)
        private void wormholesHitch$started(CallbackInfoReturnable<?> callback) {
            FrameHitchTap.phaseStarted("customUniforms");
        }

        @Inject(method = "build([Ljava/util/function/Consumer;)Lnet/irisshaders/iris/uniforms/custom/CustomUniforms;", at = @At("RETURN"), require = 0)
        private void wormholesHitch$ended(CallbackInfoReturnable<?> callback) {
            FrameHitchTap.phaseEnded("customUniforms");
        }
    }

    @Pseudo
    @Mixin(targets = "net.irisshaders.iris.gl.image.GlImage", remap = false)
    public abstract static class Images {
        @Inject(method = "<init>", at = @At(value = "CTOR_HEAD", args = "enforce=POST_DELEGATE", unsafe = true), require = 0)
        private void wormholesHitch$started(CallbackInfo callback) {
            FrameHitchTap.phaseStarted("customImages");
        }

        @Inject(method = "<init>", at = @At("RETURN"), require = 0)
        private void wormholesHitch$ended(CallbackInfo callback) {
            FrameHitchTap.phaseEnded("customImages");
        }
    }

    @Pseudo
    @Mixin(targets = "net.irisshaders.iris.gl.buffer.ShaderStorageBufferHolder", remap = false)
    public abstract static class Storage {
        @Inject(method = "<init>", at = @At(value = "CTOR_HEAD", args = "enforce=POST_DELEGATE", unsafe = true), require = 0)
        private void wormholesHitch$started(CallbackInfo callback) {
            FrameHitchTap.phaseStarted("storageBuffers");
        }

        @Inject(method = "<init>", at = @At("RETURN"), require = 0)
        private void wormholesHitch$ended(CallbackInfo callback) {
            FrameHitchTap.phaseEnded("storageBuffers");
        }
    }

    @Pseudo
    @Mixin(targets = "net.irisshaders.iris.compat.dh.DHCompat", remap = false)
    public abstract static class DistantHorizons {
        @Inject(method = "<init>", at = @At(value = "CTOR_HEAD", args = "enforce=POST_DELEGATE", unsafe = true), require = 0)
        private void wormholesHitch$started(CallbackInfo callback) {
            FrameHitchTap.phaseStarted("dhCompat");
        }

        @Inject(method = "<init>", at = @At("RETURN"), require = 0)
        private void wormholesHitch$ended(CallbackInfo callback) {
            FrameHitchTap.phaseEnded("dhCompat");
        }
    }

    @Pseudo
    @Mixin(targets = "net.irisshaders.iris.gl.program.ProgramUniforms$Builder", remap = false)
    public abstract static class ProgramUniforms {
        @Inject(method = "buildUniforms", at = @At("HEAD"), require = 0)
        private void wormholesHitch$started(CallbackInfoReturnable<?> callback) {
            FrameHitchTap.phaseStarted("programUniforms");
        }

        @Inject(method = "buildUniforms", at = @At("RETURN"), require = 0)
        private void wormholesHitch$ended(CallbackInfoReturnable<?> callback) {
            FrameHitchTap.phaseEnded("programUniforms");
        }
    }

    @Pseudo
    @Mixin(targets = "net.irisshaders.iris.gl.program.ProgramSamplers$Builder", remap = false)
    public abstract static class ProgramSamplers {
        @Inject(method = "build", at = @At("HEAD"), require = 0)
        private void wormholesHitch$started(CallbackInfoReturnable<?> callback) {
            FrameHitchTap.phaseStarted("programSamplers");
        }

        @Inject(method = "build", at = @At("RETURN"), require = 0)
        private void wormholesHitch$ended(CallbackInfoReturnable<?> callback) {
            FrameHitchTap.phaseEnded("programSamplers");
        }
    }
}
