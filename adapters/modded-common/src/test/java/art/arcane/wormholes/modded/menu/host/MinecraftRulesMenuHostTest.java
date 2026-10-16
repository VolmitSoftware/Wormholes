package art.arcane.wormholes.modded.menu.host;

import art.arcane.wormholes.config.WormholesSettings;
import art.arcane.wormholes.config.toml.RulesConfig;
import art.arcane.wormholes.menu.MenuScope;
import art.arcane.wormholes.menu.MenuService;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.rules.RulesMenuView;
import art.arcane.wormholes.menu.rules.RulesStoreOutcome;
import art.arcane.wormholes.modded.MinecraftPortal;
import art.arcane.wormholes.modded.MinecraftPortalRegistry;
import art.arcane.wormholes.modded.MinecraftRules;
import art.arcane.wormholes.modded.MinecraftStorePaths;
import art.arcane.wormholes.modded.MinecraftTestBase;
import art.arcane.wormholes.modded.WormholesModConfiguration;
import art.arcane.wormholes.modded.WormholesModRuntime;
import art.arcane.wormholes.modded.menu.MinecraftMenuViewer;
import art.arcane.wormholes.rules.RuleDocument;
import art.arcane.wormholes.rules.RuleTemplates;
import art.arcane.wormholes.rules.RuleValidationException;
import art.arcane.wormholes.rules.RulesMenuModel;
import net.minecraft.server.level.ServerPlayer;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class MinecraftRulesMenuHostTest extends MinecraftTestBase {
    private static final UUID PORTAL = UUID.fromString("a17e0000-0000-4000-8000-0000000000aa");

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private MinecraftRules rules;
    private MenuService menus;
    private MinecraftPortal portal;
    private MenuViewer viewer;
    private Path data;
    private MinecraftRulesMenuHost host;

    @Before
    public void setUp() throws IOException {
        WormholesModRuntime runtime = mock(WormholesModRuntime.class);
        MinecraftPortalRegistry portals = mock(MinecraftPortalRegistry.class);
        WormholesModConfiguration configuration = mock(WormholesModConfiguration.class);
        WormholesSettings settings = mock(WormholesSettings.class);
        rules = mock(MinecraftRules.class);
        menus = mock(MenuService.class);
        portal = mock(MinecraftPortal.class);
        data = folder.newFolder("data").toPath();
        when(runtime.portals()).thenReturn(portals);
        when(runtime.rules()).thenReturn(rules);
        when(runtime.menus()).thenReturn(menus);
        when(runtime.configuration()).thenReturn(configuration);
        when(runtime.stores()).thenReturn(new MinecraftStorePaths(folder.newFolder("config").toPath(), data, folder.newFolder("doors").toPath()));
        when(configuration.settings()).thenReturn(settings);
        when(settings.getRules()).thenReturn(new RulesConfig());
        when(portals.get(PORTAL)).thenReturn(portal);
        viewer = new MinecraftMenuViewer(runtime, mock(ServerPlayer.class));
        host = new MinecraftRulesMenuHost(runtime);
    }

    @Test
    public void theViewCarriesThePortalDocumentAndTheConfiguredLimits() {
        RuleDocument document = RulesMenuModel.addRule(RuleDocument.EMPTY, "vip");
        when(rules.document(portal)).thenReturn(document);

        Optional<RulesMenuView> view = host.rules(PORTAL);

        assertTrue(view.isPresent());
        assertSame(document, view.get().document());
        assertEquals(new RulesConfig().warmupMaxSeconds, view.get().limits().warmupMaxSeconds);
        assertTrue(host.rules(UUID.randomUUID()).isEmpty());
    }

    @Test
    public void storingSetsTheDocumentAndRefreshesThePortalMenus() {
        RuleDocument document = RulesMenuModel.addRule(RuleDocument.EMPTY, "members");

        assertTrue(host.store(viewer, PORTAL, document) instanceof RulesStoreOutcome.Stored);

        verify(rules).setDocument(portal, document);
        verify(menus).invalidate(new MenuScope.Portal(PORTAL));
    }

    @Test
    public void aRejectedDocumentReportsItsProblemsAndRefreshesNothing() {
        RuleDocument document = RulesMenuModel.addRule(RuleDocument.EMPTY, "members");
        doThrow(new RuleValidationException(List.of("profile warmupMillis is out of range"))).when(rules).setDocument(portal, document);

        RulesStoreOutcome outcome = host.store(viewer, PORTAL, document);

        assertEquals(List.of("profile warmupMillis is out of range"), ((RulesStoreOutcome.Invalid) outcome).problems());
        verify(menus, never()).invalidate(any());
    }

    @Test
    public void templatesComeFromTheDataStore() throws IOException {
        RuleTemplates templates = new RuleTemplates(data, new RulesConfig());
        templates.save("members", RulesMenuModel.addRule(RuleDocument.EMPTY, "members"));

        assertEquals(List.of("members"), host.templates());
        assertEquals("members", host.template("members").orElseThrow().rules().getFirst().id());
        assertTrue(host.template("missing").isEmpty());
    }
}
