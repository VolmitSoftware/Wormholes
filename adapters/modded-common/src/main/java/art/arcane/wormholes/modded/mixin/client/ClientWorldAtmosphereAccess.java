package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.renderer.fog.environment.AtmosphericFogEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(AtmosphericFogEnvironment.class)
public interface ClientWorldAtmosphereAccess {
    @Accessor("rainFogMultiplier")
    float wormholes$rainFogMultiplier();

    @Accessor("rainFogMultiplier")
    void wormholes$rainFogMultiplier(float value);
}
