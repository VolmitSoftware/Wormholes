package art.arcane.wormholes.modded;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;

public record MinecraftProxyPayload(byte[] data) {
    public static final ResourceLocation ID = new ResourceLocation("bungeecord", "main");

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

    public static MinecraftProxyPayload read(FriendlyByteBuf buffer) {
        byte[] bytes = new byte[buffer.readableBytes()];
        buffer.readBytes(bytes);
        return new MinecraftProxyPayload(bytes);
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeBytes(data);
    }
}
