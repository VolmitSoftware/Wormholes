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
import net.minecraft.network.protocol.game.ClientboundSwingAnimationPacket;
import net.minecraft.network.protocol.game.ServerboundAcceptTeleportationPacket;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.entity.SignTextSlot;

public final class MinecraftGameTestAccess {
    private MinecraftGameTestAccess() {
    }

    public static void acknowledge(ServerPlayer player, ClientboundPlayerPositionPacket position) {
        player.connection.handleAcceptTeleportPacket(new ServerboundAcceptTeleportationPacket(position.id(),
            player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot()));
    }

    public static void punch(ServerPlayer player) {
        player.connection.handlePunch(ServerboundPunchPacket.INSTANCE);
    }

    public static boolean swing(Object packet, int entityId, InteractionHand hand) {
        return packet instanceof ClientboundSwingAnimationPacket swing && swing.entityId() == entityId && swing.hand() == hand;
    }

    public static IntList removed(ClientboundRemoveEntitiesPacket packet) {
        return packet.entityIds();
    }

    public static ClientboundLevelChunkPacketData chunkData(ClientboundLevelChunkWithLightPacket packet) {
        return packet.chunkData();
    }

    public static void blockEntityTags(ClientboundLevelChunkWithLightPacket packet, ClientboundLevelChunkPacketData.BlockEntityTagOutput output) {
        ClientboundLevelChunkPacketData data = chunkData(packet);
        data.forEachBlockEntityTag(chunkX(packet), chunkZ(packet), output);
    }

    public static int chunkX(ClientboundLevelChunkWithLightPacket packet) {
        return packet.x();
    }

    public static int chunkZ(ClientboundLevelChunkWithLightPacket packet) {
        return packet.z();
    }

    public static ParticleOptions particle(ClientboundLevelParticlesPacket packet) {
        return packet.particle();
    }

    public static int count(ClientboundLevelParticlesPacket packet) {
        return packet.count();
    }

    public static double x(ClientboundLevelParticlesPacket packet) {
        return packet.x();
    }

    public static double y(ClientboundLevelParticlesPacket packet) {
        return packet.y();
    }

    public static double z(ClientboundLevelParticlesPacket packet) {
        return packet.z();
    }

    public static float xDist(ClientboundLevelParticlesPacket packet) {
        return packet.xDist();
    }

    public static boolean invulnerable(Entity entity) {
        return entity.isPermanentlyInvulnerable();
    }

    public static void clearDamageCooldown(ServerPlayer player) {
        player.setInvulnerableTime(0);
    }

    public static CompoundTag signTextTag(SignBlockEntity sign, HolderLookup.Provider registries, String message) {
        SignText text = sign.getText(SignTextSlot.FRONT).asMutable().setLine(0, Component.literal(message)).asImmutable();
        CompoundTag tag = sign.saveCustomOnly(registries);
        tag.put("front_text", SignText.CODEC.encodeStart(registries.createSerializationContext(NbtOps.INSTANCE), text).getOrThrow());
        return tag;
    }

    public static String signLine(SignBlockEntity sign) {
        return sign.getText(SignTextSlot.FRONT).getMessages(false).getFirst().getString();
    }
}
