package art.arcane.wormholes.modded;

import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.modded.seamless.RemoteRoutes;
import art.arcane.wormholes.portal.PortalStateCodec;
import art.arcane.wormholes.portal.PortalType;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftPortalDeletionTest extends MinecraftTestBase {
    private static final TypeToken<Map<String, Object>> DOCUMENT = new TypeToken<>() { };

    @Rule
    public TemporaryFolder directory = new TemporaryFolder();

    @Rule
    public TemporaryFolder languages = new TemporaryFolder();

    @Test
    public void crossWorldPortalsLinkedToEachOtherAreDeletedTogether() throws Exception {
        Fixture fixture = fixture();
        try (MinecraftPortalRegistry registry = fixture.registry()) {
            MinecraftPortal deleted = create(registry, fixture.overworld(), 0);
            MinecraftPortal partner = create(registry, fixture.end(), 0);
            assertTrue(registry.link(fixture.actor(), deleted.getId(), partner.getId()));
            assertTrue(registry.link(fixture.actor(), partner.getId(), deleted.getId()));

            assertTrue(registry.remove(deleted.getId()));

            assertGone(registry, deleted.getId());
            assertGone(registry, partner.getId());
        }
    }

    @Test
    public void crossWorldLinkAndReturnPairIsDeletedTogether() throws Exception {
        Fixture fixture = fixture();
        try (MinecraftPortalRegistry registry = fixture.registry()) {
            MinecraftPortal partner = create(registry, fixture.overworld(), 0);
            MinecraftPortal deleted = create(registry, fixture.end(), 0);
            assertTrue(registry.link(fixture.actor(), partner.getId(), deleted.getId()));
            fixture.nexus().pair(fixture.actor(), partner, deleted, true);

            assertTrue(registry.remove(deleted.getId()));

            assertGone(registry, deleted.getId());
            assertGone(registry, partner.getId());
        }
    }

    @Test
    public void managedCounterpartIsDeletedWithItsPortal() throws Exception {
        Fixture fixture = fixture();
        try (MinecraftPortalRegistry registry = fixture.registry()) {
            MinecraftPortal deleted = create(registry, fixture.overworld(), 0);
            MinecraftPortal counterpart = create(registry, fixture.end(), 0);
            deleted.setCounterpartId(counterpart.getId());
            counterpart.setCounterpartId(deleted.getId());
            registry.save(deleted);
            registry.save(counterpart);

            assertTrue(registry.remove(deleted.getId()));

            assertGone(registry, deleted.getId());
            assertGone(registry, counterpart.getId());
        }
    }

    @Test
    public void oneWayCrossWorldSourceKeepsItsLinkWhenItsDestinationIsDeleted() throws Exception {
        Fixture fixture = fixture();
        try (MinecraftPortalRegistry registry = fixture.registry()) {
            MinecraftPortal source = create(registry, fixture.end(), 0);
            MinecraftPortal deleted = create(registry, fixture.overworld(), 0);
            assertTrue(registry.link(fixture.actor(), source.getId(), deleted.getId()));

            assertTrue(registry.remove(deleted.getId()));

            assertGone(registry, deleted.getId());
            Map<String, Object> stored = stored(registry, source.getId());
            assertEquals(Map.of("type", "DIMENSIONAL", "destination", deleted.getId().toString()), stored.get("tunnel"));
            assertEquals(deleted.getId(), source.getDestinationId());
        }
    }

    @Test
    public void oneWayCrossWorldDestinationIsUntouchedWhenItsSourceIsDeleted() throws Exception {
        Fixture fixture = fixture();
        try (MinecraftPortalRegistry registry = fixture.registry()) {
            MinecraftPortal deleted = create(registry, fixture.end(), 0);
            MinecraftPortal destination = create(registry, fixture.overworld(), 0);
            assertTrue(registry.link(fixture.actor(), deleted.getId(), destination.getId()));

            assertTrue(registry.remove(deleted.getId()));

            assertGone(registry, deleted.getId());
            assertFalse(stored(registry, destination.getId()).containsKey("tunnel"));
        }
    }

    @Test
    public void sameWorldLinkAndReturnSurvivorLosesItsReturnLink() throws Exception {
        Fixture fixture = fixture();
        try (MinecraftPortalRegistry registry = fixture.registry()) {
            MinecraftPortal deleted = create(registry, fixture.overworld(), 0);
            MinecraftPortal survivor = create(registry, fixture.overworld(), 10);
            assertTrue(registry.link(fixture.actor(), deleted.getId(), survivor.getId()));
            fixture.nexus().pair(fixture.actor(), deleted, survivor, true);

            assertTrue(registry.remove(deleted.getId()));

            assertGone(registry, deleted.getId());
            Map<String, Object> stored = stored(registry, survivor.getId());
            assertFalse(stored.containsKey("tunnel"));
            assertEquals(Boolean.FALSE, stored.get("nexus.reciprocal"));
            assertNull(survivor.getDestinationId());
        }
    }

    @Test
    public void sameWorldReturnLinkWithoutLinkAndReturnSurvivesDeletion() throws Exception {
        Fixture fixture = fixture();
        try (MinecraftPortalRegistry registry = fixture.registry()) {
            MinecraftPortal deleted = create(registry, fixture.overworld(), 0);
            MinecraftPortal survivor = create(registry, fixture.overworld(), 10);
            assertTrue(registry.link(fixture.actor(), deleted.getId(), survivor.getId()));
            assertTrue(registry.link(fixture.actor(), survivor.getId(), deleted.getId()));

            assertTrue(registry.remove(deleted.getId()));

            assertGone(registry, deleted.getId());
            assertEquals(Map.of("type", "LOCAL", "destination", deleted.getId().toString()), stored(registry, survivor.getId()).get("tunnel"));
        }
    }

    @Test
    public void survivorThatNoLongerMarksTheReturnKeepsItsLink() throws Exception {
        Fixture fixture = fixture();
        try (MinecraftPortalRegistry registry = fixture.registry()) {
            MinecraftPortal deleted = create(registry, fixture.overworld(), 0);
            MinecraftPortal survivor = create(registry, fixture.overworld(), 10);
            assertTrue(registry.link(fixture.actor(), deleted.getId(), survivor.getId()));
            fixture.nexus().pair(fixture.actor(), deleted, survivor, true);
            survivor.setNexusValue("reciprocal", false);
            registry.save(survivor);

            assertTrue(registry.remove(deleted.getId()));

            assertGone(registry, deleted.getId());
            assertEquals(Map.of("type", "LOCAL", "destination", deleted.getId().toString()), stored(registry, survivor.getId()).get("tunnel"));
        }
    }

    @Test
    public void deleteAllPortalsRemovesEveryPortalWithoutEffectsOrNotices() throws Exception {
        Fixture fixture = fixture();
        WormholesModRuntime runtime = fixture.runtime();
        MinecraftPortalEffects effects = runtime.effects();
        RemoteRoutes routes = runtime.remoteRoutes();
        MinecraftNetworkService network = runtime.network();
        MinecraftServer server = runtime.server();
        MinecraftAccessService access = mock(MinecraftAccessService.class);
        CommandSourceStack source = mock(CommandSourceStack.class);
        ServerPlayer bystander = mock(ServerPlayer.class);
        when(runtime.access()).thenReturn(access);
        when(runtime.projections()).thenReturn(mock(MinecraftProjectionService.class));
        when(access.permission(source, "wormholes.admin.reset")).thenReturn(true);
        when(fixture.overworld().getServer()).thenReturn(server);
        when(fixture.overworld().players()).thenReturn(List.of(bystander));
        when(bystander.level()).thenReturn(fixture.overworld());
        when(bystander.getUUID()).thenReturn(UUID.randomUUID());
        Path languageDirectory = languages.getRoot().toPath();
        MinecraftLocalization localization = new MinecraftLocalization(runtime);
        localization.start(languageDirectory, WormholesSettings.loadAll(languageDirectory));
        try (MinecraftPortalRegistry registry = fixture.registry()) {
            MinecraftPortal crossWorld = create(registry, fixture.overworld(), 0);
            MinecraftPortal crossWorldPartner = create(registry, fixture.end(), 0);
            MinecraftPortal linkAndReturn = create(registry, fixture.overworld(), 10);
            MinecraftPortal linkAndReturnPartner = create(registry, fixture.overworld(), 20);
            MinecraftPortal unlinked = create(registry, fixture.end(), 10);
            assertTrue(registry.link(fixture.actor(), crossWorld.getId(), crossWorldPartner.getId()));
            assertTrue(registry.link(fixture.actor(), crossWorldPartner.getId(), crossWorld.getId()));
            assertTrue(registry.link(fixture.actor(), linkAndReturn.getId(), linkAndReturnPartner.getId()));
            fixture.nexus().pair(fixture.actor(), linkAndReturn, linkAndReturnPartner, true);
            List<MinecraftPortal> portals = List.of(crossWorld, crossWorldPartner, linkAndReturn, linkAndReturnPartner, unlinked);
            registry.flushWrites().get();
            assertEquals(portals.size(), storedFiles().size());
            CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
            new MinecraftOperations(runtime).registerCommands(dispatcher);

            assertEquals(portals.size(), dispatcher.execute("wormholes admin deleteallportals", source));

            registry.flushWrites().get();
            assertTrue(registry.snapshot().isEmpty());
            assertEquals(List.of(), storedFiles());
            verify(bystander, never()).sendSystemMessage(any(Component.class), anyBoolean());
            verify(effects, never()).removed(any(MinecraftPortal.class));
            for (MinecraftPortal portal : portals) {
                verify(routes).portalRemoved(portal.getId());
                verify(network).portalRemoved(portal.getId());
            }
        } finally {
            localization.close();
        }
    }

    private void assertGone(MinecraftPortalRegistry registry, UUID id) throws Exception {
        registry.flushWrites().get();
        assertNull(registry.get(id));
        assertFalse(Files.exists(PortalStateCodec.file(directory.getRoot().toPath(), id)));
    }

    private List<Path> storedFiles() throws IOException {
        try (Stream<Path> paths = Files.walk(directory.getRoot().toPath())) {
            return paths.filter(Files::isRegularFile).toList();
        }
    }

    private Map<String, Object> stored(MinecraftPortalRegistry registry, UUID id) throws Exception {
        registry.flushWrites().get();
        assertNotNull(registry.get(id));
        Path file = PortalStateCodec.file(directory.getRoot().toPath(), id);
        assertTrue(Files.exists(file));
        return new Gson().fromJson(Files.readString(file), DOCUMENT);
    }

    private MinecraftPortal create(MinecraftPortalRegistry registry, ServerLevel level, int x) {
        return registry.create(UUID.randomUUID(), level, List.of(new BlockPos(x, 64, 0), new BlockPos(x, 66, 0)),
            PortalType.PORTAL, new Vec3(1, 0, 0));
    }

    private Fixture fixture() {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftServer server = mock(MinecraftServer.class);
        ServerLevel overworld = level(Level.OVERWORLD);
        ServerLevel end = level(Level.END);
        ServerPlayer actor = mock(ServerPlayer.class);
        MinecraftNetworkService network = mock(MinecraftNetworkService.class);
        WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        when(runtime.server()).thenReturn(server);
        when(server.getAllLevels()).thenReturn(List.of(overworld, end));
        when(runtime.network()).thenReturn(network);
        when(runtime.effects()).thenReturn(mock(MinecraftPortalEffects.class));
        when(runtime.remoteRoutes()).thenReturn(mock(RemoteRoutes.class));
        when(runtime.atlas()).thenReturn(mock(MinecraftAtlasService.class));
        when(runtime.configuration()).thenReturn(configuration);
        when(configuration.settings()).thenReturn(MinecraftTestSettings.defaults());
        when(actor.getUUID()).thenReturn(UUID.randomUUID());
        MinecraftNexus nexus = new MinecraftNexus(runtime);
        when(runtime.nexus()).thenReturn(nexus);
        MinecraftPortalRegistry registry = new MinecraftPortalRegistry(runtime, new MinecraftPortalRegistry.Options(directory.getRoot().toPath(),
            new MinecraftPortalRegistry.Access() {
                @Override
                public boolean administrator(ServerPlayer player) {
                    return true;
                }

                @Override
                public boolean permission(ServerPlayer player, String node) {
                    return false;
                }

                @Override
                public boolean nameAlias() {
                    return true;
                }
            }));
        when(runtime.portals()).thenReturn(registry);
        return new Fixture(runtime, registry, nexus, overworld, end, actor);
    }

    private static ServerLevel level(ResourceKey<Level> dimension) {
        ServerLevel level = mock(ServerLevel.class);
        when(level.dimension()).thenReturn(dimension);
        when(level.getMinY()).thenReturn(-64);
        when(level.getMaxY()).thenReturn(320);
        return level;
    }

    private record Fixture(WormholesModRuntime runtime, MinecraftPortalRegistry registry, MinecraftNexus nexus, ServerLevel overworld, ServerLevel end, ServerPlayer actor) {
    }
}
