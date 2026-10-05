package art.arcane.wormholes.door;

import art.arcane.volmlib.util.scheduling.FoliaScheduler;
import art.arcane.wormholes.survival.doors.dimension.PocketWorldService;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

final class DoorProjectionPreparationTest {
    @Test
    void firstViewsDeduplicateAllocationAndPublishWithoutTraversalOrTicket() throws Exception {
        for (DoorKind kind : new DoorKind[]{DoorKind.PERSONAL, DoorKind.PUBLIC}) {
            Harness harness = new Harness(kind);
            try (MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
                scheduler.when(() -> FoliaScheduler.runAsync(eq(harness.plugin), any(Runnable.class)))
                    .thenAnswer(invocation -> {
                        harness.allocation.add(invocation.getArgument(1));
                        return true;
                    });
                harness.coordinator.prepareProjection(harness.source, harness.observer);
                harness.coordinator.prepareProjection(harness.source, harness.observer);
                assertEquals(1, harness.allocation.size());
                assertTrue(harness.coordinator.preparingProjection(harness.binding));
                verify(harness.state, never()).getOrAllocatePocket(any(PocketBinding.class), any(PocketCreationDefaults.class));

                harness.allocation.getFirst().run();
                verify(harness.state).getOrAllocatePocket(harness.binding, PocketSettings.creationDefaults());
                verify(harness.structures, never()).provision(any(), any(), eq(true));
                assertEquals(1, harness.provision.size());
                assertTrue(harness.coordinator.preparingProjection(harness.binding));

                harness.provision.getFirst().run();
                verify(harness.structures).provision(harness.world, harness.space, true);
                verify(harness.state).registerEndpoint(harness.returnEndpoint);
                verify(harness.runtimes).reconcile(harness.returnRuntime);
                assertFalse(harness.coordinator.preparingProjection(harness.binding));
                verify(harness.state, never()).putReturnTicket(any());
                verifyNoInteractions(harness.ledger, harness.travelers, harness.tickets);
            }
        }
    }

    @Test
    void retiredPocketPreparationDoesNotPublishOrMutateWorld() throws Exception {
        Harness harness = new Harness(DoorKind.PERSONAL);
        try (MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            scheduler.when(() -> FoliaScheduler.runAsync(eq(harness.plugin), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    harness.allocation.add(invocation.getArgument(1));
                    return true;
                });
            harness.coordinator.prepareProjection(harness.source, harness.observer);
            harness.allocation.getFirst().run();
            when(harness.guard.closed()).thenReturn(true);
            harness.provision.getFirst().run();
            verify(harness.structures, never()).provision(any(), any(), eq(true));
            verify(harness.state, never()).registerEndpoint(any());
            assertFalse(harness.coordinator.preparingProjection(harness.binding));
        }
    }

    @Test
    void previewAllocationUsesConfiguredCreationRules() throws Exception {
        PocketCreationDefaults defaults = new PocketCreationDefaults(PocketShell.defaults(),
            new PocketRules(true, true, false, 6000L, PocketRules.BuildPolicy.OWNER));
        try (MockedStatic<PocketSettings> settings = mockStatic(PocketSettings.class);
             MockedStatic<FoliaScheduler> scheduler = mockStatic(FoliaScheduler.class)) {
            settings.when(PocketSettings::creationDefaults).thenReturn(defaults);
            Harness harness = new Harness(DoorKind.PUBLIC);
            scheduler.when(() -> FoliaScheduler.runAsync(eq(harness.plugin), any(Runnable.class)))
                .thenAnswer(invocation -> {
                    harness.allocation.add(invocation.getArgument(1));
                    return true;
                });
            harness.coordinator.prepareProjection(harness.source, harness.observer);
            harness.allocation.getFirst().run();
            verify(harness.state).getOrAllocatePocket(harness.binding, defaults);
        }
    }

