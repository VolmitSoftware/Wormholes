package art.arcane.wormholes.modded;

import art.arcane.wormholes.network.MinecraftStatusBridge;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.StatusProtocols;

public final class MinecraftStatusConnection extends ChannelDuplexHandler {
    private static final String HANDLER_NAME = "wormholes_status";
    private final MinecraftStatusBridge bridge;

    private MinecraftStatusConnection(MinecraftStatusBridge bridge) {
        this.bridge = bridge;
    }

    public static void attach(Channel channel, MinecraftStatusBridge bridge, String address) {
        bridge.receiveHandshake(channel, address);
        if (channel.pipeline().get(HANDLER_NAME) == null) {
            channel.pipeline().addBefore("packet_handler", HANDLER_NAME, new MinecraftStatusConnection(bridge));
        }
    }

    @Override
    public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) throws Exception {
        if (!(message instanceof ClientboundStatusResponsePacket packet)) {
            context.write(message, promise);
            return;
        }
        String response = bridge.takeResponse(context.channel());
        if (response == null) {
            context.write(message, promise);
            return;
        }
        ByteBuf encoded = context.alloc().buffer();
        try {
            StatusProtocols.CLIENTBOUND.codec().encode(encoded, packet);
            FriendlyByteBuf buffer = new FriendlyByteBuf(encoded);
            int packetId = buffer.readVarInt();
            JsonObject document = JsonParser.parseString(buffer.readUtf()).getAsJsonObject();
            document.remove("favicon");
            document.remove("description");
            document.remove("players");
            document.addProperty("wormholes", response);
            buffer.clear();
            buffer.writeVarInt(packetId);
            buffer.writeUtf(document.toString());
        } catch (RuntimeException error) {
            encoded.release();
            throw error;
        }
        context.write(encoded, promise);
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        bridge.disconnected(context.channel());
        context.fireChannelInactive();
    }
}
