package art.arcane.wormholes.modded;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

public record MinecraftProxyPayload(byte[] data) implements CustomPacketPayload {
    public static final Type<MinecraftProxyPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath("bungeecord", "main"));
    public static final StreamCodec<FriendlyByteBuf, MinecraftProxyPayload> CODEC = CustomPacketPayload.codec(
        (payload, buffer) -> buffer.writeBytes(payload.data()), buffer -> {
            byte[] bytes = new byte[buffer.readableBytes()];
            buffer.readBytes(bytes);
            return new MinecraftProxyPayload(bytes);
        });

    public static MinecraftProxyPayload connect(String server) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(64);
            DataOutputStream output = new DataOutputStream(bytes);
            output.writeUTF("Connect");
            output.writeUTF(server);
            return new MinecraftProxyPayload(bytes.toByteArray());
        } catch (IOException error) {
            throw new UncheckedIOException(error);
        }
    }

    @Override
    public Type<MinecraftProxyPayload> type() {
        return TYPE;
    }
}
