package art.arcane.wormholes.modded.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Optional;

@Mixin(ServerPlayer.class)
public interface ServerPlayerRespawnInvoker {
    @Invoker("findRespawnAndUseSpawnBlock")
    static Optional<?> wormholes$findRespawn(ServerLevel level, ServerPlayer.RespawnConfig configuration, boolean consumeSpawnBlock) {
        throw new UnsupportedOperationException();
    }
}
