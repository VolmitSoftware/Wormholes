package art.arcane.wormholes.modded.client;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.math.Face;
import art.arcane.optics.shape.ShapeDescriptor;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.network.client.TravelMessage;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static art.arcane.wormholes.modded.client.ClientTravelTestFixtures.set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ClientPreparedTravelEntityResidencyTest extends MinecraftTestBase {
    @Test
    public void routedAddEntitySpawnsTheRealEntityIntoTheResidentLevel() {
        ClientLevel current = ResidentTestFixtures.level(ResidentTestFixtures.OVERWORLD);
        try (ResidentLevelsOpenCloseTest.Scope scope = new ResidentLevelsOpenCloseTest.Scope(current)) {
            ResidentLevels residents = new ResidentLevels(scope.sent::add, 512L << 20);
            ClientLevel nether = residents.open(ResidentTestFixtures.open(3, ResidentTestFixtures.NETHER, 12, -4));
            AtomicReference<ClientLevel> spawnedInto = new AtomicReference<>();
            AtomicReference<ClientboundAddEntityPacket> spawned = new AtomicReference<>();
            doAnswer(call -> {
                spawnedInto.set(scope.connection.getLevel());
                spawned.set(call.getArgument(0));
                return null;
            }).when(scope.connection).handleAddEntity(any());
            UUID identity = UUID.randomUUID();
            for (TravelMessage.RoutedPacket fragment : ResidentTestFixtures.routed(3, 0, new ClientboundAddEntityPacket(612, identity,
                190.5, 70, -60.5, 0, 90, EntityTypes.ARMOR_STAND, 0, Vec3.ZERO, 90))) {
                residents.route(fragment);
            }
            assertSame(nether, spawnedInto.get());
            assertEquals(612, spawned.get().getId());
            assertEquals(identity, spawned.get().getUUID());
            assertSame(EntityTypes.ARMOR_STAND, spawned.get().getType());
        }
    }

    @Test
    public void anEntityIdArrivingInOneDimensionLeavesEveryOtherDimension() {
        ClientLevel current = ResidentTestFixtures.level(ResidentTestFixtures.OVERWORLD);
        try (ResidentLevelsOpenCloseTest.Scope scope = new ResidentLevelsOpenCloseTest.Scope(current)) {
            ResidentLevels residents = new ResidentLevels(scope.sent::add, 512L << 20);
            ClientLevel nether = ResidentTestFixtures.level(ResidentTestFixtures.NETHER);
            ResidentTestFixtures.loaded(nether, 12, -4);
            residents.retire(nether);
            residents.open(ResidentTestFixtures.open(3, ResidentTestFixtures.NETHER, 12, -4));
            ClientLevel farOverworld = ResidentTestFixtures.level(ResidentTestFixtures.OVERWORLD);
            ResidentTestFixtures.loaded(farOverworld, 200, 200);
            residents.retire(farOverworld);
            residents.open(ResidentTestFixtures.open(4, ResidentTestFixtures.OVERWORLD, 200, 200));
            Entity stale = mock(Entity.class);
            when(current.getEntity(612)).thenReturn(stale);
            when(farOverworld.getEntity(612)).thenReturn(stale);
            for (TravelMessage.RoutedPacket fragment : ResidentTestFixtures.routed(3, 0, new ClientboundAddEntityPacket(612, UUID.randomUUID(),
                190.5, 70, -60.5, 0, 90, EntityTypes.ARMOR_STAND, 0, Vec3.ZERO, 90))) {
                residents.route(fragment);
            }
            verify(current).removeEntity(612, Entity.RemovalReason.DISCARDED);
            verify(farOverworld).removeEntity(612, Entity.RemovalReason.DISCARDED);
            verify(nether, never()).removeEntity(612, Entity.RemovalReason.DISCARDED);
            scope.minecraft.player = SeamlessTravelFixtures.player();
            when(current.getEntity(42)).thenReturn(scope.minecraft.player);
            for (TravelMessage.RoutedPacket fragment : ResidentTestFixtures.routed(3, 1, new ClientboundAddEntityPacket(42, UUID.randomUUID(),
                190.5, 70, -60.5, 0, 90, EntityTypes.ARMOR_STAND, 0, Vec3.ZERO, 90))) {
                residents.route(fragment);
            }
            verify(current, never()).removeEntity(42, Entity.RemovalReason.DISCARDED);
        }
    }

    @Test
    public void acceptedCrossingsDropTheSourcePortalsProjectedCopies() throws ReflectiveOperationException {
        WormholesClient client = mock(WormholesClient.class, CALLS_REAL_METHODS);
        ClientViewSession session = mock(ClientViewSession.class);
        ClientViewTick tick = mock(ClientViewTick.class);
        ClientProjectedEntities entities = mock(ClientProjectedEntities.class);
        ApertureDescriptor source = ClientTravelTestFixtures.geometry();
        ApertureDescriptor other = new ApertureDescriptor(5, 0, 0, Face.N.ordinal(), true, 0, false, 2, 3, new long[]{63L}, ShapeDescriptor.FULL,
            0, 0, 1, 64, 0, 0, 0, 0, 0, 0, 0, 0.0D, 0, 12, List.of());
        Int2ObjectOpenHashMap<ClientPortal> portals = new Int2ObjectOpenHashMap<>();
        portals.put(7, new ClientPortal(7, source, 1, 0));
        portals.put(8, new ClientPortal(8, other, 1, 0));
        when(session.portals()).thenReturn(portals);
        when(tick.entities()).thenReturn(entities);
        set(client, "session", session);
        set(client, "tick", tick);
        client.dropProjectedEntities(source);
        verify(entities).drop(7);
        verify(entities, never()).drop(8);
    }
}
