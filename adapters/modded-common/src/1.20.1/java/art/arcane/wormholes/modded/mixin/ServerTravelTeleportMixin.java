package art.arcane.wormholes.modded.mixin;

import art.arcane.wormholes.modded.WormholesModRuntime;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.RelativeMovement;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Set;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerTravelTeleportMixin {
    @Shadow public ServerPlayer player;

    @WrapMethod(method = "teleport(DDDFFLjava/util/Set;)V")
    private void wormholesRecordTeleport(double x, double y, double z, float yRot, float xRot, Set<RelativeMovement> relatives,
                                         Operation<Void> original) {
        ServerLevel previousLevel = player.serverLevel();
        Vec3 previousPosition = player.position();
        original.call(x, y, z, yRot, xRot, relatives);
        WormholesModRuntime runtime = WormholesModRuntime.forServer(player.server);
        if (runtime != null) {
            runtime.authoritativeTeleport(player, previousLevel, previousPosition);
        }
    }
}
