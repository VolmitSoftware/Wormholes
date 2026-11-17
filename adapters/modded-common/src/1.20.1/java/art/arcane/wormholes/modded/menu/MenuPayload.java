package art.arcane.wormholes.modded.menu;

import art.arcane.wormholes.menu.MenuLimits;
import art.arcane.wormholes.menu.protocol.MenuProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

public record MenuPayload(byte[] data) {
    public static final ResourceLocation ID = new ResourceLocation(MenuProtocol.NAMESPACE, MenuProtocol.PATH);

    public MenuPayload {
        Objects.requireNonNull(data, "data");
    }

    public void write(FriendlyByteBuf buffer) {
        buffer.writeBytes(data);
    }

    public static MenuPayload read(FriendlyByteBuf buffer) {
        return read(buffer, MenuLimits.MAX_S2C_BYTES);
    }

    public static MenuPayload readServerbound(FriendlyByteBuf buffer) {
        return read(buffer, MenuLimits.MAX_C2S_BYTES);
    }

    public static MenuPayload read(FriendlyByteBuf buffer, int maxBytes) {
        int length = buffer.readableBytes();
        if (length > maxBytes) {
            throw new IllegalArgumentException("Menu payload of " + length + " bytes exceeds " + maxBytes);
        }
        byte[] bytes = new byte[length];
        buffer.readBytes(bytes);
        return new MenuPayload(bytes);
    }
}
