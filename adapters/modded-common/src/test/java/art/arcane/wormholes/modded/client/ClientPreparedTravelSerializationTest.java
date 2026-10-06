package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.EncoderException;
import art.arcane.wormholes.modded.MinecraftChunkPacketEncoding;
import art.arcane.optics.stream.ViewStreamLimits;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ClientboundLevelChunkPacketData;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.network.protocol.game.ClientboundLightUpdatePacketData;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.MockedStatic;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ClientPreparedTravelSerializationTest {
    @BeforeClass
    public static void bootstrapMinecraft() {
        MinecraftTestBase.bootstrap();
    }

    @Test
    public void freshChunkConstructorAndDecodedPacketHaveDifferentMapOrderingContracts() {
        LevelChunk chunk = mock(LevelChunk.class);
        ArrayList<Map.Entry<Heightmap.Types, Heightmap>> heights = new ArrayList<>();
        for (Heightmap.Types type : Heightmap.Types.values()) {
            if (!type.sendToClient()) {
                continue;
            }
            Heightmap heightmap = mock(Heightmap.class);
            when(heightmap.getRawData()).thenReturn(new long[]{type.ordinal() + 1L, 31L, 93L});
            heights.add(Map.entry(type, heightmap));
        }
        assertTrue(heights.size() >= 2);
        when(chunk.getHeightmaps()).thenReturn(heights);
        when(chunk.getSections()).thenReturn(new LevelChunkSection[0]);
        when(chunk.getBlockEntities()).thenReturn(Map.of());
        ClientboundLevelChunkPacketData data = new ClientboundLevelChunkPacketData(chunk);
        assertEquals(HashMap.class, data.getHeightmaps().getClass());
        ClientboundLevelChunkWithLightPacket original = packet(data);
        byte[] before = encode(original);
        ClientboundLevelChunkWithLightPacket decoded = decode(before);
        assertEquals(EnumMap.class, decoded.chunkData().getHeightmaps().getClass());
        assertHeightmapsEqual(data, decoded.chunkData());
        byte[] after = encode(decoded);
        assertArrayEquals(after, encode(decode(after)));
        assertArrayEquals(after, MinecraftChunkPacketEncoding.encode(RegistryAccess.EMPTY, original));
        assertArrayEquals(after, MinecraftChunkPacketEncoding.encode(RegistryAccess.EMPTY, decoded));
        System.out.println("Fresh heightmap round trip: byteEqual=" + Arrays.equals(before, after)
            + ", originalOrder=" + data.getHeightmaps().keySet()
            + ", decodedOrder=" + decoded.chunkData().getHeightmaps().keySet());
    }

    @Test
    public void validNoncanonicalHeightmapOrderChangesBytesWithoutChangingChunkContents() throws ReflectiveOperationException {
        ArrayList<Heightmap.Types> types = new ArrayList<>();
        for (Heightmap.Types type : Heightmap.Types.values()) {
            if (type.sendToClient()) {
                types.add(type);
            }
        }
        assertTrue(types.size() >= 2);
        LinkedHashMap<Heightmap.Types, long[]> heights = new LinkedHashMap<>();
        for (int index = types.size() - 1; index >= 0; index--) {
            Heightmap.Types type = types.get(index);
            heights.put(type, new long[]{type.ordinal() + 1L, 31L, 93L});
        }
        Constructor<ClientboundLevelChunkPacketData> constructor = ClientboundLevelChunkPacketData.class
            .getDeclaredConstructor(Map.class, byte[].class, List.class);
        constructor.setAccessible(true);
        ClientboundLevelChunkPacketData data = constructor.newInstance(heights, new byte[]{7, 3, 9}, List.of());
        byte[] before = encode(packet(data));
        ClientboundLevelChunkWithLightPacket decoded = decode(before);
        assertHeightmapsEqual(data, decoded.chunkData());
        byte[] after = encode(decoded);
        assertFalse(Arrays.equals(before, after));
        assertArrayEquals(after, encode(decode(after)));
        assertArrayEquals(after, MinecraftChunkPacketEncoding.encode(RegistryAccess.EMPTY, packet(data)));
        assertArrayEquals(after, MinecraftChunkPacketEncoding.encode(RegistryAccess.EMPTY, decoded));
        assertEquals(64, decoded.x());
        assertEquals(-3, decoded.z());
        assertEquals(new BitSet(), decoded.lightData().skyYMask());
    }

    @Test
    public void substantialChunkAndLightPayloadEncodesWithoutGrowingItsTemporaryBuffer() throws ReflectiveOperationException {
        byte[] blocks = new byte[64 * 1024];
        Arrays.fill(blocks, (byte) 37);
        List<byte[]> updates = new ArrayList<>();
        BitSet mask = new BitSet();
        for (int index = 0; index < 24; index++) {
            byte[] light = new byte[2048];
            Arrays.fill(light, (byte) index);
            updates.add(light);
            mask.set(index);
        }
        ClientboundLightUpdatePacketData light = new ClientboundLightUpdatePacketData(mask, mask,
            new BitSet(), new BitSet(), updates, updates);
        ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(64, -3,
            chunkData(blocks), light);
        byte[] expected = encode(packet);
        AtomicReference<ByteBuf> allocated = new AtomicReference<>();
        AtomicInteger initialCapacity = new AtomicInteger();
        try (MockedStatic<Unpooled> buffers = mockStatic(Unpooled.class, CALLS_REAL_METHODS)) {
            buffers.when(() -> Unpooled.buffer(anyInt(), eq(ViewStreamLimits.MAX_TRAVEL_CHUNK_BYTES)))
                .thenAnswer(call -> {
                    ByteBuf buffer = spy((ByteBuf) call.callRealMethod());
                    allocated.set(buffer);
                    initialCapacity.set(buffer.capacity());
                    return buffer;
                });
            assertArrayEquals(expected, MinecraftChunkPacketEncoding.encode(RegistryAccess.EMPTY, packet));
        }
        ByteBuf buffer = allocated.get();
        assertTrue(initialCapacity.get() >= expected.length);
        assertEquals(ViewStreamLimits.MAX_TRAVEL_CHUNK_BYTES, buffer.maxCapacity());
        assertEquals(0, buffer.refCnt());
        verify(buffer, never()).capacity(anyInt());
        assertArrayEquals(expected, encode(packet));
        assertEquals(37, blocks[0]);
        assertEquals(23, updates.get(23)[0]);
    }

    @Test
    public void oversizedEncodedPacketStillFailsAtTheTravelChunkLimit() throws ReflectiveOperationException {
        ClientboundLevelChunkWithLightPacket packet = packet(chunkData(new byte[ViewStreamLimits.MAX_TRAVEL_CHUNK_BYTES]));
        assertThrows(IndexOutOfBoundsException.class, () -> MinecraftChunkPacketEncoding.encode(RegistryAccess.EMPTY, packet));
    }

    @Test
    public void oversizedLightLayerStillUsesVanillaCodecValidation() throws ReflectiveOperationException {
        ClientboundLightUpdatePacketData light = new ClientboundLightUpdatePacketData(new BitSet(), new BitSet(),
            new BitSet(), new BitSet(), List.of(new byte[2049]), List.of());
        ClientboundLevelChunkWithLightPacket packet = new ClientboundLevelChunkWithLightPacket(64, -3,
            chunkData(new byte[0]), light);
        assertThrows(EncoderException.class, () -> MinecraftChunkPacketEncoding.encode(RegistryAccess.EMPTY, packet));
    }

    private static ClientboundLevelChunkPacketData chunkData(byte[] blocks) throws ReflectiveOperationException {
        Constructor<ClientboundLevelChunkPacketData> constructor = ClientboundLevelChunkPacketData.class
            .getDeclaredConstructor(Map.class, byte[].class, List.class);
        constructor.setAccessible(true);
        return constructor.newInstance(new EnumMap<>(Heightmap.Types.class), blocks, List.of());
    }

    private static ClientboundLevelChunkWithLightPacket packet(ClientboundLevelChunkPacketData data) {
        ClientboundLightUpdatePacketData light = new ClientboundLightUpdatePacketData(new BitSet(), new BitSet(),
            new BitSet(), new BitSet(), List.of(), List.of());
        return new ClientboundLevelChunkWithLightPacket(64, -3, data, light);
    }

    private static void assertHeightmapsEqual(ClientboundLevelChunkPacketData expected,
                                             ClientboundLevelChunkPacketData actual) {
        assertEquals(expected.getHeightmaps().keySet(), actual.getHeightmaps().keySet());
        for (Map.Entry<Heightmap.Types, long[]> entry : expected.getHeightmaps().entrySet()) {
            assertArrayEquals(entry.getValue(), actual.getHeightmaps().get(entry.getKey()));
        }
    }

    private static byte[] encode(ClientboundLevelChunkWithLightPacket packet) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            ClientboundLevelChunkWithLightPacket.STREAM_CODEC.encode(buffer, packet);
            byte[] data = new byte[buffer.readableBytes()];
            buffer.readBytes(data);
            return data;
        } finally {
            buffer.release();
        }
    }

    private static ClientboundLevelChunkWithLightPacket decode(byte[] data) {
        RegistryFriendlyByteBuf buffer = new RegistryFriendlyByteBuf(Unpooled.wrappedBuffer(data), RegistryAccess.EMPTY);
        try {
            ClientboundLevelChunkWithLightPacket packet = ClientboundLevelChunkWithLightPacket.STREAM_CODEC.decode(buffer);
            assertEquals(0, buffer.readableBytes());
            return packet;
        } finally {
            buffer.release();
        }
    }
}
