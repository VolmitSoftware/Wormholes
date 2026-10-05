package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.render.ClientSodiumTerrain;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.caffeinemc.mods.sodium.client.render.chunk.storage.SectionStorage;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkUpdateTypes;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.estimation.UploadResourceBudget;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.ChunkJobCollector;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = RenderSectionManager.class, remap = false)
public abstract class SodiumPreparedColumnsMixin {
    @Shadow @Final private ClientLevel level;

    @WrapOperation(method = "onSectionRemoved", at = @At(value = "INVOKE",
        target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/storage/SectionStorage;queueRemove(J)Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;"))
    private RenderSection wormholes$removeSectionOnce(SectionStorage storage, long position, Operation<RenderSection> original) {
        return storage.getConsistent(position) == null ? null : original.call(storage, position);
    }

    @Inject(method = "beforeSectionUpdates", at = @At("HEAD"))
    private void wormholes$reconcileColumns(CallbackInfo callback) {
        ClientSodiumTerrain.prepareColumns(level, (RenderSectionManager) (Object) this);
    }

    @Inject(method = "onChunkRemoved", at = @At("HEAD"), cancellable = true)
    private void wormholes$retainColumn(int x, int z, CallbackInfo callback) {
        if (ClientSodiumTerrain.retainColumn(level, (RenderSectionManager) (Object) this, x, z)) {
            callback.cancel();
        }
    }

    @Inject(method = "submitSectionTask(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/executor/ChunkJobCollector;Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSection;ILnet/caffeinemc/mods/sodium/client/render/chunk/compile/estimation/UploadResourceBudget;Z)V",
        at = @At("HEAD"), cancellable = true)
    private void wormholes$waitForNeighbors(ChunkJobCollector collector, RenderSection section, int update,
                                          UploadResourceBudget budget, boolean immediate, CallbackInfo callback) {
        if ((ChunkUpdateTypes.isInitialBuild(update) || ChunkUpdateTypes.isRebuild(update))
            && ClientSodiumTerrain.deferColumnBuild(level, (RenderSectionManager) (Object) this,
                section.getPosition().x(), section.getPosition().z())) {
            callback.cancel();
        }
    }
}
