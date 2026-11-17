package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ClientArrivalPreparation;
import art.arcane.wormholes.modded.client.WormholesClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ReceivingLevelScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.Holder;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.Supplier;

@Mixin(ClientPacketListener.class)
public abstract class ArrivalPacketMixin {
    @Unique private static final String MAIN_THREAD =
        "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/util/thread/BlockableEventLoop;)V";
    @Unique private static final String SET_LEVEL = "Lnet/minecraft/client/Minecraft;setLevel(Lnet/minecraft/client/multiplayer/ClientLevel;)V";
    @Unique private static final String SET_SCREEN = "Lnet/minecraft/client/Minecraft;setScreen(Lnet/minecraft/client/gui/screens/Screen;)V";

    @Unique private boolean wormholes$adoptingArrival;

    @Inject(method = "handleRespawn", at = @At(value = "INVOKE", target = MAIN_THREAD, shift = At.Shift.AFTER))
    private void wormholes$arrivalRespawn(ClientboundRespawnPacket packet, CallbackInfo callback) {
        wormholes$adoptingArrival = false;
        WormholesClient client = WormholesClient.instance();
        if (client != null) {
            client.arrivals().respawn(packet);
        }
    }

    @Inject(method = "handleMovePlayer", at = @At(value = "INVOKE", target = MAIN_THREAD, shift = At.Shift.AFTER))
    private void wormholes$arrivalPosition(ClientboundPlayerPositionPacket packet, CallbackInfo callback) {
        WormholesClient client = WormholesClient.instance();
        if (client != null) {
            client.arrivals().position(packet);
        }
    }

    @WrapOperation(method = "handleRespawn", at = @At(value = "NEW",
        target = "(Lnet/minecraft/client/multiplayer/ClientPacketListener;Lnet/minecraft/client/multiplayer/ClientLevel$ClientLevelData;Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/core/Holder;IILjava/util/function/Supplier;Lnet/minecraft/client/renderer/LevelRenderer;ZJ)Lnet/minecraft/client/multiplayer/ClientLevel;"))
    private ClientLevel wormholes$arrivalLevel(ClientPacketListener connection, ClientLevel.ClientLevelData data, ResourceKey<Level> dimension,
                                               Holder<DimensionType> type, int viewDistance, int simulationDistance,
                                               Supplier<ProfilerFiller> profiler, LevelRenderer renderer, boolean debug, long seed,
                                               Operation<ClientLevel> original) {
        WormholesClient client = WormholesClient.instance();
        if (client != null) {
            ClientArrivalPreparation arrivals = client.arrivals();
            ClientLevel prepared = arrivals.respawnLevel();
            if (prepared != null) {
                ((CrossingPacketAccess) connection).wormholes$crossingData(prepared.getLevelData());
                wormholes$adoptingArrival = true;
                return prepared;
            }
        }
        return original.call(connection, data, dimension, type, viewDistance, simulationDistance, profiler, renderer, debug, seed);
    }

    @WrapOperation(method = "handleRespawn", at = @At(value = "INVOKE", target = SET_LEVEL))
    private void wormholes$arrivalInstall(Minecraft minecraft, ClientLevel level, Operation<Void> original) {
        if (!wormholes$adoptingArrival) {
            original.call(minecraft, level);
            return;
        }
        minecraft.level = level;
        ((CrossingMinecraftAccess) minecraft).wormholes$crossingEngines(level);
    }

    @WrapOperation(method = "handleRespawn", at = @At(value = "INVOKE", target = SET_SCREEN))
    private void wormholes$arrivalScreen(Minecraft minecraft, Screen screen, Operation<Void> original) {
        if (!wormholes$adoptingArrival || !(screen instanceof ReceivingLevelScreen)) {
            original.call(minecraft, screen);
        }
    }
}
