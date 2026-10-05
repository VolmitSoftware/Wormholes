package art.arcane.wormholes.modded;

import art.arcane.wormholes.modded.clientview.MinecraftClientViewService;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftAuthoritativeTeleportTest extends MinecraftTestBase {
    @Test
    public void externalSameWorldTeleportCancelsOldDepartureAndReseedsBothHistories() throws Exception {
        Fixture fixture = fixture();
        fixture.runtime().authoritativeTeleport(fixture.player(), fixture.level(), new Vec3(0, 80, 2));
        verify(fixture.views()).cancelTravel(fixture.player(), null);
        verify(fixture.portals()).recordTeleport(fixture.player());
        verify(fixture.doors()).recordTeleport(fixture.player());
        verify(fixture.doors()).cancelDeparture(fixture.player());
    }

    @Test
    public void ownPreparedTeleportAndNestedPassengerScopePreserveTheCommittingTransaction() throws Exception {
        Fixture fixture = fixture();
        ServerPlayer passenger = mock(ServerPlayer.class);
        when(passenger.getUUID()).thenReturn(UUID.randomUUID());
        when(passenger.level()).thenReturn(fixture.level());
        when(passenger.position()).thenReturn(new Vec3(20, 80, -2));
        when(passenger.getPassengers()).thenReturn(List.of());
        when(fixture.player().getPassengers()).thenReturn(List.of(passenger));
        try (WormholesModRuntime.TeleportScope scope = fixture.runtime().beginTeleport(fixture.player())) {
            fixture.runtime().authoritativeTeleport(fixture.player(), fixture.level(), new Vec3(0, 80, 2));
            try (WormholesModRuntime.TeleportScope nested = fixture.runtime().beginTeleport(passenger)) {
                fixture.runtime().authoritativeTeleport(passenger, fixture.level(), new Vec3(0, 80, 2));
            }
            fixture.runtime().authoritativeTeleport(fixture.player(), fixture.level(), new Vec3(0, 80, 2));
        }
        verify(fixture.views(), never()).cancelTravel(any(ServerPlayer.class), isNull());
        verify(fixture.portals(), never()).recordTeleport(any(ServerPlayer.class));
        fixture.runtime().authoritativeTeleport(fixture.player(), fixture.level(), new Vec3(0, 80, 2));
        verify(fixture.views()).cancelTravel(fixture.player(), null);
    }

    @Test
    public void samePoseCorrectionDoesNotDiscardACapturedCrossing() throws Exception {
        Fixture fixture = fixture();
        fixture.runtime().authoritativeTeleport(fixture.player(), fixture.level(), fixture.player().position());
        verify(fixture.views(), never()).cancelTravel(any(ServerPlayer.class), isNull());
        verify(fixture.portals(), never()).recordTeleport(any(ServerPlayer.class));
    }

    private static Fixture fixture() throws Exception {
        WormholesModRuntime runtime = new WormholesModRuntime();
        MinecraftServer server = mock(MinecraftServer.class);
        when(server.isSameThread()).thenReturn(true);
        ServerLevel level = mock(ServerLevel.class);
        MinecraftPortalRegistry portals = mock(MinecraftPortalRegistry.class);
        MinecraftDoorService doors = mock(MinecraftDoorService.class);
        MinecraftClientViewService views = mock(MinecraftClientViewService.class);
        set(runtime, "server", server);
        set(runtime, "portals", portals);
        set(runtime, "doors", doors);
        set(runtime, "clientViews", views);
        ServerPlayer player = mock(ServerPlayer.class);
        when(player.getUUID()).thenReturn(UUID.randomUUID());
        when(player.level()).thenReturn(level);
        when(player.position()).thenReturn(new Vec3(0, 80, -2));
        when(player.getPassengers()).thenReturn(List.of());
        return new Fixture(runtime, level, player, portals, doors, views);
    }

    private static void set(WormholesModRuntime runtime, String name, Object value) throws Exception {
        Field field = WormholesModRuntime.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(runtime, value);
    }

    private record Fixture(WormholesModRuntime runtime, ServerLevel level, ServerPlayer player,
                           MinecraftPortalRegistry portals, MinecraftDoorService doors, MinecraftClientViewService views) {
    }
}
