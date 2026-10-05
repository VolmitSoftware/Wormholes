package art.arcane.wormholes.modded;

import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.chunk.ChunkLease;
import art.arcane.wormholes.chunk.ChunkLeaseRegistry;
import art.arcane.wormholes.door.DoorAccessState;
import art.arcane.wormholes.door.DoorItemIdentity;
import art.arcane.wormholes.door.DoorPosition;
import art.arcane.wormholes.door.DoorwayPlane;
import art.arcane.wormholes.door.PlacedDoorEndpoint;
import art.arcane.wormholes.door.PocketBinding;
import art.arcane.wormholes.door.PocketLayout;
import art.arcane.wormholes.door.PocketSpace;
import art.arcane.wormholes.door.PocketCreationDefaults;
import art.arcane.wormholes.door.PocketRules;
import art.arcane.wormholes.door.DoorVec3;
import art.arcane.wormholes.door.ReturnTicket;
import art.arcane.wormholes.util.Direction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftPocketPreviewTest extends MinecraftTestBase {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void firstPersonalAndPublicPreviewsPrepareWithoutTicketsOrTraversal() throws Exception {
        for (boolean personal : new boolean[] {true, false}) {
            try (Fixture fixture = new Fixture(temporary.newFolder().toPath())) {
                MinecraftDoorService.DoorView source = fixture.source(personal);
                UUID first = fixture.observer();
                UUID second = fixture.observer();
                assertTrue(fixture.doors.projectionDestination(source, first).isEmpty());
                assertTrue(fixture.doors.projectionDestination(source, first).isEmpty());
                fixture.await(() -> fixture.doors.projectionDestination(source, first).isPresent());
                assertEquals(1, fixture.prepared.size());
                MinecraftDoorService.ProjectionDestination firstView = fixture.doors.projectionDestination(source, first).orElseThrow();
                PocketSpace space = fixture.doors.state().spaces().getFirst();
                assertEquals(personal ? PocketBinding.personal(first) : PocketBinding.publicDoor(source.endpoint().identity().itemId()), space.binding());
                assertEquals(new PocketLayout(space).returnDoorIdentity().itemId(), firstView.id());
                assertTrue(fixture.doors.state().returnTickets().isEmpty());
                verify(fixture.prepared.getFirst()).close();
                fixture.await(() -> fixture.doors.projectionDestination(source, second).isPresent());
                assertEquals(personal ? 2 : 1, fixture.doors.state().spaces().size());
                assertEquals(personal ? 2 : 1, fixture.prepared.size());
                assertTrue(fixture.doors.state().returnTickets().isEmpty());
            }
        }
    }

    @Test
    public void previewCreatesConfiguredRulesAndLaterEntryKeepsThem() throws Exception {
        try (Fixture fixture = new Fixture(temporary.newFolder().toPath())) {
            fixture.settings.getPockets().rulesDefaultMobs = true;
            fixture.settings.getPockets().rulesDefaultPvp = true;
            fixture.settings.getPockets().rulesDefaultKeepInventory = false;
            fixture.settings.getPockets().rulesDefaultFixedTime = 6000L;
            fixture.settings.getPockets().rulesDefaultBuild = "owner";
            MinecraftDoorService.DoorView source = fixture.source(false);
            UUID observer = fixture.observer();
            fixture.await(() -> fixture.doors.projectionDestination(source, observer).isPresent());
            PocketBinding binding = PocketBinding.publicDoor(source.endpoint().identity().itemId());
            PocketSpace preview = fixture.doors.state().findPocket(binding).orElseThrow();
            assertEquals(new PocketRules(true, true, false, 6000L, PocketRules.BuildPolicy.OWNER), preview.rules());
            fixture.settings.getPockets().rulesDefaultMobs = false;
            fixture.settings.getPockets().rulesDefaultPvp = false;
            fixture.settings.getPockets().rulesDefaultKeepInventory = true;
            fixture.settings.getPockets().rulesDefaultFixedTime = -1L;
            fixture.settings.getPockets().rulesDefaultBuild = "builders";
            PocketSpace entry = fixture.doors.state().getOrAllocatePocket(binding,
                PocketCreationDefaults.from(preview.shell(), fixture.settings.getPockets()));
            assertEquals(preview, entry);
        }
    }

    @Test
    public void instancedPublicPreviewUsesObserverBindingWithoutMarkingItOccupied() throws Exception {
        try (Fixture fixture = new Fixture(temporary.newFolder().toPath())) {
            MinecraftDoorService.DoorView source = fixture.source(false);
            PocketSpace shared = fixture.doors.state().getOrAllocatePocket(PocketBinding.publicDoor(source.endpoint().identity().itemId()), PocketCreationDefaults.defaults());
            fixture.doors.state().replacePocket(shared.withTemplateName("test-room"));
            fixture.doors.state().attachInstances(name -> name.equals("test-room"));
            UUID observer = fixture.observer();
            fixture.await(() -> fixture.doors.projectionDestination(source, observer).isPresent());
            PocketSpace instance = fixture.doors.state().findPocket(PocketBinding.instance("test-room", observer)).orElseThrow();
            assertEquals("test-room", instance.templateName());
            assertEquals(0L, instance.instance().lastOccupiedMillis());
            assertFalse(instance.instance().usedSinceReset());
            assertTrue(fixture.doors.state().returnTickets().isEmpty());
            assertEquals(new PocketLayout(instance).returnDoorIdentity().itemId(),
                fixture.doors.projectionDestination(source, observer).orElseThrow().id());
        }
    }

    @Test
    public void previewAndTraversalShareOnePreparationAndShutdownClosesItsLease() throws Exception {
        Fixture fixture = new Fixture(temporary.newFolder().toPath());
        CompletableFuture<MinecraftPocketRooms.Prepared> pending = new CompletableFuture<>();
        doReturn(pending).when(fixture.rooms).prepare(any());
        MinecraftDoorService.DoorView source = fixture.source(true);
        UUID observer = fixture.observer();
        fixture.doors.projectionDestination(source, observer);
        fixture.await(() -> fixture.doors.state().spaces().size() == 1);
        PocketSpace space = fixture.doors.state().spaces().getFirst();
        Method prepare = MinecraftDoorService.class.getDeclaredMethod("preparePocket", PocketSpace.class);
        prepare.setAccessible(true);
        CompletableFuture<?> first = (CompletableFuture<?>) prepare.invoke(fixture.doors, space);
        CompletableFuture<?> second = (CompletableFuture<?>) prepare.invoke(fixture.doors, space);
        assertSame(first, second);
        fixture.pump();
        verify(fixture.rooms).prepare(space);
        fixture.doors.close();
        assertTrue(first.isCompletedExceptionally());
        MinecraftPocketRooms.Prepared room = fixture.prepared(space);
        pending.complete(room);
        fixture.pump();
        verify(room).close();
        verify(fixture.operations, never()).furnish(any());
    }

    @Test
    public void accessDenialAndDoorInUnallocatedPocketWorldDoNotAllocate() throws Exception {
        try (Fixture fixture = new Fixture(temporary.newFolder().toPath())) {
            MinecraftDoorService.DoorView source = fixture.source(true);
            UUID observer = fixture.observer();
            assertTrue(fixture.doors.state().addAccessPlayer(source.endpoint().identity().itemId(), observer));
            assertTrue(fixture.doors.state().setAccessState(source.endpoint().identity().itemId(), observer, DoorAccessState.BLACKLIST));
            assertTrue(fixture.doors.projectionDestination(source, observer).isEmpty());
            UUID allowed = fixture.observer();
            MinecraftDoorService.DoorView inside = new MinecraftDoorService.DoorView(source.endpoint(), fixture.pocketLevel, source.plane(), true);
            assertTrue(fixture.doors.projectionDestination(inside, allowed).isEmpty());
            fixture.pump();
            assertTrue(fixture.doors.state().spaces().isEmpty());
            verify(fixture.rooms, never()).prepare(any());
        }
    }

    @Test
    public void returnPreviewUsesRelocatedCurrentSourcePlaneFromInsidePocket() throws Exception {
        try (Fixture fixture = new Fixture(temporary.newFolder().toPath())) {
            MinecraftDoorService.DoorView source = fixture.source(true);
            UUID observer = fixture.observer();
            ReturnTicket ticket = new ReturnTicket(observer, source.endpoint().identity().itemId(), source.endpoint().position().worldId(),
                "minecraft:overworld", -200, 10, -300, 0, 0);
            fixture.doors.state().putReturnTicket(ticket);
            PlacedDoorEndpoint moved = new PlacedDoorEndpoint(new DoorPosition(source.endpoint().position().worldId(),
                "minecraft:overworld", 80, 72, 90), source.endpoint().identity());
            fixture.doors.state().relocateEndpoint(source.endpoint(), moved);
            PlacedDoorEndpoint exit = new PlacedDoorEndpoint(new DoorPosition(UUID.randomUUID(), "wormholes:pockets", 0, 64, 0),
                DoorItemIdentity.newReturn(UUID.randomUUID()));
            MinecraftDoorService.DoorView returnView = new MinecraftDoorService.DoorView(exit, fixture.pocketLevel,
                new DoorwayPlane(0, 64, 0, Direction.S), true);
            MinecraftDoorService.ProjectionDestination destination = fixture.doors.projectionDestination(returnView, observer).orElseThrow();
            DoorVec3 current = new DoorwayPlane(80, 72, 90, Direction.N).center();
            assertEquals(current.x(), destination.origin().x(), 0);
            assertEquals(current.y(), destination.origin().y(), 0);
            assertEquals(current.z(), destination.origin().z(), 0);
            assertSame(fixture.overworld, destination.level());
            when(fixture.overworld.hasChunk(anyInt(), anyInt())).thenReturn(false);
            assertTrue(fixture.doors.projectionDestination(returnView, observer).isEmpty());
            assertTrue(fixture.doors.projectionDestination(returnView, observer).isEmpty());
            verify(fixture.leases).retain(eq(fixture.overworld), any(UUID.class), eq(5), eq(5));
            verify(fixture.projectionLease, never()).close();
            when(fixture.overworld.hasChunk(anyInt(), anyInt())).thenReturn(true);
            fixture.projectionReady.complete(true);
            fixture.pump();
            MinecraftDoorService.ProjectionDestination loaded = fixture.doors.projectionDestination(returnView, observer).orElseThrow();
            assertEquals(destination.origin(), loaded.origin());
            assertEquals(ticket, fixture.doors.state().getReturnTicket(observer).orElseThrow());
            Method expire = MinecraftDoorService.class.getDeclaredMethod("expireEndpointProjections", long.class);
            expire.setAccessible(true);
            expire.invoke(fixture.doors, System.currentTimeMillis() + 6_000L);
            verify(fixture.projectionLease).close();
        }
    }

    @Test
    public void unloadedReturnPreparationReleasesOnDisconnectAndShutdown() throws Exception {
        for (boolean disconnect : new boolean[] {true, false}) {
            Fixture fixture = new Fixture(temporary.newFolder().toPath());
            MinecraftDoorService.DoorView source = fixture.source(true);
            UUID observer = fixture.observer();
            fixture.doors.state().putReturnTicket(new ReturnTicket(observer, source.endpoint().identity().itemId(),
                source.endpoint().position().worldId(), "minecraft:overworld", 4, 64, 8, 0, 0));
            PlacedDoorEndpoint endpoint = new PlacedDoorEndpoint(new DoorPosition(UUID.randomUUID(), "wormholes:pockets", 0, 64, 0),
                DoorItemIdentity.newReturn(UUID.randomUUID()));
            MinecraftDoorService.DoorView exit = new MinecraftDoorService.DoorView(endpoint, fixture.pocketLevel,
                new DoorwayPlane(0, 64, 0, Direction.S), true);
            when(fixture.overworld.hasChunk(anyInt(), anyInt())).thenReturn(false);
            assertTrue(fixture.doors.projectionDestination(exit, observer).isEmpty());
            if (disconnect) {
                fixture.doors.playerDisconnected(fixture.players.getPlayer(observer));
            } else {
                fixture.doors.close();
            }
            verify(fixture.projectionLease).close();
            fixture.projectionReady.complete(true);
            fixture.pump();
            verify(fixture.projectionLease, times(2)).close();
            if (disconnect) {
                fixture.close();
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Thread owner = Thread.currentThread();
        private final ConcurrentLinkedQueue<Runnable> tasks = new ConcurrentLinkedQueue<>();
        private final MinecraftServer server = mock(MinecraftServer.class);
        private final PlayerList players = mock(PlayerList.class);
        private final ServerLevel overworld = level("minecraft:overworld");
        private final ServerLevel pocketLevel = level("wormholes:pockets");
        private final ChunkLeaseRegistry<ServerLevel> leases = mock(ChunkLeaseRegistry.class);
        private final ChunkLease projectionLease = mock(ChunkLease.class);
        private final CompletableFuture<Boolean> projectionReady = new CompletableFuture<>();
        private final MinecraftPocketRooms rooms = mock(MinecraftPocketRooms.class);
        private final MinecraftPocketService operations = mock(MinecraftPocketService.class);
        private final List<MinecraftPocketRooms.Prepared> prepared = new ArrayList<>();
        private final WormholesSettings settings = MinecraftTestSettings.defaults();
        private final MinecraftDoorService doors;

        private Fixture(Path directory) throws Exception {
            WormholesModRuntime runtime = mock(WormholesModRuntime.class);
            WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
            when(configuration.settings()).thenReturn(settings);
            when(runtime.configuration()).thenReturn(configuration);
            when(runtime.server()).thenReturn(server);
            when(runtime.leases()).thenReturn(leases);
            when(leases.retain(any(ServerLevel.class), any(UUID.class), anyInt(), anyInt())).thenReturn(projectionLease);
            when(projectionLease.ready()).thenReturn(projectionReady);
            when(server.getPlayerList()).thenReturn(players);
            when(server.getLevel(Level.OVERWORLD)).thenReturn(overworld);
            when(server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse("wormholes:pockets")))).thenReturn(pocketLevel);
            doAnswer(call -> { tasks.add(call.getArgument(0)); return null; }).when(server).execute(any(Runnable.class));
            doAnswer(call -> { assertSame(owner, Thread.currentThread()); return null; }).when(runtime).requireServerThread();
            doors = new MinecraftDoorService(runtime);
            doors.load(new MinecraftDoorService.Options(directory, mock(MinecraftDoorService.Access.class)));
            replace("pockets", rooms);
            replace("pocketOperations", operations);
            when(rooms.prepare(any())).thenAnswer(call -> CompletableFuture.completedFuture(prepared(call.getArgument(0))));
            when(rooms.load(any())).thenAnswer(call -> CompletableFuture.completedFuture(prepared(call.getArgument(0))));
            when(operations.furnish(any())).thenAnswer(call -> {
                assertSame(owner, Thread.currentThread());
                return CompletableFuture.completedFuture(call.getArgument(0));
            });
        }

        private UUID observer() {
            UUID id = UUID.randomUUID();
            ServerPlayer player = mock(ServerPlayer.class);
            when(player.getUUID()).thenReturn(id);
            when(players.getPlayer(id)).thenReturn(player);
            return id;
        }

        private MinecraftDoorService.DoorView source(boolean personal) throws Exception {
            DoorItemIdentity identity = personal ? DoorItemIdentity.newPersonal() : DoorItemIdentity.newPublic();
            PlacedDoorEndpoint endpoint = new PlacedDoorEndpoint(new DoorPosition(UUID.randomUUID(), "minecraft:overworld", 4, 64, 8), identity);
            doors.state().registerEndpoint(endpoint, UUID.randomUUID());
            return new MinecraftDoorService.DoorView(endpoint, overworld, new DoorwayPlane(4, 64, 8, Direction.N), true);
        }

        private MinecraftPocketRooms.Prepared prepared(PocketSpace space) {
            assertSame(owner, Thread.currentThread());
            MinecraftPocketRooms.Prepared room = mock(MinecraftPocketRooms.Prepared.class);
            PocketLayout layout = new PocketLayout(space);
            when(room.layout()).thenReturn(layout);
            when(room.endpoint()).thenReturn(MinecraftPocketRooms.endpoint(layout));
            when(room.level()).thenReturn(pocketLevel);
            prepared.add(room);
            return room;
        }

        private void replace(String name, Object value) throws Exception {
            Field field = MinecraftDoorService.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(doors, value);
        }

        private void pump() {
            Runnable task;
            while ((task = tasks.poll()) != null) {
                task.run();
            }
        }

        private void await(BooleanSupplier condition) throws InterruptedException {
            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
                pump();
                Thread.sleep(1);
            }
            pump();
            assertTrue("pocket preview did not finish", condition.getAsBoolean());
        }

        @Override
        public void close() {
            doors.close();
            pump();
        }

        private static ServerLevel level(String key) {
            ServerLevel level = mock(ServerLevel.class);
            when(level.dimension()).thenReturn(ResourceKey.create(Registries.DIMENSION, Identifier.parse(key)));
            when(level.hasChunk(anyInt(), anyInt())).thenReturn(true);
            BlockState door = Blocks.OAK_DOOR.defaultBlockState().setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER);
            when(level.getBlockState(any(BlockPos.class))).thenReturn(door);
            return level;
        }
    }
}
