package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(Minecraft.class)
public interface CrossingMinecraftAccess {
    @Invoker("updateLevelInEngines")
    void wormholes$crossingEngines(ClientLevel level);
}
