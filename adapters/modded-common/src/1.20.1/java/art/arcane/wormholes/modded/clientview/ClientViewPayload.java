package art.arcane.wormholes.modded.clientview;

import art.arcane.wormholes.network.client.ClientViewChannel;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record ClientViewPayload(byte[] data, long receivedNanos) {
    public static final ResourceLocation ID = new ResourceLocation(ClientViewChannel.NAMESPACE, ClientViewChannel.PATH);

    public ClientViewPayload(byte[] data) {
        this(data, System.nanoTime());
    }

    public ClientViewPayload {
        Objects.requireNonNull(data, "data");
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeBytes(data);
    }

    public static ClientViewPayload read(FriendlyByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.readBytes(bytes);
        return new ClientViewPayload(bytes);
    }
}
