package art.arcane.wormholes.clientgametest.mixin;

import art.arcane.wormholes.clientgametest.ServerStartedCrossingTap;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerStartedMoveTap {
    @Shadow
    public ServerPlayer player;

    @WrapMethod(method = "handleMovePlayer")
    private void wormholesTest$move(ServerboundMovePlayerPacket packet, Operation<Void> original) {
        if (!player.level().getServer().isSameThread() || !ServerStartedCrossingTap.recording(player)) {
            original.call(packet);
            return;
        }
        String level = player.level().dimension().identifier().toString();
        Vec3 before = player.position();
        original.call(packet);
        ServerStartedCrossingTap.moved(player, packet.hasPosition() ? new Vec3(packet.getX(0.0D), packet.getY(0.0D), packet.getZ(0.0D)) : null,
            level, before);
    }
}
