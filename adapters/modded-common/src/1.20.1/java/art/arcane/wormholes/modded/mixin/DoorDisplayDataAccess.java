package art.arcane.wormholes.modded.mixin;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.world.entity.Display;
import org.joml.Vector3f;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Display.class)
public interface DoorDisplayDataAccess {
    @Accessor("DATA_LEFT_ROTATION_ID")
    static EntityDataAccessor<Quaternionf> wormholesLeftRotation() {
        throw new AssertionError();
    }

    @Accessor("DATA_TRANSLATION_ID")
    static EntityDataAccessor<Vector3f> wormholesTranslation() {
        throw new AssertionError();
    }

    @Accessor("DATA_SCALE_ID")
    static EntityDataAccessor<Vector3f> wormholesScale() {
        throw new AssertionError();
    }

    @Accessor("DATA_INTERPOLATION_START_DELTA_TICKS_ID")
    static EntityDataAccessor<Integer> wormholesInterpolationStart() {
        throw new AssertionError();
    }
}
