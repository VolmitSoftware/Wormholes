package art.arcane.wormholes.modded;

import com.mojang.authlib.GameProfile;
import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;

import java.lang.reflect.Field;
import java.util.UUID;

public final class MinecraftGameTestAccess {
    private MinecraftGameTestAccess() {
    }

    public static ServerPlayer mockServerPlayer(GameTestHelper helper, GameType type) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = new ServerPlayer(level.getServer(), level, new GameProfile(UUID.randomUUID(), "test-mock-player"));
        // Give the connection a live channel: Forge reads channel attributes while placing a player.
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        connection.setProtocol(ConnectionProtocol.PLAY);
        MinecraftGameTestPlayer.prepare(connection);
        level.getServer().getPlayerList().placeNewPlayer(connection, player);
        player.setGameMode(type);
        return player;
    }

    public static void acknowledge(ServerPlayer player, ClientboundPlayerPositionPacket position) {
        player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(position.getId()));
    }

    public static void punch(ServerPlayer player) {
        player.connection.handleAnimate(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
    }

    public static boolean swing(Object packet, int entityId, InteractionHand hand) {
        int action = hand == InteractionHand.MAIN_HAND ? ClientboundAnimatePacket.SWING_MAIN_HAND : ClientboundAnimatePacket.SWING_OFF_HAND;
        return packet instanceof ClientboundAnimatePacket swing && swing.getId() == entityId && swing.getAction() == action;
    }

    public static IntList removed(ClientboundRemoveEntitiesPacket packet) {
        return packet.getEntityIds();
    }

    public static ClientboundLevelChunkPacketData chunkData(ClientboundLevelChunkWithLightPacket packet) {
        return packet.getChunkData();
    }

    public static void blockEntityTags(ClientboundLevelChunkWithLightPacket packet, ClientboundLevelChunkPacketData.BlockEntityTagOutput output) {
        chunkData(packet).getBlockEntitiesTagsConsumer(chunkX(packet), chunkZ(packet)).accept(output);
    }

    public static int chunkX(ClientboundLevelChunkWithLightPacket packet) {
        return packet.getX();
    }

    public static int chunkZ(ClientboundLevelChunkWithLightPacket packet) {
        return packet.getZ();
    }

    public static ParticleOptions particle(ClientboundLevelParticlesPacket packet) {
        return packet.getParticle();
    }

    public static int count(ClientboundLevelParticlesPacket packet) {
        return packet.getCount();
    }

    public static double x(ClientboundLevelParticlesPacket packet) {
        return packet.getX();
    }

    public static double y(ClientboundLevelParticlesPacket packet) {
        return packet.getY();
    }

    public static double z(ClientboundLevelParticlesPacket packet) {
        return packet.getZ();
    }

    public static float xDist(ClientboundLevelParticlesPacket packet) {
        return packet.getXDist();
    }

    public static boolean invulnerable(Entity entity) {
        return entity.isInvulnerable();
    }

    public static void clearDamageCooldown(ServerPlayer player) {
        player.invulnerableTime = 0;
        try {
            Field spawnInvulnerable = ServerPlayer.class.getDeclaredField("spawnInvulnerableTime");
            spawnInvulnerable.setAccessible(true);
            spawnInvulnerable.setInt(player, 0);
        } catch (ReflectiveOperationException error) {
            throw new IllegalStateException("Could not clear the player's spawn invulnerability", error);
        }
    }

    public static CompoundTag signTextTag(SignBlockEntity sign, HolderLookup.Provider registries, String message) {
        SignText text = sign.getFrontText().setMessage(0, Component.literal(message));
        CompoundTag tag = sign.saveWithoutMetadata();
        tag.put("front_text", SignText.DIRECT_CODEC.encodeStart(NbtOps.INSTANCE, text).getOrThrow(false, error -> { }));
        return tag;
    }

    public static String signLine(SignBlockEntity sign) {
        return sign.getFrontText().getMessages(false)[0].getString();
    }
}
