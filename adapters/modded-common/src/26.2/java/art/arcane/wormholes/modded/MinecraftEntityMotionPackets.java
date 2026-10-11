package art.arcane.wormholes.modded;

import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAnimatePacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;

public final class MinecraftEntityMotionPackets {
    private MinecraftEntityMotionPackets() {
    }

    public static ClientboundMoveEntityPacket.Pos relative(int entityId, double x, double y, double z, boolean onGround) {
        return new ClientboundMoveEntityPacket.Pos(entityId, encodeDelta(x), encodeDelta(y), encodeDelta(z), onGround);
    }

    public static ClientboundMoveEntityPacket.PosRot relativeRotation(int entityId, double x, double y, double z, byte yaw, byte pitch, boolean onGround) {
        return new ClientboundMoveEntityPacket.PosRot(entityId, encodeDelta(x), encodeDelta(y), encodeDelta(z), yaw, pitch, onGround);
    }

    public static int animationId(Packet<?> packet) {
        if (packet instanceof ClientboundAnimatePacket animate) {
            return switch (animate.getAction()) {
                case ClientboundAnimatePacket.SWING_MAIN_HAND -> MinecraftEntityPackets.ANIMATION_SWING_MAIN_HAND;
                case ClientboundAnimatePacket.SWING_OFF_HAND -> MinecraftEntityPackets.ANIMATION_SWING_OFF_HAND;
                case ClientboundAnimatePacket.WAKE_UP -> MinecraftEntityPackets.ANIMATION_WAKE_UP;
                case ClientboundAnimatePacket.CRITICAL_HIT -> MinecraftEntityPackets.ANIMATION_CRITICAL_HIT;
                case ClientboundAnimatePacket.MAGIC_CRITICAL_HIT -> MinecraftEntityPackets.ANIMATION_MAGIC_CRITICAL_HIT;
                default -> MinecraftEntityPackets.NO_ANIMATION;
            };
        }
        return MinecraftEntityPackets.NO_ANIMATION;
    }

    public static Packet<? super ClientGamePacketListener> animation(int entityId, int animation) {
        return switch (animation) {
            case MinecraftEntityPackets.ANIMATION_SWING_MAIN_HAND -> animatePacket(entityId, ClientboundAnimatePacket.SWING_MAIN_HAND);
            case MinecraftEntityPackets.ANIMATION_SWING_OFF_HAND -> animatePacket(entityId, ClientboundAnimatePacket.SWING_OFF_HAND);
            case MinecraftEntityPackets.ANIMATION_WAKE_UP -> animatePacket(entityId, ClientboundAnimatePacket.WAKE_UP);
            case MinecraftEntityPackets.ANIMATION_CRITICAL_HIT -> animatePacket(entityId, ClientboundAnimatePacket.CRITICAL_HIT);
            case MinecraftEntityPackets.ANIMATION_MAGIC_CRITICAL_HIT -> animatePacket(entityId, ClientboundAnimatePacket.MAGIC_CRITICAL_HIT);
            default -> throw new IllegalArgumentException("Unsupported projected entity animation " + animation);
        };
    }

    private static ClientboundAnimatePacket animatePacket(int entityId, int action) {
        FriendlyByteBuf buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            buffer.writeVarInt(entityId);
            buffer.writeByte(action);
            return ClientboundAnimatePacket.STREAM_CODEC.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private static short encodeDelta(double value) {
        return (short) (value * 4096.0D);
    }
}
