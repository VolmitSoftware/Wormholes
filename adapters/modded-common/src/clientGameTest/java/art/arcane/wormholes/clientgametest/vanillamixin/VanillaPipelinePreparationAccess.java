package art.arcane.wormholes.clientgametest.vanillamixin;

import art.arcane.wormholes.clientgametest.VanillaPipelinePreparationTap;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import art.arcane.wormholes.clientgametest.VanillaPipelinePreparationTap.BankAccess;
import art.arcane.wormholes.clientgametest.VanillaPipelinePreparationTap.Counts;
import art.arcane.wormholes.clientgametest.VanillaPipelinePreparationTap.Key;
import art.arcane.wormholes.clientgametest.VanillaPipelinePreparationTap.Product;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.backend.opengl.GlRenderPipeline;
import com.mojang.renderpearl.frontend.FrontendRenderPipeline;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.renderer.chunk.ChunkSectionLayer;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Mixin(targets = "art.arcane.wormholes.modded.client.render.PortalPipelines", remap = false)
public abstract class VanillaPipelinePreparationAccess implements BankAccess {
    @Accessor("descriptors")
    public abstract List<RenderPipeline> wormholesTest$descriptors();

    @Accessor("compiled")
    public abstract Map<RenderPipeline, CompiledRenderPipeline> wormholesTest$compiled();

    @Accessor("terrain")
    public abstract EnumMap<ChunkSectionLayer, RenderPipeline> wormholesTest$terrain();

    @Accessor("reflectedTerrain")
    public abstract EnumMap<ChunkSectionLayer, RenderPipeline> wormholesTest$reflectedTerrain();

    @Accessor("rgssTerrain")
    public abstract EnumMap<ChunkSectionLayer, RenderPipeline> wormholesTest$rgssTerrain();

    @Accessor("reflectedRgssTerrain")
    public abstract EnumMap<ChunkSectionLayer, RenderPipeline> wormholesTest$reflectedRgssTerrain();

    @Accessor("composite")
    public abstract RenderPipeline wormholesTest$compositeDescriptor();

    @Accessor("compositeShape")
    public abstract RenderPipeline wormholesTest$compositeShapeDescriptor();

    @Accessor("feather")
    public abstract RenderPipeline wormholesTest$featherDescriptor();

    @Accessor("layer")
    public abstract RenderPipeline wormholesTest$layerDescriptor();

    @Accessor("prepared")
    public abstract boolean prepared();

    @Accessor("closed")
    public abstract boolean closed();

    @Invoker("terrain")
    public abstract CompiledRenderPipeline wormholesTest$getTerrain(ChunkSectionLayer layer, boolean reflected, boolean rgss);

    @Invoker("composite")
    public abstract CompiledRenderPipeline wormholesTest$getComposite();

    @Invoker("compositeShape")
    public abstract CompiledRenderPipeline wormholesTest$getCompositeShape();

    @Invoker("feather")
    public abstract CompiledRenderPipeline wormholesTest$getFeather();

    @Invoker("layer")
    public abstract CompiledRenderPipeline wormholesTest$getLayer();

    @Override
    public int descriptorCount() {
        return wormholesTest$descriptors().size();
    }

    @Override
    public int compiledCount() {
        return wormholesTest$compiled().size();
    }

    @Override
    public boolean containsDescriptor(Object descriptor) {
        for (RenderPipeline entry : wormholesTest$descriptors()) {
            if (entry == descriptor) {
                return true;
            }
        }
        return false;
    }

    @Override
    public Product product(Key key) {
        RenderPipeline descriptor = wormholesTest$descriptor(key);
        CompiledRenderPipeline compiled = wormholesTest$compiled().get(descriptor);
        VanillaPipelinePreparationTap.require(compiled instanceof FrontendRenderPipeline,
            "fixed product is not a native RenderPearl frontend pipeline: " + key);
        FrontendRenderPipeline frontend = (FrontendRenderPipeline) compiled;
        VanillaPipelinePreparationTap.require(frontend.backendRenderPipeline() instanceof GlRenderPipeline,
            "fixed-bank acceptance requires the actual OpenGL backend: " + key);
        GlRenderPipeline backend = (GlRenderPipeline) frontend.backendRenderPipeline();
        return new Product(key, descriptor, compiled, backend.program(), backend.program().getProgramId(), !compiled.isClosed());
    }

