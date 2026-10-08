package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.sodium.SodiumLayers;
import art.arcane.wormholes.modded.client.render.sodium.SodiumPortalUniforms;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.UniformBufferManager;
import net.minecraft.client.renderer.DynamicGpuDataStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;

@Pseudo
@Mixin(value = UniformBufferManager.class, remap = false)
public abstract class SodiumPortalUniformsMixin implements SodiumPortalUniforms {
    @Shadow private GpuBufferSlice uniformData;
    @Shadow private boolean hasUpdatedThisFrame;

    @Override
    public GpuBufferSlice wormholes$data() {
        return uniformData;
    }

    @Override
    public void wormholes$data(GpuBufferSlice data) {
        uniformData = data;
    }

    @Override
    public boolean wormholes$written() {
        return hasUpdatedThisFrame;
    }

    @Override
    public void wormholes$written(boolean written) {
        hasUpdatedThisFrame = written;
    }

    @WrapOperation(method = "update", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/DynamicGpuDataStorage;writeData(Lnet/minecraft/client/renderer/DynamicGpuDataStorage$DynamicGpuData;)Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"))
    private GpuBufferSlice wormholes$clipPlane(DynamicGpuDataStorage<DynamicGpuDataStorage.DynamicGpuData> storage,
                                              DynamicGpuDataStorage.DynamicGpuData globals, Operation<GpuBufferSlice> original,
                                              @Local(argsOnly = true) ChunkRenderMatrices matrices) {
        return original.call(storage, SodiumLayers.clipped(globals, matrices));
    }
}
