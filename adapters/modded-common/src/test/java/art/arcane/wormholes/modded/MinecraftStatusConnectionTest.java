package art.arcane.wormholes.modded;

import art.arcane.wormholes.network.MinecraftStatusBridge;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.SharedConstants;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.PacketEncoder;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.network.protocol.status.StatusProtocols;
import net.minecraft.core.RegistryAccess;
import net.minecraft.server.Bootstrap;
import org.junit.BeforeClass;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftStatusConnectionTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void preservesNormalStatusWithTheNativePacketEncoder() {
        MinecraftStatusBridge bridge = mock(MinecraftStatusBridge.class);
        EmbeddedChannel channel = channel();
        try {
            MinecraftStatusConnection.attach(channel, bridge, "example.test");
            assertTrue(channel.writeOutbound(packet()));
            JsonObject document = response(channel);
            assertTrue(document.has("description"));
            assertTrue(document.has("players"));
            assertTrue(document.has("favicon"));
            assertFalse(document.has("wormholes"));
            verify(bridge).receiveHandshake(channel, "example.test");
        } finally {
            channel.finishAndReleaseAll();
        }
        verify(bridge).disconnected(channel);
    }

    @Test
    public void writesSidebandAsOneStatusFrameAndPreservesProtocolVersion() {
        MinecraftStatusBridge bridge = mock(MinecraftStatusBridge.class);
        EmbeddedChannel channel = channel();
        try {
            MinecraftStatusConnection.attach(channel, bridge, "whs.request");
            when(bridge.takeResponse(channel)).thenReturn("signed-response");
            assertTrue(channel.writeOutbound(packet()));
            JsonObject document = response(channel);
            assertEquals("signed-response", document.get("wormholes").getAsString());
            assertEquals(SharedConstants.getCurrentVersion().protocolVersion(),
                document.getAsJsonObject("version").get("protocol").getAsInt());
            assertFalse(document.has("description"));
            assertFalse(document.has("players"));
            assertFalse(document.has("favicon"));
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static EmbeddedChannel channel() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("encoder", new PacketEncoder<>(StatusProtocols.CLIENTBOUND));
        channel.pipeline().addLast("packet_handler", new ChannelInboundHandlerAdapter());
        return channel;
    }

    private static ClientboundStatusResponsePacket packet() {
        JsonObject document = new JsonObject();
        document.addProperty("description", "Example server");
        document.add("players", JsonParser.parseString("{\"max\":20,\"online\":1}"));
        JsonObject version = new JsonObject();
        version.addProperty("name", SharedConstants.getCurrentVersion().name());
        version.addProperty("protocol", SharedConstants.getCurrentVersion().protocolVersion());
        document.add("version", version);
        document.addProperty("favicon", "data:image/png;base64,AQID");
        return new ClientboundStatusResponsePacket(ServerStatus.CODEC.parse(
            RegistryAccess.EMPTY.createSerializationContext(JsonOps.INSTANCE), document).getOrThrow());
    }

    private static JsonObject response(EmbeddedChannel channel) {
        ByteBuf encoded = channel.readOutbound();
        try {
            FriendlyByteBuf buffer = new FriendlyByteBuf(encoded);
            assertEquals(0, buffer.readVarInt());
            JsonObject document = JsonParser.parseString(buffer.readUtf()).getAsJsonObject();
            assertFalse(buffer.isReadable());
            return document;
        } finally {
            encoded.release();
        }
    }
}