    @Override
    public void verifyGetter(Product expected) {
        Key key = expected.key();
        CompiledRenderPipeline result = key.terrain()
            ? wormholesTest$getTerrain(key.layer(), key.reflected(), key.rgss())
            : switch (key) {
                case COMPOSITE -> wormholesTest$getComposite();
                case COMPOSITE_SHAPE -> wormholesTest$getCompositeShape();
                case FEATHER -> wormholesTest$getFeather();
                case LAYER -> wormholesTest$getLayer();
                default -> throw new AssertionError("Unsupported fixed getter: " + key);
            };
        VanillaPipelinePreparationTap.require(result == expected.compiled(), "fixed getter replaced its compiled handle: " + key);
    }

    public RenderPipeline wormholesTest$descriptor(Key key) {
        RenderPipeline descriptor;
        if (key.terrain()) {
            EnumMap<ChunkSectionLayer, RenderPipeline> bank = key.rgss()
                ? key.reflected() ? wormholesTest$reflectedRgssTerrain() : wormholesTest$rgssTerrain()
                : key.reflected() ? wormholesTest$reflectedTerrain() : wormholesTest$terrain();
            descriptor = bank.get(key.layer());
            VanillaPipelinePreparationTap.require(descriptor != null && descriptor.getShaderDefines().flags().contains("USE_RGSS") == key.rgss()
                && (!key.reflected() || !descriptor.isCull()), "fixed terrain descriptor has incorrect RGSS or reflection state: " + key);
        } else {
            descriptor = switch (key) {
                case COMPOSITE -> wormholesTest$compositeDescriptor();
                case COMPOSITE_SHAPE -> wormholesTest$compositeShapeDescriptor();
                case FEATHER -> wormholesTest$featherDescriptor();
                case LAYER -> wormholesTest$layerDescriptor();
                default -> throw new AssertionError("Unsupported fixed descriptor: " + key);
            };
        }
        return descriptor;
    }

    @Mixin(value = ClientPortalRenderer.class, remap = false)
    public abstract static class Renderer implements VanillaPipelinePreparationTap.RendererAccess {
        @Accessor("portals")
        public abstract Int2ObjectOpenHashMap<?> wormholesTest$portals();

        @Override
        public BankAccess bank() {
            return VanillaPipelinePreparationTap.assignedBank();
        }

        @Override
        public int portalCount() {
            return wormholesTest$portals().size();
        }
    }

    @Mixin(targets = "art.arcane.wormholes.modded.client.render.PortalPipelines", remap = false)
    public abstract static class Lifecycle {
        @Unique
        private Counts wormholesTest$before;
        @Unique
        private boolean wormholesTest$wasPrepared;
        @Unique
        private long wormholesTest$prepareStarted;

        @Inject(method = "prepare()V", at = @At("HEAD"), require = 1, allow = 1)
        private void wormholesTest$begin(CallbackInfo callback) {
            wormholesTest$before = VanillaPipelinePreparationTap.counts();
            wormholesTest$wasPrepared = ((BankAccess) (Object) this).prepared();
            wormholesTest$prepareStarted = System.nanoTime();
        }

        @Inject(method = "prepare()V", at = @At("RETURN"), require = 1)
        private void wormholesTest$prepared(CallbackInfo callback) {
            if (!wormholesTest$wasPrepared) {
                long finished = System.nanoTime();
                VanillaPipelinePreparationTap.prepared((BankAccess) (Object) this, wormholesTest$before,
                    wormholesTest$prepareStarted, finished);
            }
        }
    }

    @Mixin(value = ClientPortalRenderer.class, remap = false)
    public abstract static class ResourceCompletion {
        @WrapOperation(method = {"resourcesReady()V", "closePipelines()V"},
            at = @At(value = "FIELD", target = "Lart/arcane/wormholes/modded/client/render/ClientPortalRenderer;pipelines:Lart/arcane/wormholes/modded/client/render/PortalPipelines;",
                opcode = Opcodes.PUTFIELD), require = 2, allow = 2)
        private void wormholesTest$bankAssigned(ClientPortalRenderer renderer, @Coerce BankAccess bank, Operation<Void> original) {
            original.call(renderer, bank);
            VanillaPipelinePreparationTap.bankAssigned(bank);
        }

        @Inject(method = "resourcesReady()V", at = @At("RETURN"), require = 1, allow = 1)
        private void wormholesTest$ready(CallbackInfo callback) {
            VanillaPipelinePreparationTap.resourcesReady(((VanillaPipelinePreparationTap.RendererAccess) this).bank());
        }
    }
}
