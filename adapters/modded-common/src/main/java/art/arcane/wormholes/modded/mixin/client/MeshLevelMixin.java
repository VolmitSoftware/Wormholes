package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ClientMeshEntities;
import art.arcane.wormholes.modded.client.ProjectedEntityGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import java.util.function.Predicate;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Level.class)
public abstract class MeshLevelMixin {
    @ModifyVariable(method = "getEntities(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;)Ljava/util/List;",
        at = @At("HEAD"), argsOnly = true)
    private Predicate<? super Entity> wormholesWorldEntities(Predicate<? super Entity> selector, Entity source, AABB bounds, Predicate<? super Entity> originalSelector) {
        return (Object) this instanceof ClientLevel
            ? ProjectedEntityGuard.localTargets(source, ClientMeshEntities.worldEntityPredicate(source, selector)) : selector;
    }

    @Inject(method = "getBlockState", at = @At("HEAD"), cancellable = true)
    private void wormholesMeshBlock(BlockPos position, CallbackInfoReturnable<BlockState> callback) {
        ClientMeshEntities scene = ClientMeshEntities.active(this);
        if (scene != null) {
            callback.setReturnValue(scene.blockState(position));
        }
    }

    @Inject(method = "getBlockEntity", at = @At("HEAD"), cancellable = true)
    private void wormholesMeshBlockEntity(BlockPos position, CallbackInfoReturnable<BlockEntity> callback) {
        ClientMeshEntities scene = ClientMeshEntities.active(this);
        if (scene != null) {
            callback.setReturnValue(scene.blockEntity(position));
        }
    }

    @Inject(method = "getFluidState", at = @At("HEAD"), cancellable = true)
    private void wormholesMeshFluid(BlockPos position, CallbackInfoReturnable<FluidState> callback) {
        ClientMeshEntities scene = ClientMeshEntities.active(this);
        if (scene != null) {
            callback.setReturnValue(scene.blockState(position).getFluidState());
        }
    }
}
