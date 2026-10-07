package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.seamless.StraddleCollision;
import art.arcane.wormholes.modded.seamless.StraddleHolder;
import art.arcane.wormholes.modded.seamless.StraddleTracker;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Entity.class)
public abstract class StraddleCollisionMixin implements StraddleHolder {
    @Unique
    private StraddleTracker.Straddle wormholes$straddle;

    @Override
    public StraddleTracker.Straddle wormholesStraddle() {
        return wormholes$straddle;
    }

    @Override
    public void wormholesStraddle(StraddleTracker.Straddle straddle) {
        wormholes$straddle = straddle;
    }

    @ModifyReturnValue(method = "collide", at = @At("RETURN"))
    private Vec3 wormholesStraddleCollide(Vec3 thisSide, @Local(argsOnly = true) Vec3 movement) {
        StraddleTracker.Straddle straddle = wormholes$straddle;
        return straddle == null ? thisSide : StraddleCollision.otherSide((Entity) (Object) this, straddle, movement, thisSide);
    }

    @ModifyExpressionValue(method = "collectCollidersIgnoringWorldBorder(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/level/Level;Ljava/util/List;Lnet/minecraft/world/phys/AABB;)Ljava/util/List;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockCollisions(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/AABB;)Ljava/lang/Iterable;"))
    private static Iterable<VoxelShape> wormholesStraddleShapes(Iterable<VoxelShape> shapes, @Local(argsOnly = true) Entity source) {
        StraddleTracker.Straddle straddle = source instanceof StraddleHolder holder ? holder.wormholesStraddle() : null;
        return straddle == null ? shapes : StraddleCollision.thisSide(shapes, straddle);
    }
}
