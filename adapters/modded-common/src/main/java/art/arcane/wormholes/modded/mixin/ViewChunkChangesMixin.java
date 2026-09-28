package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftNetworkService;
import art.arcane.wormholes.modded.MinecraftNexus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LightLayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ServerChunkCache.class)
public abstract class ViewChunkChangesMixin {
    @Shadow @Final ServerLevel level;

    @Inject(method = "blockChanged", at = @At("TAIL"))
    private void wormholesViewBlock(BlockPos position, CallbackInfo callback) {
        MinecraftNexus nexus = MinecraftNexus.forServer(level.getServer());
        if (nexus != null && level.getServer().isSameThread()) {
            nexus.blockChanged(level, position);
        }
        MinecraftNetworkService network = MinecraftNetworkService.forServer(level.getServer());
        if (network != null) {
            network.worldChanged(level, position.getX() >> 4, position.getZ() >> 4);
            network.viewServer().blockChanged(level, position);
        }
    }

    @Inject(method = "onLightUpdate", at = @At("TAIL"))
    private void wormholesViewLight(LightLayer layer, SectionPos section, CallbackInfo callback) {
        MinecraftNetworkService network = MinecraftNetworkService.forServer(level.getServer());
        if (network != null && !level.getServer().isStopped()) {
            level.getServer().execute(() -> {
                if (!level.getServer().isStopped() && MinecraftNetworkService.forServer(level.getServer()) == network) {
                    network.worldChanged(level, section.x(), section.z());
                    network.viewServer().lightChanged(level, layer, section);
                }
            });
        }
    }
}
