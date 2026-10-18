package art.arcane.wormholes.modded.mixin.client;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public interface SodiumArrivalRendererAccess {
    @Accessor("level")
    void wormholes$arrivalLevel(ClientLevel level);
}
