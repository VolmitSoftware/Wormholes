package art.arcane.wormholes.modded.mixin.client;

import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(ClientLevel.ClientLevelData.class)
public interface PreparedLevelDataAccess {
    @Accessor("isFlat")
    boolean wormholes$flat();
}
