package art.arcane.wormholes.modded.clientview;

import art.arcane.wormholes.network.client.ClientViewProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Objects;

public record ClientViewPayload(byte[] data) implements CustomPacketPayload {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(ClientViewProtocol.CHANNEL_NAMESPACE, ClientViewProtocol.CHANNEL_PATH);
    public static final Type<ClientViewPayload> TYPE = new Type<>(ID);
    public static final StreamCodec<FriendlyByteBuf, ClientViewPayload> CODEC = CustomPacketPayload.codec(
        (payload, buffer) -> buffer.writeBytes(payload.data()), ClientViewPayload::read);

    public ClientViewPayload {
        Objects.requireNonNull(data, "data");
    }

    public static ClientViewPayload read(FriendlyByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.readBytes(bytes);
        return new ClientViewPayload(bytes);
    }

    @Override
    public Type<ClientViewPayload> type() {
        return TYPE;
    }
}
