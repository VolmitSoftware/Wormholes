package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.MinecraftDoorService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerLevel.class)
public abstract class PocketSpawnMixin {
    @Inject(method = "addFreshEntity", at = @At("HEAD"), cancellable = true)
    private void wormholesPocketSpawn(Entity entity, CallbackInfoReturnable<Boolean> callback) {
        ServerLevel level = (ServerLevel) (Object) this;
        MinecraftDoorService doors = MinecraftDoorService.forServer(level.getServer());
        if (doors != null && doors.rules().denySpawn(level, entity)) {
            callback.setReturnValue(false);
        }
    }
}
