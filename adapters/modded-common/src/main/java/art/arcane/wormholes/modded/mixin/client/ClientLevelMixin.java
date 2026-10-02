package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ProjectionOverlay;
import art.arcane.wormholes.modded.client.ClientMeshEntities;
import art.arcane.wormholes.modded.client.WormholesClient;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import java.util.function.Consumer;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @ModifyArg(method = "tickEntities", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/level/entity/EntityTickList;forEach(Ljava/util/function/Consumer;)V"), index = 0)
    private Consumer<Entity> wormholesOwnedEntityTicks(Consumer<Entity> ticker) {
        return ClientMeshEntities.worldEntityTick(ticker);
    }

    @Inject(method = "tickPassenger", at = @At("HEAD"), cancellable = true)
    private void wormholesNativePassengerTick(Entity vehicle, Entity passenger, CallbackInfo callback) {
        if (ClientMeshEntities.hiddenFromWorld(passenger)) {
            callback.cancel();
        }
    }

    @Inject(method = "tickNonPassenger", at = @At("HEAD"), cancellable = true)
    private void wormholesNativeVisualTick(Entity entity, CallbackInfo callback) {
        if ((entity instanceof ItemEntity || entity instanceof Display || entity instanceof LivingEntity)
            && ClientMeshEntities.hiddenFromWorld(entity)) {
            callback.cancel();
        }
    }

    @ModifyVariable(method = "setServerVerifiedBlockState", at = @At("HEAD"), argsOnly = true)
    private BlockState wormholesProjectedState(BlockState state, BlockPos position) {
        WormholesClient.blockChanged(this, position);
        return ProjectionOverlay.intercept(this, position, state);
    }
}
