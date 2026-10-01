package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.world.entity.WalkAnimationState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(WalkAnimationState.class)
public interface WalkAnimationAccessor {
    @Accessor("speedOld")
    float wormholesSpeedOld();

    @Accessor("speedOld")
    void wormholesSpeedOld(float value);

    @Accessor("speed")
    float wormholesSpeed();

    @Accessor("speed")
    void wormholesSpeed(float value);

    @Accessor("position")
    float wormholesPosition();

    @Accessor("position")
    void wormholesPosition(float value);

    @Accessor("positionScale")
    float wormholesPositionScale();

    @Accessor("positionScale")
    void wormholesPositionScale(float value);
}
