package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ClientMeshEntities;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import java.util.function.Predicate;

@Mixin(GameRenderer.class)
public abstract class MeshInteractionMixin {
    @ModifyArg(method = "pick(F)V", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/world/entity/projectile/ProjectileUtil;getEntityHitResult(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/AABB;Ljava/util/function/Predicate;D)Lnet/minecraft/world/phys/EntityHitResult;"),
        index = 4)
    private Predicate<Entity> wormholesInteractionTarget(Predicate<Entity> pickable) {
        return pickable.and(ClientMeshEntities::interactionTarget);
    }
}
