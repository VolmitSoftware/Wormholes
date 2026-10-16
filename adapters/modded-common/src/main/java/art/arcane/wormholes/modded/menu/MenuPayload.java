package art.arcane.wormholes.modded.menu;

import art.arcane.wormholes.menu.MenuLimits;
import art.arcane.wormholes.menu.protocol.MenuProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.Objects;

public record MenuPayload(byte[] data) implements CustomPacketPayload {
    public static final Identifier ID = Identifier.fromNamespaceAndPath(MenuProtocol.NAMESPACE, MenuProtocol.PATH);
    public static final Type<MenuPayload> TYPE = new Type<>(ID);
    public static final StreamCodec<FriendlyByteBuf, MenuPayload> CODEC = codec(MenuLimits.MAX_S2C_BYTES);
    public static final StreamCodec<FriendlyByteBuf, MenuPayload> SERVERBOUND_CODEC = codec(MenuLimits.MAX_C2S_BYTES);

    public MenuPayload {
        Objects.requireNonNull(data, "data");
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

    @Override
    public Type<MenuPayload> type() {
        return TYPE;
    }

    private static StreamCodec<FriendlyByteBuf, MenuPayload> codec(int maxBytes) {
        return CustomPacketPayload.codec((payload, buffer) -> buffer.writeBytes(payload.data()), buffer -> read(buffer, maxBytes));
    }
}
