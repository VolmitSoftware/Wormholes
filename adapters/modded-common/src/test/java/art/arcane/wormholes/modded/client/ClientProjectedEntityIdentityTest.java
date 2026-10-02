package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.network.client.ClientViewMessage;
import art.arcane.wormholes.network.view.EntityDeltaCodec;
import art.arcane.wormholes.network.view.EntityVisual;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.RegistryAccess;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class ClientProjectedEntityIdentityTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void branchesCoexistWithTheSourceAndMetadataUpdatesKeepTheirEntities() {
        UUID source = UUID.randomUUID();
        ClientViewHarness.FakeScene scene = new ClientViewHarness.FakeScene();
        scene.identities.put(42, source);
        ClientProjectedEntities entities = new ClientProjectedEntities(scene);
        ClientPortal portal = new ClientPortal(1, ClientViewHarness.geometry(), 1, 0);
        EntityVisual initial = visual(source, "minecraft:pig", 1);
        for (int key = 1; key <= 8; key++) {
            entities.apply(new ClientViewMessage.EntityFrame(key, 1, List.of(initial), List.of(source), true));
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
        EntityVisual previous = initial;
        for (int revision = 2; revision <= 40; revision++) {
            EntityVisual updated = visual(source, "minecraft:pig", revision);
            EntityVisual delta = EntityDeltaCodec.buildDelta(updated, previous, revision,
                EntityDeltaCodec.computeMask(updated, previous));
            for (int key = 1; key <= 8; key++) {
                entities.apply(new ClientViewMessage.EntityFrame(key, revision, List.of(delta), List.of(source), true));
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
        entities.apply(new ClientViewMessage.EntityFrame(1, 41, List.of(previous), List.of(source), true));
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
        ClientLevelScene scene = new ClientLevelScene(level, () -> connection);
        UUID projection = UUID.randomUUID();
        EntityVisual player = visual(UUID.randomUUID(), "minecraft:player", 1);
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
        scene.tick(-100, true);
        scene.tick(10, false);
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
        ClientLevelScene scene = new ClientLevelScene(level, () -> connection);
        UUID projection = UUID.randomUUID();
        assertFalse(scene.spawn(-100, projection, visual(UUID.randomUUID(), "minecraft:player", 1)));
        ArgumentCaptor<ClientboundPlayerInfoRemovePacket> remove = ArgumentCaptor.forClass(ClientboundPlayerInfoRemovePacket.class);
        verify(connection).handlePlayerInfoRemove(remove.capture());
        assertEquals(List.of(projection), remove.getValue().profileIds());
    }

    private static EntityVisual visual(UUID id, String type, int revision) {
        return EntityVisual.full(id, type, 1.5, 64, 5.5, 1, 0, 0, 1, 0, 0, 0, 0, 0, true,
            "Projection", "", "", null, null, new byte[] {(byte) revision}, EntityVisual.EMPTY, revision);
    }
}
