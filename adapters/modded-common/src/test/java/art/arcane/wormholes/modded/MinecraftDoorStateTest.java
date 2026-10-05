package art.arcane.wormholes.modded;

import art.arcane.wormholes.door.DimensionalDoorRepository;
import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.MainConfig;
import art.arcane.wormholes.config.toml.ProjectionConfig;
import art.arcane.wormholes.config.toml.RenderConfig;
import art.arcane.wormholes.config.toml.NetworkConfig;
import art.arcane.wormholes.door.DoorForm;
import art.arcane.wormholes.door.DoorItemIdentity;
import art.arcane.wormholes.door.DoorPairIdentity;
import art.arcane.wormholes.door.DoorPosition;
import art.arcane.wormholes.door.DoorStateService;
import art.arcane.wormholes.door.PairEndpoint;
import art.arcane.wormholes.door.PlacedDoorEndpoint;
import art.arcane.wormholes.door.ReturnTicket;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftDoorStateTest {
    @Rule
    public TemporaryFolder temporary = new TemporaryFolder();

    @BeforeClass
    public static void bootstrap() {
        MinecraftTestBase.bootstrap();
        for (Item item : List.of(Items.BUNDLE, Items.OAK_DOOR, Items.OAK_TRAPDOOR, Items.DARK_OAK_DOOR, Items.PALE_OAK_DOOR)) {
            item.builtInRegistryHolder().bindComponents(DataComponents.COMMON_ITEM_COMPONENTS);
        }
    }

    @Test
    public void kitCopiesResolveToTheSamePairAndKeepDoorForm() {
        for (DoorForm form : DoorForm.values()) {
            ItemStack kit = MinecraftDoorItems.pairKit(form);
            MinecraftDoorItems.PairKit stamp = MinecraftDoorItems.kit(kit).orElseThrow();
            assertEquals(stamp, MinecraftDoorItems.kit(kit.copy()).orElseThrow());
            DoorPairIdentity pair = DoorPairIdentity.forKit(stamp.kitId());
            for (PairEndpoint side : PairEndpoint.values()) {
                DoorItemIdentity identity = DoorItemIdentity.paired(pair.itemId(side), pair.pairId(), side, stamp.form());
                assertEquals(identity, MinecraftDoorItems.identity(MinecraftDoorItems.door(identity).copy()).orElseThrow());
            }
        }
        ItemStack renamed = new ItemStack(Items.OAK_DOOR);
        renamed.set(DataComponents.CUSTOM_NAME, Component.literal("Wormhole Door A"));
        assertFalse(MinecraftDoorItems.identity(renamed).isPresent());
        assertFalse(MinecraftDoorItems.kit(renamed).isPresent());
    }

    @Test
    public void jsonDocumentsPreserveLongValuesAndNestedNulls() {
        long value = 9_007_199_254_740_993L;
        Map<String, Object> document = MinecraftJsonDocuments.INSTANCE.decode("{\"value\":" + value + ",\"nested\":[null,{\"active\":true}]}");
        assertEquals(value, ((Number) document.get("value")).longValue());
        assertEquals(document, MinecraftJsonDocuments.INSTANCE.decode(MinecraftJsonDocuments.INSTANCE.encode(document)));
    }

    @Test
    public void nativeRepositoryRestoresPairPlacementAccessAndDurableTicket() throws Exception {
        Path directory = temporary.newFolder().toPath();
        DoorStateService state = DoorStateService.under(directory, MinecraftJsonDocuments.INSTANCE);
        DoorPairIdentity pair = DoorPairIdentity.forKit(UUID.randomUUID());
        UUID player = UUID.randomUUID();
        UUID world = UUID.randomUUID();
        PlacedDoorEndpoint a = new PlacedDoorEndpoint(new DoorPosition(world, "minecraft:overworld", 0, 64, 0), pair.endpoint(PairEndpoint.A));
        PlacedDoorEndpoint b = new PlacedDoorEndpoint(new DoorPosition(world, "minecraft:overworld", 32, 64, 0), pair.endpoint(PairEndpoint.B));
        state.registerPair(pair);
        state.registerEndpoint(a, player);
        state.registerEndpoint(b, player);
        ReturnTicket ticket = new ReturnTicket(player, a.identity().itemId(), world, "minecraft:overworld", 0.5, 64, 1.5, 180, 0);
        state.putReturnTicket(ticket);
        DoorStateService restored = DoorStateService.load(DimensionalDoorRepository.under(directory, MinecraftJsonDocuments.INSTANCE));
        assertEquals(b, restored.findMate(a.identity()).orElseThrow());
        assertEquals(player, restored.accessRecord(a.identity().itemId()).orElseThrow().ownerId());
        assertEquals(ticket, restored.getReturnTicket(player).orElseThrow());
        restored.removeReturnTicket(player);
        assertTrue(DoorStateService.under(directory, MinecraftJsonDocuments.INSTANCE).returnTickets().isEmpty());
    }

    @Test
    public void commandsRegisterBeforeServerAndStorageExist() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftDoorService doors = new MinecraftDoorService(runtime);
        CommandDispatcher<CommandSourceStack> commands = new CommandDispatcher<>();
        doors.registerCommands(commands);
        assertNotNull(commands.getRoot().getChild("wormholes").getChild("door").getChild("pair"));
        verify(runtime, never()).server();
    }
    @Test
    public void serviceReloadsAfterStopAndFailedLoad() throws Exception {
        Path directory = temporary.newFolder().toPath();
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftServer server = mock(MinecraftServer.class);
        when(runtime.server()).thenReturn(server);
        WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        MainConfig main = new MainConfig();
        when(runtime.configuration()).thenReturn(configuration);
        when(configuration.settings()).thenReturn(new WormholesSettings(main, new ProjectionConfig(), new RenderConfig(), new NetworkConfig()));
        MinecraftDoorService doors = new MinecraftDoorService(runtime);
        MinecraftDoorService.Options options = new MinecraftDoorService.Options(directory, mock(MinecraftDoorService.Access.class));
        doors.load(options);
        assertSame(doors, MinecraftDoorService.forServer(server));
        main.dimensionalDoorsEnabled = false;
        assertNull(MinecraftDoorService.forServer(server));
        assertTrue(doors.projectableViews().isEmpty());
        main.dimensionalDoorsEnabled = true;
        assertSame(doors, MinecraftDoorService.forServer(server));
        DoorPairIdentity pair = DoorPairIdentity.create();
        doors.state().registerPair(pair);
        doors.close();
        assertNull(MinecraftDoorService.forServer(server));
        doors.load(options);
        assertEquals(pair, doors.state().findPair(pair.pairId()).orElseThrow());
        doors.close();
        Path state = directory.resolve("doors/state.json");
        Files.writeString(state, "invalid JSON");
        assertThrows(IOException.class, () -> doors.load(options));
        assertNull(MinecraftDoorService.forServer(server));
        doors.close();
        Files.delete(state);
        doors.load(options);
        assertTrue(doors.state().pairs().isEmpty());
        doors.close();
    }
}
