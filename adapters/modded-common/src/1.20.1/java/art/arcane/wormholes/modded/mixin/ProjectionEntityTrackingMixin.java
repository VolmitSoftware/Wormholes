package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftEntityTracker;
import art.arcane.wormholes.modded.MinecraftProjectionService;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public abstract class ProjectionEntityTrackingMixin implements MinecraftEntityTracker {
    @Shadow @Final private Entity entity;
    @Shadow public abstract void removePlayer(ServerPlayer player);
    @Shadow public abstract void updatePlayer(ServerPlayer player);

    @Override
    public void wormholesHide(ServerPlayer player) {
        removePlayer(player);
    }

    @Override
    public void wormholesShow(ServerPlayer player) {
        updatePlayer(player);
    }

    @Inject(method = "broadcast", at = @At("HEAD"))
    @SuppressWarnings("unchecked")
    private void wormholesProjectionEvent(Packet<?> packet, CallbackInfo callback) {
        MinecraftProjectionService projections = MinecraftProjectionService.forServer(entity.level().getServer());
        if (projections != null) {
            projections.entityEvent(entity, (Packet<? super ClientGamePacketListener>) packet);
        }
    }

    @Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true)
    private void wormholesProjectionVisibility(ServerPlayer player, CallbackInfo callback) {
        MinecraftProjectionService projections = MinecraftProjectionService.forServer(player.level().getServer());
        if (projections != null && projections.isEntityHidden(player.getUUID(), entity.getUUID())) {
            removePlayer(player);
            callback.cancel();
        }
    }
}
