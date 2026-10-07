package art.arcane.wormholes.modded.seamless;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.clientview.ClientViewPayload;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.core.BlockPos;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class RoutedSendsScopeTest extends MinecraftTestBase {
    private static final ProtocolInfo<ClientGamePacketListener> PROTOCOL = RoutedPackets.protocol(RegistryAccess.EMPTY);

    @Test
    public void residentRouteWrapsPacketsWithItsHandleAndIncreasingSequence() {
        List<TravelMessage> sent = new ArrayList<>();
        RoutedSends sends = new RoutedSends(PROTOCOL, sent::add);
        RemoteRoute route = route(7);

        assertTrue(sends.send(route, new ClientboundSetChunkCacheCenterPacket(1, 2)) > 0);
        assertTrue(sends.send(route, new ClientboundRemoveEntitiesPacket(4)) > 0);

        assertEquals(2, sent.size());
        TravelMessage.RoutedPacket first = (TravelMessage.RoutedPacket) sent.get(0);
        TravelMessage.RoutedPacket second = (TravelMessage.RoutedPacket) sent.get(1);
        assertEquals(7, first.levelHandle());
        assertEquals(7, second.levelHandle());
        assertEquals(first.sequence() + 1, second.sequence());
        assertEquals(1, ((ClientboundSetChunkCacheCenterPacket) RoutedPackets.decode(PROTOCOL, RoutedPackets.join(List.of(first)))).getX());
    }

    @Test
    public void bundlesAreWrappedPerSubPacketInOrder() {
        List<TravelMessage> sent = new ArrayList<>();
        RoutedSends sends = new RoutedSends(PROTOCOL, sent::add);
        List<Packet<? super ClientGamePacketListener>> inner = List.of(new ClientboundBlockUpdatePacket(BlockPos.ZERO, Blocks.DIRT.defaultBlockState()),
            new ClientboundRemoveEntitiesPacket(1));

        sends.send(route(3), new ClientboundBundlePacket(inner));

        assertEquals(2, sent.size());
        assertTrue(RoutedPackets.decode(PROTOCOL, RoutedPackets.join(List.of((TravelMessage.RoutedPacket) sent.get(0))))
            instanceof ClientboundBlockUpdatePacket);
        assertTrue(RoutedPackets.decode(PROTOCOL, RoutedPackets.join(List.of((TravelMessage.RoutedPacket) sent.get(1))))
            instanceof ClientboundRemoveEntitiesPacket);
    }

    @Test
    public void routesWithoutAResidentHandleOrClosedRoutesDropPackets() {
        List<TravelMessage> sent = new ArrayList<>();
        RoutedSends sends = new RoutedSends(PROTOCOL, sent::add);
        RemoteRoute near = route(0);
        RemoteRoute closed = route(4);
        closed.close();

        assertEquals(0, sends.send(near, new ClientboundRemoveEntitiesPacket(1)));
        assertEquals(0, sends.send(closed, new ClientboundRemoveEntitiesPacket(1)));
        assertEquals(0, sends.send(null, new ClientboundRemoveEntitiesPacket(1)));
        assertTrue(sent.isEmpty());
    }

    @Test
    public void aRefusedTravelSendReportsTheRoutedSendAsFailed() {
        RoutedSends sends = new RoutedSends(PROTOCOL, message -> false);

        assertEquals(RoutedSends.FAILED, sends.send(route(6), new ClientboundRemoveEntitiesPacket(1)));
    }

    @Test
    public void customPayloadsAreNeverRoutedIntoResidentLevels() {
        List<TravelMessage> sent = new ArrayList<>();
        RoutedSends sends = new RoutedSends(PROTOCOL, sent::add);

        assertEquals(0, sends.send(route(2), new ClientboundCustomPayloadPacket(new ClientViewPayload(new byte[] {1}))));
        assertTrue(sent.isEmpty());
    }

    @Test
    public void coverageFollowsDeliveredColumnsAndTheWindowBorderForLight() {
        RemoteRoute route = route(5);
        route.opened(true);
        route.stream().markDelivered(ChunkPos.pack(0, 0));
        route.stream().markDelivered(ChunkPos.pack(2, 0));

        assertTrue(RemoteRoutes.covers(route, 0, 0, false));
        assertFalse(RemoteRoutes.covers(route, 0, 0, true));
        assertTrue(RemoteRoutes.covers(route, 2, 0, true));
        assertFalse(RemoteRoutes.covers(route, 1, 0, false));
        route.opened(false);
        assertFalse(RemoteRoutes.covers(route, 0, 0, false));
    }

    private static RemoteRoute route(int handle) {
        return new RemoteRoute(new RemoteRoute.Key(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()), null,
            new Vec3d(8.0D, 64.0D, 8.0D), new RouteWindow(0, 0, 1), handle);
    }
}