    private static final class Harness {
        private final Plugin plugin = mock(Plugin.class);
        private final DoorStateGuard guard = mock(DoorStateGuard.class);
        private final DoorStateService state = mock(DoorStateService.class);
        private final DoorRuntimeIndex runtimes = mock(DoorRuntimeIndex.class);
        private final DoorTransitLedger ledger = mock(DoorTransitLedger.class);
        private final DoorTravelerService travelers = mock(DoorTravelerService.class);
        private final DoorTicketService tickets = mock(DoorTicketService.class);
        private final PocketStructureService structures = mock(PocketStructureService.class);
        private final World world = mock(World.class);
        private final UUID observer = UUID.randomUUID();
        private final List<Runnable> allocation = new ArrayList<>();
        private final List<Runnable> provision = new ArrayList<>();
        private final PlacedDoorEndpoint source;
        private final PocketBinding binding;
        private final PocketSpace space;
        private final PlacedDoorEndpoint returnEndpoint;
        private final RuntimeDoor returnRuntime;
        private final DoorTransitCoordinator coordinator;

        private Harness(DoorKind kind) throws Exception {
            UUID worldId = UUID.randomUUID();
            when(world.getUID()).thenReturn(worldId);
            when(plugin.getLogger()).thenReturn(Logger.getLogger(DoorProjectionPreparationTest.class.getName()));
            when(guard.state()).thenReturn(state);
            when(guard.acceptingEntries()).thenReturn(true);
            when(guard.mutate(any())).thenAnswer(invocation -> ((DoorStateGuard.IOCall<?>) invocation.getArgument(0)).call());
            DoorItemIdentity identity = kind == DoorKind.PERSONAL ? DoorItemIdentity.newPersonal() : DoorItemIdentity.newPublic();
            source = new PlacedDoorEndpoint(new DoorPosition(UUID.randomUUID(), "minecraft:overworld", 0, 64, 0), identity);
            binding = kind == DoorKind.PERSONAL ? PocketBinding.personal(observer) : PocketBinding.publicDoor(identity.itemId());
            when(state.resolveDestination(identity, observer)).thenReturn(new PocketDoorDestination(binding));
            when(state.findEndpointByItem(identity.itemId())).thenReturn(Optional.of(source));
            space = new PocketSpace(UUID.randomUUID(), binding, 0, 8, 80, 8, PocketShell.defaults());
            when(state.getOrAllocatePocket(binding, PocketSettings.creationDefaults())).thenReturn(space);
            when(state.findPocket(binding)).thenReturn(Optional.of(space));
            PocketLayout layout = new PocketLayout(space);
            when(structures.layout(space)).thenReturn(layout);
            PocketBlockPosition lower = layout.returnDoorLower();
            returnEndpoint = new PlacedDoorEndpoint(new DoorPosition(worldId, "wormholes:pockets", lower.x(), lower.y(), lower.z()),
                layout.returnDoorIdentity());
            returnRuntime = new RuntimeDoor(returnEndpoint);
            when(structures.provision(world, space, true)).thenReturn(returnEndpoint);
            when(runtimes.install(returnEndpoint)).thenReturn(returnRuntime);
            DoorChunkLoader chunks = mock(DoorChunkLoader.class);
            doAnswer(invocation -> {
                provision.add(invocation.getArgument(3));
                return null;
            }).when(chunks).loadPocket(eq(world), eq(space), eq(layout), any(), any());
            PocketWorldService worlds = mock(PocketWorldService.class);
            when(worlds.world()).thenReturn(Optional.of(world));
            coordinator = new DoorTransitCoordinator(plugin, guard, ledger, runtimes, chunks,
                mock(DoorChunkLoader.RegionDispatch.class), mock(DoorArrivalResolver.class), tickets, travelers,
                mock(PocketSpaceIndex.class), structures, worlds, mock(BukkitPocketTemplates.class), mock(DoorTransitFailures.class));
        }
    }
}
