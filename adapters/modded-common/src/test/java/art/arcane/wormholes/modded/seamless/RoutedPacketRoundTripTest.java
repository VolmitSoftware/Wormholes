package art.arcane.wormholes.modded.seamless;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.clientview.ClientViewPayload;
import art.arcane.wormholes.network.client.TravelMessage;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBundleDelimiterPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundForgetLevelChunkPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class RoutedPacketRoundTripTest extends MinecraftTestBase {
    @Test
    public void smallPacketTravelsAsOneFragmentAndDecodesToTheSamePacket() {
        ProtocolInfo<ClientGamePacketListener> protocol = RoutedPackets.protocol(RegistryAccess.EMPTY);
        ClientboundBlockUpdatePacket packet = new ClientboundBlockUpdatePacket(new BlockPos(12, 70, -9), Blocks.STONE.defaultBlockState());

        List<TravelMessage.RoutedPacket> fragments = RoutedPackets.encode(protocol, 7, 41, packet);

        assertEquals(1, fragments.size());
        assertEquals(7, fragments.getFirst().levelHandle());
        assertEquals(41, fragments.getFirst().sequence());
        assertEquals(0, fragments.getFirst().fragmentIndex());
        assertEquals(1, fragments.getFirst().fragmentCount());
        Packet<? super ClientGamePacketListener> decoded = RoutedPackets.decode(protocol, RoutedPackets.join(fragments));
        ClientboundBlockUpdatePacket update = (ClientboundBlockUpdatePacket) decoded;
        assertEquals(new BlockPos(12, 70, -9), update.getPos());
        assertSame(Blocks.STONE.defaultBlockState(), update.getBlockState());
    }

    @Test
    public void largePacketSplitsAtTheTravelFragmentSizeAndRejoinsInOrder() {
        ProtocolInfo<ClientGamePacketListener> protocol = RoutedPackets.protocol(RegistryAccess.EMPTY);
        IntArrayList ids = new IntArrayList(40_000);
        for (int id = 1_000_000; id < 1_040_000; id++) {
            ids.add(id);
        }
        ClientboundRemoveEntitiesPacket packet = new ClientboundRemoveEntitiesPacket(ids);

        List<TravelMessage.RoutedPacket> fragments = RoutedPackets.encode(protocol, 3, 0, packet);

        assertTrue(fragments.size() > 1);
        int total = fragments.getFirst().totalBytes();
        assertEquals((total + TravelMessage.TRAVEL_FRAGMENT_BYTES - 1) / TravelMessage.TRAVEL_FRAGMENT_BYTES, fragments.size());
        for (int index = 0; index < fragments.size(); index++) {
            TravelMessage.RoutedPacket fragment = fragments.get(index);
            assertEquals(index, fragment.fragmentIndex());
            assertEquals(fragments.size(), fragment.fragmentCount());
            assertEquals(0, fragment.sequence());
            assertTrue(fragment.payload().length <= TravelMessage.TRAVEL_FRAGMENT_BYTES);
        }
        ClientboundRemoveEntitiesPacket decoded = (ClientboundRemoveEntitiesPacket) RoutedPackets.decode(protocol, RoutedPackets.join(fragments));
        assertEquals(ids, decoded.entityIds());
    }

    @Test
    public void bundleIsRoutedPerSubPacketInOrder() {
        ProtocolInfo<ClientGamePacketListener> protocol = RoutedPackets.protocol(RegistryAccess.EMPTY);
        List<Packet<? super ClientGamePacketListener>> inner = List.of(new ClientboundSetChunkCacheCenterPacket(4, -2),
            new ClientboundForgetLevelChunkPacket(new ChunkPos(5, 6)), new ClientboundRemoveEntitiesPacket(9, 10));
        ClientboundBundlePacket bundle = new ClientboundBundlePacket(inner);

        List<Packet<? super ClientGamePacketListener>> flattened = RoutedPackets.flatten(bundle);
        List<Packet<? super ClientGamePacketListener>> decoded = new ArrayList<>();
        int sequence = 100;
        for (Packet<? super ClientGamePacketListener> packet : flattened) {
            decoded.add(RoutedPackets.decode(protocol, RoutedPackets.join(RoutedPackets.encode(protocol, 1, sequence++, packet))));
        }

        assertEquals(3, decoded.size());
        assertEquals(4, ((ClientboundSetChunkCacheCenterPacket) decoded.get(0)).getX());
        assertEquals(-2, ((ClientboundSetChunkCacheCenterPacket) decoded.get(0)).getZ());
        assertEquals(new ChunkPos(5, 6), ((ClientboundForgetLevelChunkPacket) decoded.get(1)).pos());
        assertEquals(IntArrayList.of(9, 10), ((ClientboundRemoveEntitiesPacket) decoded.get(2)).entityIds());
        assertEquals(List.of(new ClientboundForgetLevelChunkPacket(new ChunkPos(1, 1))),
            RoutedPackets.flatten(new ClientboundForgetLevelChunkPacket(new ChunkPos(1, 1))));
    }

    @Test
    public void customPayloadsBundlesAndDelimitersAreNeverEncodedDirectly() {
        assertFalse(RoutedPackets.routable(new ClientboundCustomPayloadPacket(new ClientViewPayload(new byte[] {1, 2}))));
        assertFalse(RoutedPackets.routable(new ClientboundBundleDelimiterPacket()));
        assertFalse(RoutedPackets.routable(new ClientboundBundlePacket(List.of())));
        assertTrue(RoutedPackets.routable(new ClientboundSetChunkCacheCenterPacket(0, 0)));
        assertThrows(IllegalArgumentException.class, () -> RoutedPackets.encode(RoutedPackets.protocol(RegistryAccess.EMPTY), 1, 0,
            new ClientboundCustomPayloadPacket(new ClientViewPayload(new byte[] {1}))));
        assertEquals(List.of(), RoutedPackets.flatten(new ClientboundCustomPayloadPacket(new ClientViewPayload(new byte[] {1}))));
    }

    @Test
    public void joinRejectsMissingDuplicatedOrMixedFragments() {
        ProtocolInfo<ClientGamePacketListener> protocol = RoutedPackets.protocol(RegistryAccess.EMPTY);
        IntArrayList ids = new IntArrayList(40_000);
        for (int id = 2_000_000; id < 2_040_000; id++) {
            ids.add(id);
        }
        List<TravelMessage.RoutedPacket> fragments = RoutedPackets.encode(protocol, 2, 5, new ClientboundRemoveEntitiesPacket(ids));
        List<TravelMessage.RoutedPacket> other = RoutedPackets.encode(protocol, 2, 6, new ClientboundRemoveEntitiesPacket(ids));

        assertThrows(IllegalArgumentException.class, () -> RoutedPackets.join(fragments.subList(0, fragments.size() - 1)));
        assertThrows(IllegalArgumentException.class, () -> RoutedPackets.join(List.of(fragments.get(1), fragments.get(0))));
        List<TravelMessage.RoutedPacket> mixed = new ArrayList<>(fragments);
        mixed.set(1, other.get(1));
        assertThrows(IllegalArgumentException.class, () -> RoutedPackets.join(mixed));
        assertThrows(IllegalArgumentException.class, () -> RoutedPackets.join(List.of()));
    }

    @Test
    public void packetLargerThanTheRoutedLimitIsRejected() {
        IntArrayList ids = new IntArrayList(800_000);
        for (int id = 0; id < 800_000; id++) {
            ids.add(Integer.MAX_VALUE - id);
        }

        assertThrows(IllegalArgumentException.class, () -> RoutedPackets.encode(RoutedPackets.protocol(RegistryAccess.EMPTY), 1, 0,
            new ClientboundRemoveEntitiesPacket(ids)));
    }
}
