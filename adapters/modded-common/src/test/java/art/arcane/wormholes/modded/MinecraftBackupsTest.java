package art.arcane.wormholes.modded;

import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.door.DimensionalDoorRepository;
import art.arcane.wormholes.door.DoorStateService;
import art.arcane.wormholes.door.DoorStoreSnapshot;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.MinecraftServer;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftBackupsTest extends MinecraftTestBase {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    public void importerOptionsDefaultToDryAndRequireExactFlags() {
        MinecraftBackups.ImportOptions defaults = MinecraftBackups.ImportOptions.parse("");
        assertTrue(defaults.dry());
        assertEquals(0, defaults.width());
        MinecraftBackups.ImportOptions explicit = MinecraftBackups.ImportOptions.parse("dry=false frame=3,5");
        assertFalse(explicit.dry());
        assertEquals(3, explicit.width());
        assertEquals(5, explicit.height());
    }

    @Test(expected = IllegalArgumentException.class)
    public void importerOptionsRejectUnknownValues() {
        MinecraftBackups.ImportOptions.parse("dry=maybe");
    }

    @Test
    public void restoreDefaultsRemainDryAndUntrusted() {
        MinecraftBackups.RestoreOptions defaults = MinecraftBackups.RestoreOptions.parse("");
        assertTrue(defaults.dry());
        assertFalse(defaults.confirm());
        assertFalse(defaults.allowUnsigned());
        MinecraftBackups.RestoreOptions chosen = MinecraftBackups.RestoreOptions.parse("dry=false confirm=true allow-unsigned=true world-map=a=b");
        assertFalse(chosen.dry());
        assertTrue(chosen.confirm());
        assertTrue(chosen.allowUnsigned());
        assertEquals("b", chosen.remap().worlds().get("a"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void malformedConfirmationDoesNotBecomeAnImplicitApproval() {
        MinecraftBackups.RestoreOptions.parse("dry=false confirm=yes");
    }

    @Test
    public void resetRefusesOccupiedPocketsBeforeStoppingRuntime() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftDoorService doors = mock(MinecraftDoorService.class);
        when(runtime.doors()).thenReturn(doors);
        assertEquals(0, new MinecraftBackups(runtime).reset(mock(CommandSourceStack.class)));
        verify(runtime, never()).stop();
    }

    @Test
    public void resetRetiresPocketSlotsAndRestartsAfterDeletingOnlyProductData() throws Exception {
        Path root = temporary.newFolder().toPath();
        Path data = root.resolve("config/wormholes");
        Files.createDirectories(data.resolve("identity"));
        Files.writeString(data.resolve("identity/server.identity"), "old");
        Files.writeString(data.resolve(WormholesSettings.CONFIG_FILE_NAME), "old");
        Path world = root.resolve("world/region/keep.mca");
        Files.createDirectories(world.getParent());
        Files.writeString(world, "world data");
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftServer server = mock(MinecraftServer.class);
        MinecraftDoorService doors = mock(MinecraftDoorService.class);
        DoorStateService state = mock(DoorStateService.class);
        when(runtime.server()).thenReturn(server);
        when(runtime.doors()).thenReturn(doors);
        when(doors.resetAllowed()).thenReturn(true);
        when(doors.state()).thenReturn(state);
        when(state.snapshot()).thenReturn(new DoorStoreSnapshot(DoorStoreSnapshot.CURRENT_SCHEMA, 42L,
            List.of(), List.of(), List.of(), List.of(), List.of()));
        when(runtime.stores()).thenReturn(MinecraftStorePaths.dedicated(root));
        doAnswer(invocation -> { invocation.<Runnable>getArgument(0).run(); return null; }).when(server).execute(any());
        CountDownLatch restarted = new CountDownLatch(1);
        doAnswer(invocation -> { restarted.countDown(); return null; }).when(runtime).start(server);
        assertEquals(1, new MinecraftBackups(runtime).reset(mock(CommandSourceStack.class)));
        assertTrue(restarted.await(5, TimeUnit.SECONDS));
        verify(runtime).stop();
        assertFalse(Files.exists(data.resolve("identity/server.identity")));
        assertFalse(Files.exists(data.resolve(WormholesSettings.CONFIG_FILE_NAME)));
        assertEquals("world data", Files.readString(world));
        assertEquals(42L, DimensionalDoorRepository.under(data, MinecraftJsonDocuments.INSTANCE).load().nextPocketSlot());
    }

    @Test
    public void resetClearsSharedPortalDataAndOnlyThisWorldsDoors() throws Exception {
        Path game = temporary.newFolder().toPath();
        Path save = game.resolve("saves/World");
        MinecraftStorePaths stores = MinecraftStorePaths.singleplayer(game, save, true);
        Files.createDirectories(stores.data().resolve("portals"));
        Files.writeString(stores.data().resolve("portals/portal.json"), "{}");
        Files.writeString(stores.config().resolve(WormholesSettings.CONFIG_FILE_NAME), "old");
        Files.createDirectories(stores.doors().resolve("pockets/templates"));
        Files.writeString(stores.doors().resolve("pockets/templates/room.nbt"), "nbt");
        Path region = save.resolve("region/r.0.0.mca");
        Files.createDirectories(region.getParent());
        Files.writeString(region, "world data");
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftServer server = mock(MinecraftServer.class);
        MinecraftDoorService doors = mock(MinecraftDoorService.class);
        DoorStateService state = mock(DoorStateService.class);
        when(runtime.server()).thenReturn(server);
        when(runtime.stores()).thenReturn(stores);
        when(runtime.doors()).thenReturn(doors);
        when(doors.resetAllowed()).thenReturn(true);
        when(doors.state()).thenReturn(state);
        when(state.snapshot()).thenReturn(new DoorStoreSnapshot(DoorStoreSnapshot.CURRENT_SCHEMA, 7L,
            List.of(), List.of(), List.of(), List.of(), List.of()));
        doAnswer(invocation -> { invocation.<Runnable>getArgument(0).run(); return null; }).when(server).execute(any());
        CountDownLatch restarted = new CountDownLatch(1);
        doAnswer(invocation -> { restarted.countDown(); return null; }).when(runtime).start(server);
        assertEquals(1, new MinecraftBackups(runtime).reset(mock(CommandSourceStack.class)));
        assertTrue(restarted.await(5, TimeUnit.SECONDS));
        assertFalse(Files.exists(stores.data().resolve("portals/portal.json")));
        assertFalse(Files.exists(stores.config().resolve(WormholesSettings.CONFIG_FILE_NAME)));
        assertFalse(Files.exists(stores.doors().resolve("pockets/templates/room.nbt")));
        assertFalse(Files.exists(stores.data().resolve("doors")));
        assertEquals("world data", Files.readString(region));
        assertEquals(7L, DimensionalDoorRepository.under(stores.doors(), MinecraftJsonDocuments.INSTANCE).load().nextPocketSlot());
    }
}
