package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ProjectedEntityGuard;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySelector;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Predicate;

@Mixin(EntitySelector.class)
public abstract class ProjectedEntitySelectorMixin {
    @Shadow
    @Final
    @Mutable
    public static Predicate<Entity> CAN_BE_COLLIDED_WITH;

    @Shadow
    @Final
    @Mutable
    public static Predicate<Entity> CAN_BE_PICKED;

    @Inject(method = "<clinit>", at = @At("TAIL"))
    private static void wormholes$excludeProjected(CallbackInfo callback) {
        CAN_BE_COLLIDED_WITH = ProjectedEntityGuard.excluding(CAN_BE_COLLIDED_WITH);
        CAN_BE_PICKED = ProjectedEntityGuard.excluding(CAN_BE_PICKED);
    }

    @ModifyReturnValue(method = "pushableBy", at = @At("RETURN"))
    private static Predicate<Entity> wormholes$pushableBy(Predicate<Entity> pushable) {
        return ProjectedEntityGuard.excluding(pushable);
    }
}
