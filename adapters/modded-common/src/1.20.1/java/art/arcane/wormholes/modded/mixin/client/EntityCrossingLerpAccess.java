package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(LivingEntity.class)
public interface EntityCrossingLerpAccess {
    @Accessor("lerpSteps")
    int wormholes$lerpSteps();

    @Accessor("lerpSteps")
    void wormholes$lerpSteps(int steps);

    @Accessor("lerpX")
    double wormholes$lerpX();

    @Accessor("lerpX")
    void wormholes$lerpX(double x);

    @Accessor("lerpY")
    double wormholes$lerpY();

    @Accessor("lerpY")
    void wormholes$lerpY(double y);

    @Accessor("lerpZ")
    double wormholes$lerpZ();

    @Accessor("lerpZ")
    void wormholes$lerpZ(double z);
}
