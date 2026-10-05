package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.render.ClientSodiumTerrain;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientChunkCache.class)
public abstract class LocalMeshChunkMixin {
    @Shadow @Final private ClientLevel level;

    @Inject(method = "replaceBiomes", at = @At("RETURN"))
    private void wormholesLocalBiomes(int x, int z, FriendlyByteBuf data, CallbackInfo callback) {
        WormholesClient.localChunkChanged(level, x, z);
    }

    @WrapOperation(method = "onLightUpdate", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/renderer/extract/LevelExtractor;setSectionDirty(III)V"))
    private void wormholesLightGeometry(LevelExtractor extractor, int x, int y, int z, Operation<Void> original) {
        boolean vanillaUpdated = level == Minecraft.getInstance().level;
        if (vanillaUpdated) {
            original.call(extractor, x, y, z);
        }
        ClientSodiumTerrain.lightChanged(level, SectionPos.asLong(x, y, z), vanillaUpdated);
    }

    @Inject(method = "onLightUpdate", at = @At("RETURN"))
    private void wormholesLocalLight(LightLayer layer, SectionPos position, CallbackInfo callback) {
        WormholesClient.localLightChanged(level, position);
    }
}
