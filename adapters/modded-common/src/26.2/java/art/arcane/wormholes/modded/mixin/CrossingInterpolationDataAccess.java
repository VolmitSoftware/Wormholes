package art.arcane.wormholes.modded.mixin;

import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "net.minecraft.world.entity.InterpolationHandler$InterpolationData")
public interface CrossingInterpolationDataAccess {
    @Invoker("addDelta")
    void wormholesAddDelta(Vec3 delta);
}
