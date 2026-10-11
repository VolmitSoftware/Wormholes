package art.arcane.wormholes.modded;

import it.unimi.dsi.fastutil.ints.IntList;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundSwingPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;

public final class MinecraftGameTestAccess {
    private MinecraftGameTestAccess() {
    }

    public static void acknowledge(ServerPlayer player, ClientboundPlayerPositionPacket position) {
        player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(position.id()));
    }

    public static void punch(ServerPlayer player) {
        player.connection.handleAnimate(new ServerboundSwingPacket(InteractionHand.MAIN_HAND));
    }

    public static boolean swing(Object packet, int entityId, InteractionHand hand) {
        return packet instanceof ClientboundAnimatePacket swing && swing.getId() == entityId
            && swing.getAction() == (hand == InteractionHand.MAIN_HAND ? ClientboundAnimatePacket.SWING_MAIN_HAND : ClientboundAnimatePacket.SWING_OFF_HAND);
    }

    public static IntList removed(ClientboundRemoveEntitiesPacket packet) {
        return packet.getEntityIds();
    }

    public static ClientboundLevelChunkPacketData chunkData(ClientboundLevelChunkWithLightPacket packet) {
        return packet.getChunkData();
    }

    public static void blockEntityTags(ClientboundLevelChunkWithLightPacket packet, ClientboundLevelChunkPacketData.BlockEntityTagOutput output) {
        ClientboundLevelChunkPacketData data = chunkData(packet);
        data.getBlockEntitiesTagsConsumer(chunkX(packet), chunkZ(packet)).accept(output);
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
    }

    public static CompoundTag signTextTag(SignBlockEntity sign, HolderLookup.Provider registries, String message) {
        SignText text = sign.getFrontText().setMessage(0, Component.literal(message));
        CompoundTag tag = sign.saveCustomOnly(registries);
        tag.put("front_text", SignText.DIRECT_CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), text).getOrThrow());
        return tag;
    }

    public static String signLine(SignBlockEntity sign) {
        return sign.getFrontText().getMessage(0, false).getString();
    }
}
