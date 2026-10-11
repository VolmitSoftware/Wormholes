package art.arcane.wormholes.modded.client;

import net.minecraft.core.Holder;
import net.minecraft.world.clock.WorldClock;

public interface ClientClockRate {
    float wormholes$rate(Holder<WorldClock> clock);
}
