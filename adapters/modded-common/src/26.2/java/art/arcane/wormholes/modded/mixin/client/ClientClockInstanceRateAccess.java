package art.arcane.wormholes.modded.mixin.client;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(targets = "net.minecraft.client.ClientClockManager$ClockInstance")
public interface ClientClockInstanceRateAccess {
    @Accessor("rate")
    float wormholes$rate();
}
