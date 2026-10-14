package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.NativeSodiumTerrainProbe;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.gl.device.MultiDrawBatch;
import net.caffeinemc.mods.sodium.client.gl.tessellation.GlTessellation;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer", remap = false)
public abstract class SodiumPortalTerrainTap {
    @Unique
    private NativeSodiumTerrainProbe.Frame wormholesTest$frame;

    @Inject(method = "render", at = @At("HEAD"))
    private void wormholesTest$begin(CallbackInfo callback, @Local(argsOnly = true) ChunkRenderMatrices matrices,
                                     @Local(argsOnly = true) ChunkRenderListIterable lists, @Local(argsOnly = true) TerrainRenderPass pass) {
        wormholesTest$frame = NativeSodiumTerrainProbe.begin(matrices, lists, pass);
    }

    @WrapOperation(method = "render", at = @At(value = "INVOKE", target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/DefaultChunkRenderer;executeDrawBatch(Lnet/caffeinemc/mods/sodium/client/gl/device/CommandList;Lnet/caffeinemc/mods/sodium/client/gl/tessellation/GlTessellation;Lnet/caffeinemc/mods/sodium/client/gl/device/MultiDrawBatch;)V"))
    private void wormholesTest$draw(CommandList commands, GlTessellation tessellation, MultiDrawBatch batch, Operation<Void> original) {
        int query = NativeSodiumTerrainProbe.beginDraw(wormholesTest$frame);
        try {
            original.call(commands, tessellation, batch);
        } finally {
            NativeSodiumTerrainProbe.endDraw(wormholesTest$frame, query, batch.size);
        }
    }

    @Inject(method = "render", at = @At("RETURN"))
    private void wormholesTest$end(CallbackInfo callback) {
        NativeSodiumTerrainProbe.end(wormholesTest$frame);
        wormholesTest$frame = null;
    }
}
