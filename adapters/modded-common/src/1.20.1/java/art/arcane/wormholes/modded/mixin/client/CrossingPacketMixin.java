package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ClientEntityCrossing;
import art.arcane.wormholes.modded.client.CrossingEntity;
import art.arcane.wormholes.modded.client.WormholesClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class CrossingPacketMixin {
    @Unique private static final String MAIN_THREAD =
        "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V";

    @Inject(method = "handleRespawn", at = @At(value = "INVOKE", target = MAIN_THREAD, shift = At.Shift.AFTER))
    private void wormholes$respawn(CallbackInfo callback) {
        WormholesClient client = WormholesClient.instance();
        if (client != null) {
            client.seamlessTravel().clear();
        }
    }

    @Inject(method = "handleMovePlayer", at = @At(value = "INVOKE", target = MAIN_THREAD, shift = At.Shift.AFTER))
    private void wormholes$serverPosition(CallbackInfo callback) {
        WormholesClient client = WormholesClient.instance();
        if (client != null) {
            client.seamlessTravel().serverPosition();
        }
    }

    @WrapOperation(method = {"handleMoveEntity", "handleTeleportEntity"},
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;lerpTo(DDDFFIZ)V"))
    private void wormholes$sourceFrame(Entity entity, double x, double y, double z, float yRot, float xRot, int steps, boolean teleport,
                                       Operation<Void> original) {
        ClientEntityCrossing crossing = entity instanceof CrossingEntity holder ? holder.wormholes$crossing() : null;
        if (crossing == null) {
            original.call(entity, x, y, z, yRot, xRot, steps, teleport);
            return;
        }
        if (crossing.absorb()) {
            return;
        }
        if (crossing.crossed()) {
            original.call(entity, x, y, z, yRot, xRot, steps, teleport);
            return;
        }
        crossing.route(entity, new Vec3(x, y, z), yRot, xRot, yRot != entity.getYRot() || xRot != entity.getXRot(), steps, teleport);
    }
}
