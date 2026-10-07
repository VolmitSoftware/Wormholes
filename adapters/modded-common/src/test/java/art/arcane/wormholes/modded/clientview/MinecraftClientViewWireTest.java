package art.arcane.wormholes.modded.clientview;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.optics.stream.ViewStreamCapability;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamLimits;
import art.arcane.optics.stream.ViewStreamProtocolException;
import art.arcane.optics.stream.SessionPalette;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import art.arcane.wormholes.network.client.ClientViewExtensions;

public class MinecraftClientViewWireTest extends MinecraftTestBase {
    @Test
    public void everyBlockStateRoundTripsThroughTheSessionPalette() throws CommandSyntaxException {
        SessionPalette palette = new SessionPalette();
        int states = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                int id = palette.id(BlockStateParser.serialize(state));
                assertTrue(id >= ViewStreamLimits.RESERVED_PALETTE_IDS || state.isAir());
                BlockState parsed = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, palette.state(id), false).blockState();
                assertSame(palette.state(id), state, parsed);
                states++;
            }
        }
        assertEquals(states, palette.size() - ViewStreamLimits.RESERVED_PALETTE_IDS + airStates());
    }

    @Test
    public void paletteMessagesCarryCanonicalStatesOverTheWire() throws ViewStreamProtocolException, CommandSyntaxException {
        SessionPalette palette = new SessionPalette();
        List<BlockState> sample = new ArrayList<>();
        for (Block block : BuiltInRegistries.BLOCK) {
            List<BlockState> possible = block.getStateDefinition().getPossibleStates();
            sample.add(possible.get(possible.size() / 2));
            if (sample.size() == 64) {
                break;
            }
        }
        int[] ids = new int[sample.size()];
        for (int i = 0; i < sample.size(); i++) {
            ids[i] = palette.id(BlockStateParser.serialize(sample.get(i)));
        }
        List<ViewStreamMessage.PaletteEntry> entries = palette.cursor().pending(ids);
        byte[] frame = ClientViewExtensions.CODEC.encodeS2C(new ViewStreamMessage.Palette(entries), 7, ViewStreamLimits.FLAG_LAST);
        ViewStreamMessage.Palette decoded = (ViewStreamMessage.Palette) ClientViewExtensions.CODEC.decodeS2C(frame, ViewStreamCapability.ALL).message();
        for (ViewStreamMessage.PaletteEntry entry : decoded.entries()) {
            BlockState expected = sample.get(indexOf(ids, entry.id()));
            assertSame(expected, BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK, entry.state(), false).blockState());
        }
    }

    @Test
    public void transportWritesPayloadPacketsAndFlushesOnce() {
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        MinecraftClientViewTransport transport = new MinecraftClientViewTransport();
        MinecraftClientViewPeer peer = new MinecraftClientViewPeer(UUID.randomUUID(), "Viewer", connection);
        byte[] first = {1, 2, 3};
        byte[] second = {4, 5};
        transport.send(peer, first);
        transport.send(peer, second);
        assertNull(channel.readOutbound());
        transport.flush(peer);
        ClientboundCustomPayloadPacket firstPacket = channel.readOutbound();
        ClientboundCustomPayloadPacket secondPacket = channel.readOutbound();
        assertEquals(ClientViewPayload.TYPE, firstPacket.payload().type());
        assertArrayEquals(first, ((ClientViewPayload) firstPacket.payload()).data());
        assertArrayEquals(second, ((ClientViewPayload) secondPacket.payload()).data());
        List<Object> wrapped = new ArrayList<>();
        transport.packets(payload -> {
            wrapped.add(payload);
            return new ClientboundCustomPayloadPacket(payload);
        });
        transport.send(peer, first);
        transport.flush(peer);
        assertEquals(1, wrapped.size());
        assertTrue(channel.readOutbound() instanceof ClientboundCustomPayloadPacket);
        channel.finishAndReleaseAll();
    }

    private static int airStates() {
        int air = 0;
        for (Block block : BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                if (BlockStateParser.serialize(state).equals(SessionPalette.AIR)) {
                    air++;
                }
            }
        }
        return air;
    }

    private static int indexOf(int[] ids, int id) {
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] == id) {
                return i;
            }
        }
        throw new AssertionError("palette id " + id + " was not requested");
    }
}
