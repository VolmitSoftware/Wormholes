package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.mixin.SeamlessEntityAccess;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.entity.EntityDeltaCodec;
import art.arcane.optics.entity.EntitySnapshot;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.world.entity.Entity;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class ClientProjectedEntityIdentityTest extends MinecraftTestBase {
    @Test
    public void branchesCoexistWithTheSourceAndMetadataUpdatesKeepTheirEntities() {
        UUID source = UUID.randomUUID();
        ClientViewHarness.FakeScene scene = new ClientViewHarness.FakeScene();
        scene.identities.put(42, source);
        ClientProjectedEntities entities = new ClientProjectedEntities(scene);
        ClientPortal portal = new ClientPortal(1, ClientViewHarness.geometry(), 1, 0);
        EntitySnapshot initial = visual(source, "minecraft:pig", 1);
        for (int key = 1; key <= 8; key++) {
            entities.apply(new ViewStreamMessage.EntityFrame(key, 1, List.of(initial), List.of(source), true));
        }
        entities.tick(key -> portal, key -> true);
        assertEquals(8, entities.spawned());
        assertEquals(9, new HashSet<>(scene.identities.values()).size());
        int[] ids = new int[8];
        UUID[] projections = new UUID[8];
        for (int index = 0; index < ids.length; index++) {
            ids[index] = entities.entityId(index + 1, source);
            projections[index] = scene.identities.get(ids[index]);
            assertNotEquals(source, projections[index]);
        }
        EntitySnapshot previous = initial;
        for (int revision = 2; revision <= 40; revision++) {
            EntitySnapshot updated = visual(source, "minecraft:pig", revision);
            EntitySnapshot delta = EntityDeltaCodec.buildDelta(updated, previous, revision,
                EntityDeltaCodec.computeMask(updated, previous));
            for (int key = 1; key <= 8; key++) {
                entities.apply(new ViewStreamMessage.EntityFrame(key, revision, List.of(delta), List.of(source), true));
            }
            entities.tick(key -> portal, key -> true);
            previous = updated;
        }
        for (int index = 0; index < ids.length; index++) {
            assertEquals(ids[index], entities.entityId(index + 1, source));
            assertEquals(projections[index], scene.identities.get(ids[index]));
            assertEquals(40, scene.metadata.get(ids[index])[0]);
        }
        assertEquals(8, scene.events.stream().filter(event -> event.startsWith("spawn ")).count());
        assertEquals(0, entities.spawnFailures());
        entities.drop(1);
        assertEquals(source, scene.identities.get(42));
        assertEquals(7, entities.spawned());
        entities.apply(new ViewStreamMessage.EntityFrame(1, 41, List.of(previous), List.of(source), true));
        entities.tick(key -> portal, key -> true);
        assertEquals(projections[0], scene.identities.get(entities.entityId(1, source)));
        assertEquals(ids[1], entities.entityId(2, source));
        entities.clear();
        assertEquals(1, scene.identities.size());
        assertEquals(source, scene.identities.get(42));
    }

    @Test
    public void nativePlayerSpawnAndRemovalUseOnlyTheProjectionProfile() {
        ClientLevel level = mock(ClientLevel.class);
        ClientPacketListener connection = mock(ClientPacketListener.class);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        when(level.getEntity(-100)).thenReturn(mock(Entity.class));
        when(connection.getLevel()).thenReturn(level);
        ClientLevelScene scene = new ClientLevelScene(level, () -> connection);
        UUID projection = UUID.randomUUID();
        EntitySnapshot player = visual(UUID.randomUUID(), "minecraft:player", 1);
        assertTrue(scene.spawn(-100, projection, player));
        ArgumentCaptor<ClientboundAddEntityPacket> spawn = ArgumentCaptor.forClass(ClientboundAddEntityPacket.class);
        verify(connection).handleAddEntity(spawn.capture());
        assertEquals(projection, spawn.getValue().getUUID());
        ArgumentCaptor<ClientboundPlayerInfoUpdatePacket> info = ArgumentCaptor.forClass(ClientboundPlayerInfoUpdatePacket.class);
        verify(connection).handlePlayerInfoUpdate(info.capture());
        assertEquals(projection, info.getValue().entries().getFirst().profileId());
        scene.remove(-100, player);
        ArgumentCaptor<ClientboundPlayerInfoRemovePacket> remove = ArgumentCaptor.forClass(ClientboundPlayerInfoRemovePacket.class);
        verify(connection).handlePlayerInfoRemove(remove.capture());
        assertEquals(List.of(projection), remove.getValue().profileIds());
    }

    @Test
    public void nativeCopiesHaveNoPhysicsAndCannotExchangePushImpulses() {
        ClientLevel level = mock(ClientLevel.class);
        Entity projected = mock(Entity.class);
        Entity original = mock(Entity.class);
        when(level.getEntity(-100)).thenReturn(projected);
        when(level.getEntity(10)).thenReturn(original);
        ClientLevelScene scene = new ClientLevelScene(level, () -> null);
        scene.tick(-100, 7, true);
        scene.tick(10, 7, false);
        assertTrue(projected.noPhysics);
        assertFalse(original.noPhysics);
        doCallRealMethod().when(projected).push(original);
        doCallRealMethod().when(original).push(projected);
        projected.push(original);
        original.push(projected);
        verify(projected, never()).push(anyDouble(), anyDouble(), anyDouble());
        verify(original, never()).push(anyDouble(), anyDouble(), anyDouble());
    }

    @Test
    public void failedPlayerSpawnReleasesItsProjectionProfile() {
        ClientLevel level = mock(ClientLevel.class);
        ClientPacketListener connection = mock(ClientPacketListener.class);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        when(connection.getLevel()).thenReturn(level);
        ClientLevelScene scene = new ClientLevelScene(level, () -> connection);
        UUID projection = UUID.randomUUID();
        assertFalse(scene.spawn(-100, projection, visual(UUID.randomUUID(), "minecraft:player", 1)));
        ArgumentCaptor<ClientboundPlayerInfoRemovePacket> remove = ArgumentCaptor.forClass(ClientboundPlayerInfoRemovePacket.class);
        verify(connection).handlePlayerInfoRemove(remove.capture());
        assertEquals(List.of(projection), remove.getValue().profileIds());
    }

    @Test
    public void staleCopyHoldingTheProjectionIdentityLeavesTheLevelBeforeTheNewCopyIsAdded() {
        ClientLevel level = mock(ClientLevel.class);
        ClientPacketListener connection = mock(ClientPacketListener.class);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        when(connection.getLevel()).thenReturn(level);
        UUID projection = UUID.randomUUID();
        Entity stale = mock(Entity.class, withSettings().extraInterfaces(SeamlessEntityAccess.class));
        when(stale.getId()).thenReturn(ClientEntityIds.PROJECTED_MAX);
        when(((SeamlessEntityAccess) stale).wormholesEntityId()).thenReturn(ClientEntityIds.PROJECTED_MAX);
        when(level.getEntity(projection)).thenReturn(stale);
        when(level.getEntity(ClientEntityIds.PROJECTED_MAX)).thenReturn(stale);
        when(level.getEntity(ClientEntityIds.PROJECTED_MAX - 1)).thenReturn(mock(Entity.class));
        ClientLevelScene scene = new ClientLevelScene(level, () -> connection);
        assertTrue(scene.spawn(ClientEntityIds.PROJECTED_MAX - 1, projection, visual(UUID.randomUUID(), "minecraft:pig", 1)));
        InOrder order = inOrder(level, connection);
        order.verify(level).removeEntity(ClientEntityIds.PROJECTED_MAX, Entity.RemovalReason.DISCARDED);
        order.verify(connection).handleAddEntity(any(ClientboundAddEntityPacket.class));
    }

    @Test
    public void identityHeldByARealEntityOrAConnectionInAnotherLevelRefusesTheSpawnWithoutAddingAnything() {
        ClientLevel level = mock(ClientLevel.class);
        ClientPacketListener connection = mock(ClientPacketListener.class);
        when(level.registryAccess()).thenReturn(RegistryAccess.EMPTY);
        when(connection.getLevel()).thenReturn(level);
        UUID projection = UUID.randomUUID();
        Entity real = mock(Entity.class, withSettings().extraInterfaces(SeamlessEntityAccess.class));
        when(real.getId()).thenReturn(12);
        when(((SeamlessEntityAccess) real).wormholesEntityId()).thenReturn(12);
        when(level.getEntity(projection)).thenReturn(real);
        ClientLevelScene scene = new ClientLevelScene(level, () -> connection);
        assertFalse(scene.spawn(ClientEntityIds.PROJECTED_MAX, projection, visual(UUID.randomUUID(), "minecraft:player", 1)));
        verify(connection, never()).handleAddEntity(any(ClientboundAddEntityPacket.class));
        verify(connection, never()).handlePlayerInfoUpdate(any(ClientboundPlayerInfoUpdatePacket.class));
        verify(level, never()).removeEntity(anyInt(), any(Entity.RemovalReason.class));
        ClientPacketListener elsewhere = mock(ClientPacketListener.class);
        when(elsewhere.getLevel()).thenReturn(mock(ClientLevel.class));
        ClientLevelScene switched = new ClientLevelScene(level, () -> elsewhere);
        assertFalse(switched.spawn(ClientEntityIds.PROJECTED_MAX, UUID.randomUUID(), visual(UUID.randomUUID(), "minecraft:pig", 1)));
        verify(elsewhere, never()).handleAddEntity(any(ClientboundAddEntityPacket.class));
    }

    private static EntitySnapshot visual(UUID id, String type, int revision) {
        return EntitySnapshot.full(id, type, 1.5, 64, 5.5, 1, 0, 0, 1, 0, 0, 0, 0, 0, true,
            "Projection", "", "", null, null, new byte[] {(byte) revision}, EntitySnapshot.EMPTY, revision);
    }
}
