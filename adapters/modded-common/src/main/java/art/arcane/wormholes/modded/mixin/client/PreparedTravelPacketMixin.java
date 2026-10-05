package art.arcane.wormholes.modded.mixin.client;

import art.arcane.wormholes.modded.client.ClientPreparedTravel;
import art.arcane.wormholes.modded.client.WormholesClient;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.ClientRecipeBook;
import net.minecraft.client.player.ItemActivation;
import net.minecraft.stats.StatsCounter;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import org.objectweb.asm.Opcodes;
import net.minecraft.world.entity.player.Input;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.client.renderer.extract.LevelExtractor;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class PreparedTravelPacketMixin {
    @WrapMethod(method = "handleRespawn")
    private void wormholes$respawn(ClientboundRespawnPacket packet, Operation<Void> original) {
        WormholesClient client = WormholesClient.instance();
        if (client == null || !Minecraft.getInstance().isSameThread()) {
            original.call(packet);
            return;
        }
        client.preparedTravel().beginRespawn(packet.commonPlayerSpawnInfo().dimension(),
            packet.shouldKeep((byte) 1) && packet.shouldKeep((byte) 2));
        try {
            original.call(packet);
        } finally {
            client.preparedTravel().endRespawn();
        }
    }

    @WrapOperation(method = "handleRespawn", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;level()Lnet/minecraft/world/level/Level;"))
    private Level wormholes$sourceDimension(LocalPlayer player, Operation<Level> original) {
        WormholesClient client = WormholesClient.instance();
        Level source = client == null ? null : client.preparedTravel().respawnSourceLevel();
        return source == null ? original.call(player) : source;
    }

    @WrapOperation(method = "handleRespawn", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;createPlayer(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/stats/StatsCounter;Lnet/minecraft/client/ClientRecipeBook;Lnet/minecraft/world/entity/player/Input;ZLnet/minecraft/client/player/ItemActivation;)Lnet/minecraft/client/player/LocalPlayer;"))
    private LocalPlayer wormholes$keepPlayer(MultiPlayerGameMode mode, ClientLevel level, StatsCounter stats,
                                            ClientRecipeBook recipes, Input input, boolean sprinting,
                                            ItemActivation activation, Operation<LocalPlayer> original) {
        return seamless() ? Minecraft.getInstance().player : original.call(mode, level, stats, recipes, input, sprinting, activation);
    }

    @WrapOperation(method = "handleRespawn", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;setCameraEntity(Lnet/minecraft/world/entity/Entity;)V"))
    private void wormholes$keepCamera(Minecraft minecraft, Entity entity, Operation<Void> original) {
        if (!seamless() || entity != null) {
            original.call(minecraft, entity);
        }
    }

    @WrapOperation(method = "handleRespawn", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/Minecraft;setLevel(Lnet/minecraft/client/multiplayer/ClientLevel;)V"))
    private void wormholes$attachedLevel(Minecraft minecraft, ClientLevel level, Operation<Void> original) {
        WormholesClient client = WormholesClient.instance();
        if (client == null || !client.preparedTravel().attachRespawnLevel(level)) {
            original.call(minecraft, level);
        }
    }

    @WrapOperation(method = "handleRespawn", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;addEntity(Lnet/minecraft/world/entity/Entity;)V"))
    private void wormholes$existingPlayer(ClientLevel level, Entity player, Operation<Void> original) {
        if (!seamless() || level.getEntity(player.getId()) != player) {
            original.call(level, player);
        }
    }

    @WrapOperation(method = "handleRespawn", at = @At(value = "FIELD", target = "Lnet/minecraft/client/player/LocalPlayer;input:Lnet/minecraft/client/player/ClientInput;", opcode = Opcodes.PUTFIELD))
    private void wormholes$keepInput(LocalPlayer player, ClientInput input, Operation<Void> original) {
        if (!seamless()) {
            original.call(player, input);
        }
    }

    @WrapOperation(method = "handleRespawn", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/ai/attributes/AttributeMap;assignAllValues(Lnet/minecraft/world/entity/ai/attributes/AttributeMap;)V"))
    private void wormholes$preserveAttributes(AttributeMap attributes, AttributeMap source, Operation<Void> original) {
        if (attributes != source) {
            original.call(attributes, source);
        }
    }

    private static boolean seamless() {
        WormholesClient client = WormholesClient.instance();
        return client != null && client.preparedTravel().seamlessRespawn();
    }

    @WrapOperation(method = "handleRespawn", at = @At(value = "NEW", target = "net/minecraft/client/multiplayer/ClientLevel"))
    private ClientLevel wormholes$preparedLevel(ClientPacketListener connection, ClientLevel.ClientLevelData data,
                                              ResourceKey<Level> dimension, Holder<DimensionType> type, int distance,
                                              int simulation, LevelExtractor extractor, boolean debug, long seed,
                                              int seaLevel, Operation<ClientLevel> original) {
        WormholesClient client = WormholesClient.instance();
        ClientLevel prepared = client == null ? null : client.preparedTravel().adopt(
            new ClientPreparedTravel.Construction(data, dimension, type, extractor, debug, seed, seaLevel, distance, simulation));
        return prepared == null ? original.call(connection, data, dimension, type, distance, simulation, extractor, debug, seed, seaLevel) : prepared;
    }

    @WrapOperation(method = "startWaitingForNewLevel", at = @At(value = "INVOKE",
        target = "Lnet/minecraft/client/Minecraft;setScreenAndShow(Lnet/minecraft/client/gui/screens/Screen;)V"))
    private void wormholes$preparedLoading(Minecraft minecraft, Screen screen, Operation<Void> original) {
        WormholesClient client = WormholesClient.instance();
        if (client == null || !client.preparedTravel().deferLoadingScreen(screen)) {
            original.call(minecraft, screen);
        }
    }

    @Inject(method = "handleMovePlayer", at = @At("RETURN"))
    private void wormholes$confirmedPosition(CallbackInfo callback) {
        WormholesClient client = WormholesClient.instance();
        if (client != null) {
            client.preparedTravel().serverPosition();
        }
    }

    @Inject(method = "handleMovePlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V", shift = At.Shift.AFTER))
    private void wormholes$positionCheckpoint(CallbackInfo callback) {
        WormholesClient client = WormholesClient.instance();
        if (client != null) {
            client.preparedTravel().beforeServerPosition();
        }
    }

}
