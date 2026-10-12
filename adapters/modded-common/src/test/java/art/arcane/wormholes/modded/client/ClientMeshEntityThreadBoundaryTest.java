package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.mixin.SeamlessEntityAccess;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.junit.Test;
import org.mockito.MockedStatic;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class ClientMeshEntityThreadBoundaryTest extends MinecraftTestBase {
    @Test
    public void logicalServerNeverReadsClientOwnershipOrEntityIds() {
        ServerLevel server = mock(ServerLevel.class);
        Entity entity = entity(server, ClientEntityIds.PROJECTED_MAX);
        WormholesClient client = mock(WormholesClient.class);
        ClientViewTick tick = mock(ClientViewTick.class);
        ClientProjectedEntities projected = mock(ClientProjectedEntities.class);
        ClientReflectionEntity reflections = mock(ClientReflectionEntity.class);
        when(client.tickState()).thenReturn(tick);
        when(tick.entities()).thenReturn(projected);
        when(client.reflections()).thenReturn(reflections);
        when(projected.meshEntity(anyInt())).thenThrow(new AssertionError("Logical server read client projection ownership"));
        when(reflections.meshEntity(anyInt())).thenThrow(new AssertionError("Logical server read client reflection ownership"));
        try (MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class)) {
            clients.when(WormholesClient::instance).thenReturn(client);
            for (int tickIndex = 0; tickIndex < 20; tickIndex++) {
                assertFalse(ClientMeshEntities.hiddenFromWorld(entity));
            }
            clients.verifyNoInteractions();
        }
        verifyNoInteractions(client, tick, projected, reflections);
        verify((SeamlessEntityAccess) entity, never()).wormholesEntityId();
        verify(entity, never()).getId();
    }

    @Test
    public void equalDimensionAndEntityIdsInAnotherClientRealmCannotReadTheActiveSets() {
        ClientLevel attached = mock(ClientLevel.class);
        ClientLevel resident = mock(ClientLevel.class);
        when(attached.dimension()).thenReturn(Level.OVERWORLD);
        when(resident.dimension()).thenReturn(Level.OVERWORLD);
        Entity foreign = entity(resident, ClientEntityIds.PROJECTED_MAX);
        WormholesClient client = mock(WormholesClient.class);
        ClientViewTick tick = mock(ClientViewTick.class);
        ClientProjectedEntities projected = mock(ClientProjectedEntities.class);
        ClientReflectionEntity reflections = mock(ClientReflectionEntity.class);
        when(client.viewsAttachedTo(attached)).thenReturn(true);
        when(client.tickState()).thenReturn(tick);
        when(tick.entities()).thenReturn(projected);
        when(client.reflections()).thenReturn(reflections);
        when(projected.meshEntity(ClientEntityIds.PROJECTED_MAX)).thenReturn(true);
        try (MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class)) {
            clients.when(WormholesClient::instance).thenReturn(client);
            assertFalse(ClientMeshEntities.hiddenFromWorld(foreign));
            verifyNoInteractions(tick, projected, reflections);
            verify((SeamlessEntityAccess) foreign, never()).wormholesEntityId();
            Entity copy = entity(attached, ClientEntityIds.PROJECTED_MAX);
            assertTrue(ClientMeshEntities.hiddenFromWorld(copy));
            when(client.viewsAttachedTo(attached)).thenReturn(false);
            when(client.viewsAttachedTo(resident)).thenReturn(true);
            assertFalse(ClientMeshEntities.hiddenFromWorld(copy));
            assertTrue(ClientMeshEntities.hiddenFromWorld(foreign));
        }
        verify(projected, times(2)).meshEntity(ClientEntityIds.PROJECTED_MAX);
        verifyNoInteractions(reflections);
    }

    @Test
    public void attachedClientProjectionReflectionAndOrdinaryEntityVisibilityStillWork() {
        ClientLevel level = mock(ClientLevel.class);
        WormholesClient client = mock(WormholesClient.class);
        ClientViewTick tick = mock(ClientViewTick.class);
        ClientProjectedEntities projected = mock(ClientProjectedEntities.class);
        ClientReflectionEntity reflections = mock(ClientReflectionEntity.class);
        when(client.viewsAttachedTo(level)).thenReturn(true);
        when(client.tickState()).thenReturn(tick);
        when(tick.entities()).thenReturn(projected);
        when(client.reflections()).thenReturn(reflections);
        when(projected.meshEntity(ClientEntityIds.PROJECTED_MAX)).thenReturn(true);
        when(reflections.meshEntity(ClientEntityIds.REFLECTION_MAX)).thenReturn(true);
        try (MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class)) {
            clients.when(WormholesClient::instance).thenReturn(client);
            assertTrue(ClientMeshEntities.hiddenFromWorld(entity(level, ClientEntityIds.PROJECTED_MAX)));
            assertTrue(ClientMeshEntities.hiddenFromWorld(entity(level, ClientEntityIds.REFLECTION_MAX)));
            assertFalse(ClientMeshEntities.hiddenFromWorld(entity(level, 42)));
        }
    }

    @Test
    public void entitiesWithoutAnAssignedWorldDoNotConsultTheClientSingleton() {
        Entity entity = mock(Entity.class);
        try (MockedStatic<WormholesClient> clients = mockStatic(WormholesClient.class)) {
            assertFalse(ClientMeshEntities.hiddenFromWorld(entity));
            clients.verifyNoInteractions();
        }
        verify(entity, never()).getId();
    }

    private static Entity entity(Level level, int id) {
        Entity entity = mock(Entity.class, withSettings().extraInterfaces(SeamlessEntityAccess.class));
        when(entity.level()).thenReturn(level);
        when(((SeamlessEntityAccess) entity).wormholesEntityId()).thenReturn(id);
        return entity;
    }
}
