package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ProjectionOverlay;
import art.arcane.wormholes.modded.client.ClientMeshEntities;
import art.arcane.wormholes.modded.client.ProjectedEntityGuard;
import art.arcane.wormholes.modded.client.WormholesClient;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import java.util.function.Consumer;
import java.util.List;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientLevel.class)
public abstract class ClientLevelMixin {
    @ModifyReturnValue(method = "getPushableEntities", at = @At("RETURN"))
    private List<Entity> wormholesVisualPushTargets(List<Entity> entities, Entity source, AABB bounds) {
        if (ProjectedEntityGuard.projected(source)) {
            return List.of();
        }
        return ClientMeshEntities.worldPushableEntities(source, entities);
    }

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

    @Inject(method = "setBlocksDirty", at = @At("RETURN"))
    private void wormholesLocalBlocks(BlockPos position, BlockState previous, BlockState next, CallbackInfo callback) {
        WormholesClient.blockChanged(this, position);
    }

    @ModifyVariable(method = "setServerVerifiedBlockState", at = @At("HEAD"), argsOnly = true)
    private BlockState wormholesProjectedState(BlockState state, BlockPos position) {
        WormholesClient.blockChanged(this, position);
        return ProjectionOverlay.intercept(this, position, state);
    }
}
