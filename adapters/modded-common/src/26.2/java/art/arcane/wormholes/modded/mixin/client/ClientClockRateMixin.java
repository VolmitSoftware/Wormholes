package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ClientClockRate;
import net.minecraft.client.ClientClockManager;
import net.minecraft.core.Holder;
import net.minecraft.world.clock.WorldClock;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;

@Mixin(ClientClockManager.class)
public abstract class ClientClockRateMixin implements ClientClockRate {
    @Shadow @Final private Map<Holder<WorldClock>, Object> clocks;

    @Override
    public float wormholes$rate(Holder<WorldClock> clock) {
        Object instance = clocks.get(clock);
        return instance == null ? 1.0F : ((ClientClockInstanceRateAccess) instance).wormholes$rate();
    }
}
