package art.arcane.wormholes.modded;

import art.arcane.wormholes.door.PocketBinding;
import art.arcane.wormholes.door.DoorStateService;
import art.arcane.wormholes.door.PocketLayout;
import art.arcane.wormholes.door.PocketShell;
import art.arcane.wormholes.door.PocketSpace;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.clock.ClockNetworkState;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.clock.WorldClock;
import net.minecraft.world.clock.WorldClocks;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.dimension.DimensionType;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftPocketRulesTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void fixedTimeOverridesOnlyPocketClockAndRestoresWorldPacketOnExit() {
        Fixture fixture = new Fixture();
        HolderOwner<WorldClock> owner = new HolderOwner<WorldClock>() { };
        Holder<WorldClock> pocketClock = Holder.Reference.createStandAlone(owner, WorldClocks.THE_END);
        Holder<WorldClock> otherClock = Holder.Reference.createStandAlone(owner, WorldClocks.OVERWORLD);
        DimensionType dimension = mock(DimensionType.class);
        when(dimension.defaultClock()).thenReturn(Optional.of(pocketClock));
        when(fixture.level.dimensionType()).thenReturn(dimension);
        when(fixture.player.isSpectator()).thenReturn(true);
        ClientboundSetTimePacket actual = new ClientboundSetTimePacket(123L,
            Map.of(pocketClock, new ClockNetworkState(9000, 0.5F, 1), otherClock, new ClockNetworkState(2000, 0, 1)));
        ServerClockManager manager = mock(ServerClockManager.class);
        when(fixture.server.clockManager()).thenReturn(manager);
        when(manager.createFullSyncPacket()).thenReturn(actual);
        PocketSpace fixed = fixture.space.withRules(fixture.space.rules().withFixedTime(6000));
        when(fixture.doors.spaceAt(fixture.level, fixture.position)).thenReturn(fixed);
        fixture.rules.tick();
        ClientboundSetTimePacket overridden = fixture.rules.clockPacket(fixture.player.getUUID(), actual);
        assertEquals(123L, overridden.gameTime());
        assertEquals(new ClockNetworkState(6000, 0, 0), overridden.clockUpdates().get(pocketClock));
        assertEquals(actual.clockUpdates().get(otherClock), overridden.clockUpdates().get(otherClock));
        fixture.rules.tick();
        verify(fixture.player.connection, times(1)).send(any(ClientboundSetTimePacket.class));
        when(fixture.doors.spaceAt(fixture.level, fixture.position)).thenReturn(null);
        fixture.rules.tick();
        assertSame(actual, fixture.rules.clockPacket(fixture.player.getUUID(), actual));
        verify(fixture.player.connection).send(actual);
        fixture.rules.close();
    }

    @Test
    public void nativeSpawnDamageAndInventoryUseCurrentPocketRules() {
        Fixture fixture = new Fixture();
        Mob mob = mock(Mob.class);
        when(mob.blockPosition()).thenReturn(fixture.position);
        DamageSource damage = mock(DamageSource.class);
        ServerPlayer attacker = mock(ServerPlayer.class);
        when(damage.getEntity()).thenReturn(attacker);
        assertTrue(fixture.rules.denySpawn(fixture.level, mob));
        assertTrue(fixture.rules.denyPvp(fixture.player, damage));
        assertTrue(fixture.rules.keepInventory(fixture.player));
        PocketSpace changed = fixture.space.withRules(fixture.space.rules().withMobs(true).withPvp(true).withKeepInventory(false));
        when(fixture.doors.spaceAt(fixture.level, fixture.position)).thenReturn(changed);
        assertFalse(fixture.rules.denySpawn(fixture.level, mob));
        assertFalse(fixture.rules.denyPvp(fixture.player, damage));
        assertFalse(fixture.rules.keepInventory(fixture.player));
        fixture.rules.close();
    }

    @Test
    public void retentionUsesDeathRuleEvenIfPocketChangesBeforeRespawn() {
        Fixture fixture = new Fixture();
        fixture.rules.recordDeath(fixture.player);
        when(fixture.doors.spaceAt(fixture.level, fixture.position)).thenReturn(fixture.space.withRules(fixture.space.rules().withKeepInventory(false)));
        assertTrue(fixture.rules.keepInventory(fixture.player));
        assertTrue(fixture.rules.restoreInventory(fixture.player));
        assertFalse(fixture.rules.keepInventory(fixture.player));
        fixture.rules.close();
    }

    @Test
    public void lethalDamageHoldsOneHeartWithoutTeleportingInsideDamageCallback() {
        Fixture fixture = new Fixture();
        when(fixture.player.getHealth()).thenReturn(20F);
        when(fixture.player.getMaxHealth()).thenReturn(20F);
        when(fixture.player.isAlive()).thenReturn(true);
        DoorStateService state = mock(DoorStateService.class);
        when(fixture.doors.state()).thenReturn(state);
        when(state.getReturnTicket(fixture.player.getUUID())).thenReturn(Optional.empty());
        when(fixture.runtime.schedule(any(Runnable.class), eq(1L))).thenReturn(true);
        assertFalse(fixture.rules.retainHealth(fixture.player, 4F));
        assertTrue(fixture.rules.retainHealth(fixture.player, -4F));
        verify(fixture.player).setHealth(2F);
        verify(fixture.player).clearFire();
        assertEquals(40, fixture.player.invulnerableTime);
        assertTrue(fixture.rules.rescuing(fixture.player.getUUID()));
        verify(fixture.runtime).schedule(any(Runnable.class), eq(1L));
        fixture.rules.close();
    }

    private static final class Fixture {
        private final WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        private final MinecraftDoorService doors = mock(MinecraftDoorService.class);
        private final MinecraftServer server = mock(MinecraftServer.class);
        private final ServerLevel level = mock(ServerLevel.class);
        private final ServerPlayer player = mock(ServerPlayer.class);
        private final PocketSpace space = new PocketSpace(UUID.randomUUID(), PocketBinding.personal(UUID.randomUUID()),
            0, 8, 128, 8, PocketShell.defaults());
        private final BlockPos position = BlockPos.containing(new PocketLayout(space).entry().x(),
            new PocketLayout(space).entry().y(), new PocketLayout(space).entry().z());
        private final MinecraftPocketRules rules;

        private Fixture() {
            when(runtime.server()).thenReturn(server);
            when(server.getAllLevels()).thenReturn(List.of(level));
            when(level.players()).thenReturn(List.of(player));
            when(level.dimension()).thenReturn(ResourceKey.create(Registries.DIMENSION, Identifier.fromNamespaceAndPath("wormholes", "pockets")));
            when(player.getUUID()).thenReturn(UUID.randomUUID());
            when(player.level()).thenReturn(level);
            when(player.blockPosition()).thenReturn(position);
            player.connection = mock(ServerGamePacketListenerImpl.class);
            when(doors.spaceAt(level, position)).thenReturn(space);
            rules = new MinecraftPocketRules(runtime, doors);
        }
    }
}
