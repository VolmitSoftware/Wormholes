package art.arcane.wormholes.menu.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import art.arcane.wormholes.PortalManager;
import art.arcane.wormholes.config.toml.RulesConfig;
import art.arcane.wormholes.menu.BukkitMenuViewer;
import art.arcane.wormholes.menu.MenuViewer;
import art.arcane.wormholes.menu.rules.RulesMenuView;
import art.arcane.wormholes.menu.rules.RulesStoreOutcome;
import art.arcane.wormholes.portal.LocalPortal;
import art.arcane.wormholes.rules.RuleDocument;
import art.arcane.wormholes.rules.RuleTemplates;
import art.arcane.wormholes.rules.RulesMenuModel;
import art.arcane.wormholes.rules.RulesPortalExtension;
import art.arcane.wormholes.rules.TraversalProfile;

class BukkitRulesMenuHostTest {
    private final UUID portalId = UUID.randomUUID();
    @TempDir
    Path dataFolder;
    private LocalPortal portal;
    private RulesPortalExtension extension;
    private MenuViewer viewer;
    private BukkitRulesMenuHost host;

    @BeforeEach
    void setUp() {
        portal = mock(LocalPortal.class);
        extension = mock(RulesPortalExtension.class);
        when(portal.extension(RulesPortalExtension.class)).thenReturn(extension);
        PortalManager portals = mock(PortalManager.class);
        when(portals.getLocalPortal(portalId)).thenReturn(portal);
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        viewer = new BukkitMenuViewer(player);
        host = new BukkitRulesMenuHost(portals, dataFolder);
    }

    @Test
    void theViewCarriesTheStoredDocumentAndTheLiveLimits() {
        RuleDocument document = RulesMenuModel.addRule(RuleDocument.EMPTY, "vip");
        when(extension.document()).thenReturn(document);

        Optional<RulesMenuView> view = host.rules(portalId);

        assertTrue(view.isPresent());
        assertSame(document, view.get().document());
        assertEquals(new RulesConfig().cooldownMaxSeconds, view.get().limits().cooldownMaxSeconds);
    }

    @Test
    void aMissingPortalOrExtensionHasNoRules() {
        assertTrue(host.rules(UUID.randomUUID()).isEmpty());

        when(portal.extension(RulesPortalExtension.class)).thenReturn(null);
        assertTrue(host.rules(portalId).isEmpty());

        when(portal.extension(RulesPortalExtension.class)).thenReturn(extension);
        when(portal.isDestroyed()).thenReturn(true);
        assertTrue(host.rules(portalId).isEmpty());
    }

    @Test
    void aValidDocumentIsStoredAndRefreshesThePortalMenus() {
        RuleDocument document = RuleDocument.EMPTY.withProfile(new TraversalProfile(5_000L, "lobby", 0L, 1.0D, 1.0D, 0, 0));

        RulesStoreOutcome outcome = host.store(viewer, portalId, document);

        assertInstanceOf(RulesStoreOutcome.Stored.class, outcome);
        ArgumentCaptor<RuleDocument> stored = ArgumentCaptor.forClass(RuleDocument.class);
        verify(extension).setDocument(stored.capture());
        assertEquals(document.profile(), stored.getValue().profile());
        verify(portal).invalidateMenus();
    }

    @Test
    void aDocumentOutsideTheConfiguredLimitsIsRejectedWithItsProblems() {
        long tooLong = (new RulesConfig().warmupMaxSeconds + 1L) * 1_000L;
        RuleDocument document = RuleDocument.EMPTY.withProfile(new TraversalProfile(0L, "", tooLong, 1.0D, 1.0D, 0, 0));

        RulesStoreOutcome outcome = host.store(viewer, portalId, document);

        RulesStoreOutcome.Invalid invalid = assertInstanceOf(RulesStoreOutcome.Invalid.class, outcome);
        assertTrue(!invalid.problems().isEmpty());
        verify(extension, never()).setDocument(any());
        verify(portal, never()).invalidateMenus();
    }

    @Test
    void templatesComeFromTheDataFolder() throws IOException {
        RuleTemplates templates = new RuleTemplates(dataFolder, new RulesConfig());
        RuleDocument saved = RulesMenuModel.addRule(RuleDocument.EMPTY, "members");
        templates.save("members", saved);
        templates.save("guests", RuleDocument.EMPTY);

        assertEquals(List.of("guests", "members"), host.templates());
        assertEquals(List.of("members"), host.template("members").orElseThrow().rules().stream().map(rule -> rule.id()).toList());
        assertTrue(host.template("missing").isEmpty());
    }
}
