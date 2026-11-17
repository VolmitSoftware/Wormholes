package art.arcane.wormholes.modded.mixin.client;

import me.jellysquid.mods.sodium.client.render.SodiumWorldRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Accessor;

@Pseudo
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public interface SodiumArrivalRendererAccess {
    @Accessor("world")
    void wormholes$arrivalLevel(ClientLevel level);
}
