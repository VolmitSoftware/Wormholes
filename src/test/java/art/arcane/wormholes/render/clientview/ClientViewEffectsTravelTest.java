package art.arcane.wormholes.render.clientview;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.Wormholes;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ClientViewEffectsTravelTest {
    @Test
    void arrivalContextRestoresNestedActorAndCannotMuteAnotherTraveler() {
        Player outer = mock(Player.class);
        Player inner = mock(Player.class);
        when(outer.getUniqueId()).thenReturn(UUID.randomUUID());
        when(inner.getUniqueId()).thenReturn(UUID.randomUUID());
        ClientViewEffects.arrival(outer, true, () -> {
            assertTrue(ClientViewEffects.arrivalSeamless(outer));
            assertFalse(ClientViewEffects.arrivalSeamless(inner));
            ClientViewEffects.arrival(inner, true, () -> {
                assertTrue(ClientViewEffects.arrivalSeamless(inner));
                assertFalse(ClientViewEffects.arrivalSeamless(outer));
            });
            assertTrue(ClientViewEffects.arrivalSeamless(outer));
            ClientViewEffects.arrival(outer, false, () -> assertFalse(ClientViewEffects.arrivalSeamless(outer)));
            assertTrue(ClientViewEffects.arrivalSeamless(outer));
        });
        assertFalse(ClientViewEffects.arrivalSeamless(outer));
        assertFalse(ClientViewEffects.arrivalSeamless(inner));
    }

    @Test
    void failedObserverCannotLeaveArrivalSuppressionBehind() {
        Player traveler = mock(Player.class);
        when(traveler.getUniqueId()).thenReturn(UUID.randomUUID());
        assertThrows(IllegalStateException.class, () -> ClientViewEffects.arrival(traveler, true, () -> {
            assertTrue(ClientViewEffects.arrivalSeamless(traveler));
            throw new IllegalStateException("observer failed");
        }));
        assertFalse(ClientViewEffects.arrivalSeamless(traveler));
    }

    @Test
    void excludedTravelerKeepsNearbyBystanderSoundOnTheRecipientOwner() {
        World world = mock(World.class);
        Player traveler = mock(Player.class);
        Player bystander = mock(Player.class);
        Player distant = mock(Player.class);
        UUID excluded = UUID.randomUUID();
        Location point = new Location(world, 4, 80, 8);
        when(traveler.getUniqueId()).thenReturn(excluded);
        when(bystander.getUniqueId()).thenReturn(UUID.randomUUID());
        when(distant.getUniqueId()).thenReturn(UUID.randomUUID());
        when(bystander.getLocation()).thenReturn(new Location(world, 5, 80, 8));
        when(distant.getLocation()).thenReturn(new Location(world, 40, 80, 8));
        List<Runnable> scheduled = new ArrayList<>();
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class);
             MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(traveler, bystander, distant));
            scheduler.when(() -> FoliaScheduler.runEntity(eq(Wormholes.instance), any(Player.class), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    scheduled.add(invocation.getArgument(2, Runnable.class));
                    return true;
                });
            ClientViewEffects.sound(point, "minecraft:entity.enderman.teleport", SoundCategory.MASTER, 0.5F, 1.5F, excluded);
            assertEquals(2, scheduled.size());
            verify(bystander, never()).getLocation();
            verify(distant, never()).getLocation();
            for (Runnable delivery : scheduled) {
                delivery.run();
            }
            verify(bystander).playSound(point, "minecraft:entity.enderman.teleport", SoundCategory.MASTER, 0.5F, 1.5F);
            verify(distant, never()).playSound(any(Location.class), any(String.class), any(SoundCategory.class), anyFloat(), anyFloat());
            verify(traveler, never()).getLocation();
            verifyNoInteractions(world);
        }
    }

    @Test
    void ordinaryCrossingKeepsTheOriginalWorldBroadcast() {
        World world = mock(World.class);
        Location point = new Location(world, 4, 80, 8);
        ClientViewEffects.sound(point, "minecraft:entity.enderman.teleport", SoundCategory.MASTER, 0.5F, 1.5F, null);
        verify(world).playSound(point, "minecraft:entity.enderman.teleport", SoundCategory.MASTER, 0.5F, 1.5F);
    }
}
