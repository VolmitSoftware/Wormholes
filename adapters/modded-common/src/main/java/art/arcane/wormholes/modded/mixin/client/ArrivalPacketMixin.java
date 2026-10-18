package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ClientArrivalPreparation;
import art.arcane.wormholes.modded.client.ClientRenderAccess;
import art.arcane.wormholes.modded.client.WormholesClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.LevelLoadTracker;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ArrivalPacketMixin {
    @Unique private static final String MAIN_THREAD =
        "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V";

    @Shadow private LevelLoadTracker levelLoadTracker;
    @Unique private boolean wormholes$arrivalAdopted;

    @Inject(method = "handleRespawn", at = @At(value = "INVOKE", target = MAIN_THREAD, shift = At.Shift.AFTER))
    private void wormholes$arrivalRespawn(ClientboundRespawnPacket packet, CallbackInfo callback) {
        WormholesClient client = WormholesClient.instance();
        if (client != null) {
            client.arrivals().respawn(packet);
        }
    }

    @Inject(method = "handleMovePlayer", at = @At(value = "INVOKE", target = MAIN_THREAD, shift = At.Shift.AFTER))
    private void wormholes$arrivalPosition(ClientboundPlayerPositionPacket packet, CallbackInfo callback) {
        WormholesClient client = WormholesClient.instance();
        wormholes$arrivalAdopted = client != null && client.arrivals().position(packet);
    }

    @Inject(method = "handleMovePlayer", at = @At("RETURN"))
    private void wormholes$arrivalPositionApplied(ClientboundPlayerPositionPacket packet, CallbackInfo callback) {
        if (wormholes$arrivalAdopted) {
            wormholes$arrivalAdopted = false;
            ClientRenderAccess.finishLevelLoading(Minecraft.getInstance(), levelLoadTracker);
        }
    }

    @WrapOperation(method = "handleRespawn", at = @At(value = "NEW", target = "net/minecraft/client/multiplayer/ClientLevel"))
    private ClientLevel wormholes$arrivalLevel(ClientPacketListener connection, ClientLevel.ClientLevelData data,
                                               ResourceKey<Level> dimension, Holder<DimensionType> type, int viewDistance,
                                               int simulationDistance, LevelExtractor extractor, boolean debug, long seed,
                                               int seaLevel, Operation<ClientLevel> original) {
        WormholesClient client = WormholesClient.instance();
        if (client != null) {
            ClientArrivalPreparation arrivals = client.arrivals();
            ClientLevel prepared = arrivals.respawnLevel();
            if (prepared != null) {
                ((PreparedPacketAccess) connection).wormholes$data(prepared.getLevelData());
                return prepared;
            }
        }
        return original.call(connection, data, dimension, type, viewDistance, simulationDistance, extractor, debug, seed, seaLevel);
    }
}
