package art.arcane.wormholes.access;

import art.arcane.wormholes.access.adapters.ClaimAdapter;
import art.arcane.wormholes.access.adapters.GriefPreventionAdapter;
import art.arcane.wormholes.access.adapters.LandsAdapter;
import art.arcane.wormholes.access.adapters.PlotSquaredAdapter;
import art.arcane.wormholes.access.adapters.ReflectiveEnvironment;
import art.arcane.wormholes.access.adapters.TownyAdapter;
import art.arcane.wormholes.access.adapters.WorldGuardAdapter;
import art.arcane.wormholes.access.adapters.WorldGuardFlags;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ClaimAdaptersTest {
    private static final World WORLD = AccessTestPortals.world("claims");
    private static final Player PLAYER = AccessTestPortals.player("Builder", false, Set.of());

    private static PlacementRequest request(PlacementKind kind, int[]... cells) {
        return new PlacementRequest(PLAYER.getUniqueId(), WORLD, List.of(cells), kind);
    }

    @Test
    void adaptersRunInConfigOrderAndUnknownTokensAreIgnored() {
        RecordingAdapter first = new RecordingAdapter("worldguard", true, PlacementDecision.allowedResult());
        RecordingAdapter second = new RecordingAdapter("towny", false, PlacementDecision.allowedResult());
        ClaimAdapters adapters = new ClaimAdapters(List.of(second, first));

        adapters.configure("worldguard, nonsense ,towny", true);

        assertEquals(List.of("worldguard", "towny"), adapters.enabledIds());
        assertTrue(adapters.evaluate(request(PlacementKind.CREATE, new int[] {0, 64, 0})).allowed());
        assertEquals(1, first.calls.size());
        assertEquals(1, second.calls.size());
    }

    @Test
    void chunkGranularAdaptersSeeOneRepresentativeCellPerChunk() {
        RecordingAdapter perChunk = new RecordingAdapter("towny", false, PlacementDecision.allowedResult());
        RecordingAdapter perCell = new RecordingAdapter("worldguard", true, PlacementDecision.allowedResult());
        ClaimAdapters adapters = new ClaimAdapters(List.of(perChunk, perCell));
        adapters.configure("towny,worldguard", true);

        adapters.evaluate(request(PlacementKind.CREATE,
            new int[] {0, 64, 0}, new int[] {1, 64, 0}, new int[] {15, 64, 15}, new int[] {16, 64, 0}));

        assertEquals(2, perChunk.calls.getFirst().size());
        assertEquals(4, perCell.calls.getFirst().size());
    }

    @Test
    void theFirstDeniedAdapterWinsAndLaterAdaptersAreNotConsulted() {
        RecordingAdapter denying = new RecordingAdapter("worldguard", true, PlacementDecision.deniedResult("WorldGuard"));
        RecordingAdapter later = new RecordingAdapter("towny", false, PlacementDecision.allowedResult());
        ClaimAdapters adapters = new ClaimAdapters(List.of(denying, later));
        adapters.configure("worldguard,towny", true);

        PlacementDecision decision = adapters.evaluate(request(PlacementKind.CREATE, new int[] {0, 64, 0}));

        assertEquals(PlacementDecision.Status.DENIED, decision.status());
        assertEquals("WorldGuard", decision.plugin());
        assertTrue(later.calls.isEmpty());
    }

    @Test
    void aBrokenAdapterFailsClosedWithoutMaskingTheAdaptersBehindIt() {
        RuntimeException broken = new IllegalStateException("no such method");
        RecordingAdapter failing = new RecordingAdapter("lands", false, PlacementDecision.failureResult("Lands", broken));
        RecordingAdapter later = new RecordingAdapter("towny", false, PlacementDecision.allowedResult());
        ClaimAdapters adapters = new ClaimAdapters(List.of(failing, later));
        adapters.configure("lands,towny", true);

        PlacementDecision decision = adapters.evaluate(request(PlacementKind.CREATE, new int[] {0, 64, 0}));

        assertFalse(decision.allowed());
        assertEquals(PlacementDecision.Status.FAILURE, decision.status());
        assertSame(broken, decision.failure().orElseThrow());
        assertEquals(1, later.calls.size());
    }

    @Test
    void aRefusalFromAWorkingAdapterOutranksAnEarlierBrokenOne() {
        RecordingAdapter failing = new RecordingAdapter("lands", false,
            PlacementDecision.failureResult("Lands", new IllegalStateException("no such method")));
        RecordingAdapter denying = new RecordingAdapter("towny", false, PlacementDecision.deniedResult("Towny"));
        ClaimAdapters adapters = new ClaimAdapters(List.of(failing, denying));
        adapters.configure("lands,towny", true);

        PlacementDecision decision = adapters.evaluate(request(PlacementKind.CREATE, new int[] {0, 64, 0}));

        assertEquals(PlacementDecision.Status.DENIED, decision.status());
        assertEquals("Towny", decision.plugin());
        assertEquals(1, denying.calls.size());
    }

    @Test
    void anEmptyAdapterListSkipsEveryClaimPlugin() {
        RecordingAdapter adapter = new RecordingAdapter("worldguard", true, PlacementDecision.deniedResult("WorldGuard"));
        ClaimAdapters adapters = new ClaimAdapters(List.of(adapter));

        adapters.configure("", true);

        assertEquals(List.of(), adapters.enabledIds());
        assertTrue(adapters.evaluate(request(PlacementKind.CREATE, new int[] {0, 64, 0})).allowed());
        assertTrue(adapter.calls.isEmpty());
    }

    @Test
    void disablingAPluginDropsItsCachedResolution() {
        RecordingAdapter adapter = new RecordingAdapter("worldguard", true, PlacementDecision.allowedResult());
        ClaimAdapters adapters = new ClaimAdapters(List.of(adapter));
        adapters.configure("worldguard", true);
        int afterConfigure = adapter.invalidations;

        adapters.invalidate("SomethingElse");
        assertEquals(afterConfigure, adapter.invalidations);

        adapters.invalidate("WorldGuard");
        assertEquals(afterConfigure + 1, adapter.invalidations);
    }

    @Nested
    final class WorldGuard {
        @Test
        void anAbsentPluginAllowsWithoutLoadingAnyClass() {
            TestEnvironment environment = TestEnvironment.absent();
            WorldGuardAdapter adapter = new WorldGuardAdapter(environment);

            assertTrue(adapter.evaluate(request(PlacementKind.CREATE, new int[] {0, 64, 0})).allowed());
            assertEquals(0, environment.classLoads);
        }

        @Test
        void anAllowedRegionFlagPassesEveryCell() {
            FakeWorldGuardState state = new FakeWorldGuardState(false, true);
            WorldGuardAdapter adapter = new WorldGuardAdapter(worldGuardEnvironment(state, true));

            assertTrue(adapter.evaluate(request(PlacementKind.CREATE, new int[] {0, 64, 0}, new int[] {1, 64, 0})).allowed());
            assertEquals(2, state.queries);
            assertEquals("wormholes-create", state.queriedFlag);
        }

        @Test
        void aDeniedRegionFlagRefusesOnTheFirstCell() {
            FakeWorldGuardState state = new FakeWorldGuardState(false, false);
            WorldGuardAdapter adapter = new WorldGuardAdapter(worldGuardEnvironment(state, true));

            PlacementDecision decision = adapter.evaluate(request(PlacementKind.USE, new int[] {0, 64, 0}, new int[] {1, 64, 0}));

            assertEquals(PlacementDecision.Status.DENIED, decision.status());
            assertEquals("WorldGuard", decision.plugin());
            assertEquals(1, state.queries);
            assertEquals("wormholes-use", state.queriedFlag);
        }

        @Test
        void aRegionBypassSkipsTheQueryEntirely() {
            FakeWorldGuardState state = new FakeWorldGuardState(true, false);
            WorldGuardAdapter adapter = new WorldGuardAdapter(worldGuardEnvironment(state, true));

            assertTrue(adapter.evaluate(request(PlacementKind.CREATE, new int[] {0, 64, 0})).allowed());
            assertEquals(0, state.queries);
        }

        @Test
        void withCustomFlagsOffTheBuiltInBuildFlagIsUsedWithoutTouchingTheRegistry() {
            FakeWorldGuardState state = new FakeWorldGuardState(false, true);
            WorldGuardAdapter adapter = new WorldGuardAdapter(worldGuardEnvironment(state, false));
            adapter.setCustomFlagsEnabled(false);

            assertTrue(adapter.evaluate(request(PlacementKind.CREATE, new int[] {0, 64, 0})).allowed());
            assertEquals("build", state.queriedFlag);
            assertEquals(0, state.registrations);
        }

        @Test
        void anUnregisteredCustomFlagFallsBackToTheBuiltInFlagWithoutWritingToTheRegistry() {
            FakeWorldGuardState state = new FakeWorldGuardState(false, true);
            WorldGuardAdapter adapter = new WorldGuardAdapter(worldGuardEnvironment(state, false));

            assertTrue(adapter.evaluate(request(PlacementKind.ARRIVE, new int[] {0, 64, 0})).allowed());
            assertEquals(0, state.registrations);
            assertEquals("interact", state.queriedFlag);
        }

        @Test
        void theCustomFlagsRegisterWhileWorldGuardIsStillLoading() {
            FakeWorldGuardState state = new FakeWorldGuardState(false, true);
            TestEnvironment environment = worldGuardEnvironment(state, false);
            state.frozen = false;

            assertEquals(4, WorldGuardFlags.registerAtLoad(environment));
            assertEquals(List.of("wormholes-create", "wormholes-link", "wormholes-use", "wormholes-arrive"),
                state.registeredFlags);
        }

        @Test
        void aRegisteredFlagIsReadBackAtQueryTimeAfterRegistration() {
            FakeWorldGuardState state = new FakeWorldGuardState(false, true);
            TestEnvironment environment = worldGuardEnvironment(state, false);
            state.frozen = false;
            WorldGuardFlags.registerAtLoad(environment);
            WorldGuardAdapter adapter = new WorldGuardAdapter(environment);

            assertTrue(adapter.evaluate(request(PlacementKind.LINK, new int[] {0, 64, 0})).allowed());
            assertEquals("wormholes-link", state.queriedFlag);
            assertEquals(4, state.registrations);
        }

        @Test
        void aFrozenRegistryRegistersNothingAndDoesNotThrow() {
            FakeWorldGuardState state = new FakeWorldGuardState(false, true);
            TestEnvironment environment = worldGuardEnvironment(state, false);

            assertEquals(0, WorldGuardFlags.registerAtLoad(environment));
            assertEquals(1, state.registrations);
        }

        @Test
        void absentWorldGuardRegistersNothingAndLoadsNoClass() {
            TestEnvironment environment = TestEnvironment.absent();

            assertEquals(0, WorldGuardFlags.registerAtLoad(environment));
            assertEquals(0, environment.classLoads);
        }

        @Test
        void anInstalledButDisabledPluginIsAllowedWithoutLoadingAnyClass() {
            FakeWorldGuardState state = new FakeWorldGuardState(false, false);
            TestEnvironment environment = worldGuardEnvironment(state, true);
            environment.enabled = false;
            WorldGuardAdapter adapter = new WorldGuardAdapter(environment);

            assertTrue(adapter.evaluate(request(PlacementKind.CREATE, new int[] {0, 64, 0})).allowed());
            assertEquals(0, environment.classLoads);
        }

        @Test
        void anIncompatibleApiFailsClosedWithTheOriginalException() {
            FakeWorldGuardState state = new FakeWorldGuardState(false, true);
            TestEnvironment environment = worldGuardEnvironment(state, false);
            environment.classes.put("com.sk89q.worldguard.protection.flags.Flags", FakeEmpty.class);
            WorldGuardAdapter adapter = new WorldGuardAdapter(environment);
            adapter.setCustomFlagsEnabled(false);

            PlacementDecision decision = adapter.evaluate(request(PlacementKind.CREATE, new int[] {0, 64, 0}));

            assertEquals(PlacementDecision.Status.FAILURE, decision.status());
            assertInstanceOf(NoSuchFieldException.class, decision.failure().orElseThrow());
        }

        private TestEnvironment worldGuardEnvironment(FakeWorldGuardState state, boolean flagsAlreadyRegistered) {
            FakeWorldGuard.state = state;
            FakeWorldGuardPlugin.state = state;
            state.customFlagsRegistered = flagsAlreadyRegistered;
            TestEnvironment environment = TestEnvironment.installed();
            environment.classes.put("com.sk89q.worldguard.WorldGuard", FakeWorldGuard.class);
            environment.classes.put("com.sk89q.worldguard.bukkit.WorldGuardPlugin", FakeWorldGuardPlugin.class);
            environment.classes.put("com.sk89q.worldedit.bukkit.BukkitAdapter", FakeBukkitAdapter.class);
            environment.classes.put("com.sk89q.worldguard.protection.flags.Flags", FakeWorldGuardFlags.class);
            environment.classes.put("com.sk89q.worldguard.protection.flags.StateFlag", FakeStateFlag.class);
            return environment;
        }
    }

    @Nested
    final class GriefPrevention {
        @Test
        void anAbsentPluginAllows() {
            assertTrue(new GriefPreventionAdapter(TestEnvironment.absent())
                .evaluate(request(PlacementKind.CREATE, new int[] {0, 64, 0})).allowed());
        }

        @Test
        void groundOutsideAnyClaimIsAllowed() {
            FakeGriefPreventionState state = new FakeGriefPreventionState(false, null);
            GriefPreventionAdapter adapter = new GriefPreventionAdapter(griefPreventionEnvironment(state));

            assertTrue(adapter.evaluate(request(PlacementKind.CREATE, new int[] {0, 64, 0})).allowed());
            assertEquals(1, state.lookups);
        }

        @Test
        void aClaimThatGrantsBuildIsAllowed() {
            FakeGriefPreventionState state = new FakeGriefPreventionState(true, null);
            GriefPreventionAdapter adapter = new GriefPreventionAdapter(griefPreventionEnvironment(state));

            assertTrue(adapter.evaluate(request(PlacementKind.CREATE, new int[] {0, 64, 0})).allowed());
            assertEquals("Build", state.checkedPermission);
        }

        @Test
        void aClaimThatRefusesAccessDeniesTheUseCheck() {
            FakeGriefPreventionState state = new FakeGriefPreventionState(true, "no trust here");
            GriefPreventionAdapter adapter = new GriefPreventionAdapter(griefPreventionEnvironment(state));

            PlacementDecision decision = adapter.evaluate(request(PlacementKind.USE, new int[] {0, 64, 0}));

            assertEquals(PlacementDecision.Status.DENIED, decision.status());
            assertEquals("GriefPrevention", decision.plugin());
            assertEquals("Access", state.checkedPermission);
        }

        @Test
        void anInstalledButDisabledPluginIsAllowed() {
            TestEnvironment environment = griefPreventionEnvironment(new FakeGriefPreventionState(true, "no trust here"));
            environment.enabled = false;

            assertTrue(new GriefPreventionAdapter(environment)
                .evaluate(request(PlacementKind.CREATE, new int[] {0, 64, 0})).allowed());
            assertEquals(0, environment.classLoads);
        }

        @Test
        void aNonPublicPluginClassStillYieldsItsPublicFields() {
            FakeGriefPreventionState state = new FakeGriefPreventionState(true, "no trust here");
            ObfuscatedGriefPrevention.instance = new ObfuscatedGriefPrevention(state);
            TestEnvironment environment = TestEnvironment.installed();
            environment.classes.put("me.ryanhamshire.GriefPrevention.GriefPrevention", ObfuscatedGriefPrevention.class);
            environment.classes.put("me.ryanhamshire.GriefPrevention.ClaimPermission", FakeClaimPermission.class);

            PlacementDecision decision = new GriefPreventionAdapter(environment)
                .evaluate(request(PlacementKind.CREATE, new int[] {0, 64, 0}));

            assertEquals(PlacementDecision.Status.DENIED, decision.status());
            assertEquals("Build", state.checkedPermission);
        }

        @Test
        void anIncompatibleApiFailsClosed() {
            TestEnvironment environment = griefPreventionEnvironment(new FakeGriefPreventionState(false, null));
            environment.classes.put("me.ryanhamshire.GriefPrevention.GriefPrevention", FakeEmpty.class);

            PlacementDecision decision = new GriefPreventionAdapter(environment)
                .evaluate(request(PlacementKind.CREATE, new int[] {0, 64, 0}));

            assertEquals(PlacementDecision.Status.FAILURE, decision.status());
            assertInstanceOf(NoSuchFieldException.class, decision.failure().orElseThrow());
        }

        private TestEnvironment griefPreventionEnvironment(FakeGriefPreventionState state) {
            FakeGriefPrevention.instance = new FakeGriefPrevention(state);
            TestEnvironment environment = TestEnvironment.installed();
            environment.classes.put("me.ryanhamshire.GriefPrevention.GriefPrevention", FakeGriefPrevention.class);
            environment.classes.put("me.ryanhamshire.GriefPrevention.ClaimPermission", FakeClaimPermission.class);
            return environment;
        }
    }

    @Nested
    final class Towny {
        @Test
        void anAbsentPluginAllows() {
            assertTrue(new TownyAdapter(TestEnvironment.absent())
                .evaluate(request(PlacementKind.CREATE, new int[] {0, 64, 0})).allowed());
        }

        @Test
        void aPermittedBuildActionIsAllowedOncePerChunk() {
            FakeTownyState state = new FakeTownyState(true);
            TownyAdapter adapter = new TownyAdapter(townyEnvironment(state));

            assertTrue(adapter.evaluate(request(PlacementKind.CREATE, new int[] {0, 64, 0}, new int[] {1, 64, 0})).allowed());
            assertEquals(2, state.checks);
            assertEquals("BUILD", state.action);
        }

        @Test
        void aRefusedActionDenies() {
            FakeTownyState state = new FakeTownyState(false);
            TownyAdapter adapter = new TownyAdapter(townyEnvironment(state));

            PlacementDecision decision = adapter.evaluate(request(PlacementKind.ARRIVE, new int[] {0, 64, 0}));

            assertEquals(PlacementDecision.Status.DENIED, decision.status());
            assertEquals("Towny", decision.plugin());
            assertEquals("SWITCH", state.action);
        }

        @Test
        void anInstalledButDisabledPluginIsAllowed() {
            TestEnvironment environment = townyEnvironment(new FakeTownyState(false));
            environment.enabled = false;

            assertTrue(new TownyAdapter(environment)
                .evaluate(request(PlacementKind.CREATE, new int[] {0, 64, 0})).allowed());
            assertEquals(0, environment.classLoads);
        }

        @Test
        void anIncompatibleApiFailsClosed() {
            TestEnvironment environment = townyEnvironment(new FakeTownyState(true));
            environment.classes.put("com.palmergames.bukkit.towny.utils.PlayerCacheUtil", FakeEmpty.class);

            PlacementDecision decision = new TownyAdapter(environment)
                .evaluate(request(PlacementKind.CREATE, new int[] {0, 64, 0}));

            assertEquals(PlacementDecision.Status.FAILURE, decision.status());
            assertInstanceOf(NoSuchMethodException.class, decision.failure().orElseThrow());
        }

        private TestEnvironment townyEnvironment(FakeTownyState state) {
            FakePlayerCacheUtil.state = state;
            TestEnvironment environment = TestEnvironment.installed();
            environment.classes.put("com.palmergames.bukkit.towny.utils.PlayerCacheUtil", FakePlayerCacheUtil.class);
            environment.classes.put("com.palmergames.bukkit.towny.object.TownyPermission$ActionType", FakeActionType.class);
            return environment;
        }
    }

    @Nested
    final class Lands {
        @Test
        void anAbsentPluginAllows() {
            assertTrue(new LandsAdapter(TestEnvironment.absent())
                .evaluate(request(PlacementKind.CREATE, new int[] {0, 64, 0})).allowed());
        }

        @Test
        void unclaimedWildernessIsAllowed() {
            FakeLandsState state = new FakeLandsState(false, false);
            LandsAdapter adapter = new LandsAdapter(landsEnvironment(state));

            assertTrue(adapter.evaluate(request(PlacementKind.CREATE, new int[] {0, 64, 0})).allowed());
            assertEquals(1, state.areaLookups);
        }

        @Test
        void aLandThatGrantsTheRoleFlagIsAllowed() {
            FakeLandsState state = new FakeLandsState(true, true);
            LandsAdapter adapter = new LandsAdapter(landsEnvironment(state));

            assertTrue(adapter.evaluate(request(PlacementKind.CREATE, new int[] {0, 64, 0})).allowed());
            assertEquals("BLOCK_PLACE", state.checkedFlag);
        }

        @Test
        void aLandThatWithholdsTheRoleFlagDenies() {
            FakeLandsState state = new FakeLandsState(true, false);
            LandsAdapter adapter = new LandsAdapter(landsEnvironment(state));

            PlacementDecision decision = adapter.evaluate(request(PlacementKind.USE, new int[] {0, 64, 0}));

            assertEquals(PlacementDecision.Status.DENIED, decision.status());
            assertEquals("Lands", decision.plugin());
            assertEquals("INTERACT_GENERAL", state.checkedFlag);
        }

        @Test
        void anInstalledButDisabledPluginIsAllowed() {
            TestEnvironment environment = landsEnvironment(new FakeLandsState(true, false));
            environment.enabled = false;

            assertTrue(new LandsAdapter(environment)
                .evaluate(request(PlacementKind.CREATE, new int[] {0, 64, 0})).allowed());
            assertEquals(0, environment.classLoads);
        }

        @Test
        void anObfuscatedNonPublicImplementationIsStillInvokable() {
            FakeLandsState state = new FakeLandsState(true, false);
            ObfuscatedLandsIntegration.state = state;
            TestEnvironment environment = TestEnvironment.installed();
            environment.classes.put("me.angeschossen.lands.api.LandsIntegration", ObfuscatedLandsIntegration.class);
            environment.classes.put("me.angeschossen.lands.api.flags.type.Flags", ObfuscatedLandsFlags.class);

            PlacementDecision decision = new LandsAdapter(environment)
                .evaluate(request(PlacementKind.CREATE, new int[] {0, 64, 0}));

            assertEquals(PlacementDecision.Status.DENIED, decision.status());
            assertEquals("Lands", decision.plugin());
            assertEquals("BLOCK_PLACE", state.checkedFlag);
        }

        @Test
        void anIncompatibleApiFailsClosed() {
            TestEnvironment environment = landsEnvironment(new FakeLandsState(true, true));
            environment.classes.put("me.angeschossen.lands.api.flags.type.Flags", FakeEmpty.class);

            PlacementDecision decision = new LandsAdapter(environment)
                .evaluate(request(PlacementKind.CREATE, new int[] {0, 64, 0}));

            assertEquals(PlacementDecision.Status.FAILURE, decision.status());
            assertInstanceOf(NoSuchFieldException.class, decision.failure().orElseThrow());
        }

        private TestEnvironment landsEnvironment(FakeLandsState state) {
            FakeLandsIntegration.state = state;
            TestEnvironment environment = TestEnvironment.installed();
            environment.classes.put("me.angeschossen.lands.api.LandsIntegration", FakeLandsIntegration.class);
            environment.classes.put("me.angeschossen.lands.api.flags.type.Flags", FakeLandsFlags.class);
            return environment;
        }
    }

    @Nested
    final class PlotSquared {
        @Test
        void anAbsentPluginAllows() {
            assertTrue(new PlotSquaredAdapter(TestEnvironment.absent())
                .evaluate(request(PlacementKind.CREATE, new int[] {0, 64, 0})).allowed());
        }

        @Test
        void groundOutsideAnyPlotAreaIsAllowed() {
            FakePlotSquaredState state = new FakePlotSquaredState(false, false, false);
            PlotSquaredAdapter adapter = new PlotSquaredAdapter(plotSquaredEnvironment(state));

            assertTrue(adapter.evaluate(request(PlacementKind.CREATE, new int[] {0, 64, 0})).allowed());
            assertEquals(1, state.areaLookups);
        }

        @Test
        void aPlotTheBuilderIsAddedToIsAllowed() {
            FakePlotSquaredState state = new FakePlotSquaredState(true, true, true);
            PlotSquaredAdapter adapter = new PlotSquaredAdapter(plotSquaredEnvironment(state));

            assertTrue(adapter.evaluate(request(PlacementKind.CREATE, new int[] {0, 64, 0}, new int[] {1, 64, 0})).allowed());
            assertEquals(2, state.plotLookups);
        }

        @Test
        void unclaimedPlotSpaceInsideAPlotAreaIsAllowed() {
            FakePlotSquaredState state = new FakePlotSquaredState(true, true, false);
            state.owned = false;
            PlotSquaredAdapter adapter = new PlotSquaredAdapter(plotSquaredEnvironment(state));

            assertTrue(adapter.evaluate(request(PlacementKind.CREATE, new int[] {0, 64, 0})).allowed());
            assertEquals(1, state.plotLookups);
        }

        @Test
        void someoneElsesPlotDenies() {
            FakePlotSquaredState state = new FakePlotSquaredState(true, true, false);
            PlotSquaredAdapter adapter = new PlotSquaredAdapter(plotSquaredEnvironment(state));

            PlacementDecision decision = adapter.evaluate(request(PlacementKind.CREATE, new int[] {0, 64, 0}));

            assertEquals(PlacementDecision.Status.DENIED, decision.status());
            assertEquals("PlotSquared", decision.plugin());
        }

        @Test
        void anInstalledButDisabledPluginIsAllowed() {
            TestEnvironment environment = plotSquaredEnvironment(new FakePlotSquaredState(true, true, false));
            environment.enabled = false;

            assertTrue(new PlotSquaredAdapter(environment)
                .evaluate(request(PlacementKind.CREATE, new int[] {0, 64, 0})).allowed());
            assertEquals(0, environment.classLoads);
        }

        @Test
        void anIncompatibleApiFailsClosed() {
            TestEnvironment environment = plotSquaredEnvironment(new FakePlotSquaredState(true, true, true));
            environment.classes.put("com.plotsquared.core.PlotSquared", FakeEmpty.class);

            PlacementDecision decision = new PlotSquaredAdapter(environment)
                .evaluate(request(PlacementKind.CREATE, new int[] {0, 64, 0}));

            assertEquals(PlacementDecision.Status.FAILURE, decision.status());
            assertInstanceOf(NoSuchMethodException.class, decision.failure().orElseThrow());
        }

        private TestEnvironment plotSquaredEnvironment(FakePlotSquaredState state) {
            FakePlotSquared.state = state;
            TestEnvironment environment = TestEnvironment.installed();
            environment.classes.put("com.plotsquared.core.PlotSquared", FakePlotSquared.class);
            environment.classes.put("com.plotsquared.core.location.Location", FakeP2Location.class);
            return environment;
        }
    }

    private static final class RecordingAdapter implements ClaimAdapter {
        private final String id;
        private final boolean perCell;
        private final PlacementDecision decision;
        private final List<List<int[]>> calls = new ArrayList<>();
        private int invalidations;

        private RecordingAdapter(String id, boolean perCell, PlacementDecision decision) {
            this.id = id;
            this.perCell = perCell;
            this.decision = decision;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public String pluginName() {
            return switch (id) {
                case "worldguard" -> "WorldGuard";
                case "towny" -> "Towny";
                default -> "Lands";
            };
        }

        @Override
        public boolean perCell() {
            return perCell;
        }

        @Override
        public void invalidate() {
            invalidations++;
        }

        @Override
        public PlacementDecision evaluate(PlacementRequest request) {
            calls.add(request.cells());
            return decision;
        }
    }

    static final class TestEnvironment implements ReflectiveEnvironment {
        private final Object plugin;
        final Map<String, Class<?>> classes = new HashMap<>();
        boolean enabled = true;
        int classLoads;

        private TestEnvironment(Object plugin) {
            this.plugin = plugin;
        }

        static TestEnvironment absent() {
            return new TestEnvironment(null);
        }

        static TestEnvironment installed() {
            return new TestEnvironment(new Object());
        }

        @Override
        public Object findPlugin(String name) {
            return plugin;
        }

        @Override
        public boolean isPluginEnabled(Object candidate) {
            return enabled;
        }

        @Override
        public Object hostPlugin() {
            return this;
        }

        @Override
        public Player resolvePlayer(UUID playerId) {
            return PLAYER.getUniqueId().equals(playerId) ? PLAYER : null;
        }

        @Override
        public Class<?> loadClass(Object candidate, String className) throws ClassNotFoundException {
            classLoads++;
            Class<?> resolved = classes.get(className);
            if (resolved == null) {
                throw new ClassNotFoundException(className);
            }
            return resolved;
        }
    }

    public static final class FakeEmpty {
    }

    static final class FakeWorldGuardState {
        private final boolean bypass;
        private final boolean flagAllows;
        final List<String> registeredFlags = new ArrayList<>();
        boolean customFlagsRegistered;
        boolean frozen = true;
        int queries;
        int registrations;
        String queriedFlag;

        FakeWorldGuardState(boolean bypass, boolean flagAllows) {
            this.bypass = bypass;
            this.flagAllows = flagAllows;
        }
    }

    public static final class FakeWorldGuard {
        static FakeWorldGuardState state;

        public static FakeWorldGuard getInstance() {
            return new FakeWorldGuard();
        }

        public FakePlatform getPlatform() {
            return new FakePlatform();
        }

        public FakeFlagRegistry getFlagRegistry() {
            return new FakeFlagRegistry();
        }
    }

    public static final class FakeFlagRegistry {
        public Object get(String name) {
            FakeWorldGuardState state = FakeWorldGuard.state;
            return state.customFlagsRegistered || state.registeredFlags.contains(name)
                ? new FakeStateFlag(name, true) : null;
        }

        /** SimpleFlagRegistry.register throws once WorldGuard has finished enabling. */
        public void register(Object flag) {
            FakeWorldGuardState state = FakeWorldGuard.state;
            state.registrations++;
            if (state.frozen) {
                throw new IllegalStateException("New flags cannot be registered at this time");
            }
            state.registeredFlags.add(((FakeStateFlag) flag).name);
        }
    }

    public static final class FakePlatform {
        public FakeSessionManager getSessionManager() {
            return new FakeSessionManager();
        }

        public FakeRegionContainer getRegionContainer() {
            return new FakeRegionContainer();
        }
    }

    public static final class FakeSessionManager {
        public boolean hasBypass(Object localPlayer, Object world) {
            return FakeWorldGuard.state.bypass;
        }
    }

    public static final class FakeRegionContainer {
        public FakeRegionQuery createQuery() {
            return new FakeRegionQuery();
        }
    }

    public static final class FakeRegionQuery {
        public boolean testState(Object location, Object localPlayer, FakeStateFlag... flags) {
            FakeWorldGuardState state = FakeWorldGuard.state;
            state.queries++;
            state.queriedFlag = flags[0].name;
            return state.flagAllows;
        }
    }

    public static final class FakeWorldGuardPlugin {
        static FakeWorldGuardState state;

        public static FakeWorldGuardPlugin inst() {
            return new FakeWorldGuardPlugin();
        }

        public Object wrapPlayer(Player player) {
            return new Object();
        }
    }

    public static final class FakeBukkitAdapter {
        public static Object adapt(World world) {
            return new Object();
        }

        public static Object adapt(Location location) {
            return location;
        }
    }

    public static final class FakeWorldGuardFlags {
        public static final FakeStateFlag BUILD = new FakeStateFlag("build", true);
        public static final FakeStateFlag INTERACT = new FakeStateFlag("interact", true);
    }

    public static final class FakeStateFlag {
        final String name;

        public FakeStateFlag(String name, boolean defaultValue) {
            this.name = name;
        }
    }

    static final class FakeGriefPreventionState {
        private final boolean claimed;
        private final String refusal;
        int lookups;
        String checkedPermission;

        FakeGriefPreventionState(boolean claimed, String refusal) {
            this.claimed = claimed;
            this.refusal = refusal;
        }
    }

    public static final class FakeGriefPrevention {
        public static FakeGriefPrevention instance;

        public final FakeDataStore dataStore;
        private final FakeGriefPreventionState state;

        FakeGriefPrevention(FakeGriefPreventionState state) {
            this.state = state;
            this.dataStore = new FakeDataStore(state);
        }
    }

    public static final class FakeDataStore {
        private final FakeGriefPreventionState state;

        FakeDataStore(FakeGriefPreventionState state) {
            this.state = state;
        }

        public FakeClaim getClaimAt(Location location, boolean ignoreHeight, Object cached) {
            state.lookups++;
            return state.claimed ? new FakeClaim(state) : null;
        }
    }

    static final class ObfuscatedGriefPrevention {
        public static ObfuscatedGriefPrevention instance;

        public final FakeDataStore dataStore;

        ObfuscatedGriefPrevention(FakeGriefPreventionState state) {
            this.dataStore = new FakeDataStore(state);
        }
    }

    public static final class FakeClaim {
        private final FakeGriefPreventionState state;

        FakeClaim(FakeGriefPreventionState state) {
            this.state = state;
        }

        public Object checkPermission(Player player, Object permission, Object event) {
            state.checkedPermission = String.valueOf(permission);
            return state.refusal;
        }
    }

    public enum FakeClaimPermission {
        Build,
        Access
    }

    static final class FakeTownyState {
        private final boolean permitted;
        int checks;
        String action;

        FakeTownyState(boolean permitted) {
            this.permitted = permitted;
        }
    }

    public static final class FakePlayerCacheUtil {
        static FakeTownyState state;

        public static boolean getCachePermission(Player player, Location location, org.bukkit.Material material, Object action) {
            state.checks++;
            state.action = String.valueOf(action);
            return state.permitted;
        }
    }

    public enum FakeActionType {
        BUILD,
        SWITCH
    }

    static final class FakeLandsState {
        private final boolean claimed;
        private final boolean flagGranted;
        int areaLookups;
        String checkedFlag;

        FakeLandsState(boolean claimed, boolean flagGranted) {
            this.claimed = claimed;
            this.flagGranted = flagGranted;
        }
    }

    public static final class FakeLandsIntegration {
        static FakeLandsState state;

        public static FakeLandsIntegration of(Object plugin) {
            return new FakeLandsIntegration();
        }

        public FakeArea getArea(Location location) {
            state.areaLookups++;
            return state.claimed ? new FakeArea() : null;
        }
    }

    public static final class FakeArea {
        public boolean hasRoleFlag(UUID playerId, Object flag) {
            FakeLandsIntegration.state.checkedFlag = String.valueOf(flag);
            return FakeLandsIntegration.state.flagGranted;
        }
    }

    static final class ObfuscatedLandsIntegration {
        static FakeLandsState state;

        public static ObfuscatedLandsIntegration of(Object plugin) {
            return new ObfuscatedLandsIntegration();
        }

        public ObfuscatedArea getArea(Location location) {
            state.areaLookups++;
            return state.claimed ? new ObfuscatedArea() : null;
        }
    }

    static final class ObfuscatedArea {
        public boolean hasRoleFlag(UUID playerId, Object flag) {
            ObfuscatedLandsIntegration.state.checkedFlag = String.valueOf(flag);
            return ObfuscatedLandsIntegration.state.flagGranted;
        }
    }

    static final class ObfuscatedLandsFlags {
        public static final String BLOCK_PLACE = "BLOCK_PLACE";
        public static final String INTERACT_GENERAL = "INTERACT_GENERAL";
    }

    public static final class FakeLandsFlags {
        public static final String BLOCK_PLACE = "BLOCK_PLACE";
        public static final String INTERACT_GENERAL = "INTERACT_GENERAL";
    }

    static final class FakePlotSquaredState {
        private final boolean inArea;
        private final boolean plotted;
        private final boolean added;
        boolean owned = true;
        int areaLookups;
        int plotLookups;

        FakePlotSquaredState(boolean inArea, boolean plotted, boolean added) {
            this.inArea = inArea;
            this.plotted = plotted;
            this.added = added;
        }
    }

    public static final class FakePlotSquared {
        static FakePlotSquaredState state;

        public static FakePlotSquared get() {
            return new FakePlotSquared();
        }

        public FakePlotAreaManager getPlotAreaManager() {
            return new FakePlotAreaManager();
        }
    }

    public static final class FakePlotAreaManager {
        public FakePlotArea getApplicablePlotArea(Object location) {
            FakePlotSquared.state.areaLookups++;
            return FakePlotSquared.state.inArea ? new FakePlotArea() : null;
        }
    }

    public static final class FakePlotArea {
        /** Unclaimed plot space yields a synthetic unowned plot rather than null, as PlotArea does. */
        public FakePlot getPlot(Object location) {
            FakePlotSquared.state.plotLookups++;
            return FakePlotSquared.state.plotted ? new FakePlot() : null;
        }
    }

    public static final class FakePlot {
        public boolean hasOwner() {
            return FakePlotSquared.state.owned;
        }

        public boolean isAdded(UUID playerId) {
            return FakePlotSquared.state.owned && FakePlotSquared.state.added;
        }
    }

    public static final class FakeP2Location {
        public static FakeP2Location at(String world, int x, int y, int z) {
            return new FakeP2Location();
        }
    }
}
