package art.arcane.wormholes.modded.mixin;

import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Set;

@Mixin(targets = "net.minecraft.server.level.ChunkMap$TrackedEntity")
public interface RemoteTrackedEntityAccess {
    @Accessor("entity")
    Entity wormholesTrackedEntity();

    @Accessor("seenBy")
    Set<ServerPlayerConnection> wormholesSeenBy();

    @Accessor("serverEntity")
    ServerEntity wormholesServerEntity();

    @Invoker("getEffectiveRange")
    int wormholesEffectiveRange();

    @Invoker("broadcastRemoved")
    void wormholesBroadcastRemoved();
}
