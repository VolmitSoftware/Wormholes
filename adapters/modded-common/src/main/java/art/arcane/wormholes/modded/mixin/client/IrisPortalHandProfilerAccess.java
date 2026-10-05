package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.util.profiling.Profiler;
import net.minecraft.util.profiling.ProfilerFiller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.concurrent.atomic.AtomicInteger;

@Mixin(Profiler.class)
public interface IrisPortalHandProfilerAccess {
    @Accessor("ACTIVE")
    static ThreadLocal<ProfilerFiller> wormholes$active() {
        throw new UnsupportedOperationException();
    }

    @Accessor("ACTIVE_COUNT")
    static AtomicInteger wormholes$count() {
        throw new UnsupportedOperationException();
    }
}
