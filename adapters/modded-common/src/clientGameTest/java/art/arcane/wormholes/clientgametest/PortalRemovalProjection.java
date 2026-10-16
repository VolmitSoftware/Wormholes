package art.arcane.wormholes.clientgametest;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import static art.arcane.wormholes.clientgametest.SeamlessScenario.assertTrue;

final class PortalRemovalProjection {
    private static final Logger LOGGER = LoggerFactory.getLogger("WormholesClientGameTest");
    private static final int VIEW_TICKS = 600;
    private static final int REMOVE_TICKS = 100;
    private static final int CLEAR_TICKS = 40;
    private static final int PERSIST_TICKS = 400;
    private static final int CAPTURE_TICKS = 100;
    private static final int OBSERVE_TICKS = 100;
    private static final int CROSS_TICKS = 200;
    private static final int STEP_AWAY_TICKS = 10;
    private static final int SETTLE_TICKS = 40;
    private static final int NOTICE_TICKS = 40;
    private static final int INVENTORY_TICKS = 40;
    private static final double FAR_BLOCKS = 1600.0D;

    private PortalRemovalProjection() {
    }

    static Removal viewer(SeamlessClient client, SeamlessServer server, String label, ResourceKey<Level> remote, PortalPairScene.Side removed,
                          Method method, PortalPairScene.Link link) {
        SeamlessScenario.join(client);
        SeamlessScenario.assertSeamlessNegotiated(client);
        PortalPairScene.Spec spec = new PortalPairScene.Spec(PortalPairScene.Orientation.VERTICAL, remote, PortalPairScene.Side.LOCAL, link);
        PortalPairScene.Pair pair = server.buildPortalPair(spec);
        PortalPairViews.awaitPlaced(client, server, Level.OVERWORLD, label);
        awaitView(client, pair, PortalPairScene.Side.LOCAL, label);
        Removal removal = new Removal(label, pair, PortalPairScene.Side.LOCAL, PortalPairScene.Stance.ENTRY, removed,
            expectation(server, pair, spec, removed));
        remove(client, server, removal, method);
        return removal;
    }

    static Removal afterCrossing(SeamlessClient client, SeamlessServer server, String label, PortalPairScene.Link link) {
        SeamlessScenario.join(client);
        SeamlessScenario.assertSeamlessNegotiated(client);
        PortalPairScene.Spec spec = new PortalPairScene.Spec(PortalPairScene.Orientation.VERTICAL, Level.END, PortalPairScene.Side.REMOTE, link);
        PortalPairScene.Pair pair = server.buildPortalPair(spec);
        PortalPairViews.awaitPlaced(client, server, Level.END, label);
        awaitView(client, pair, PortalPairScene.Side.REMOTE, label);
        String overworld = Level.OVERWORLD.identifier().toString();
        client.holdForward();
        try {
            await(client, minecraft -> dimension(minecraft).equals(overworld), CROSS_TICKS, label + ": the End portal did not carry the player to the Overworld");
        } finally {
            client.releaseForward();
        }
        client.holdForwardFor(STEP_AWAY_TICKS);
        client.waitTicks(SETTLE_TICKS);
        client.lookAt(0.0F, 0.0F);
        if (link != PortalPairScene.Link.ONE_WAY) {
            awaitView(client, pair, PortalPairScene.Side.LOCAL, label);
        } else {
            int shown = PortalPairViews.awaitTicks(client, minecraft -> PortalPairViews.viewAt(minecraft, pair, PortalPairScene.Side.LOCAL), SETTLE_TICKS);
            assertTrue(shown < 0, label + ": the one-way arrival portal drew a view " + shown + " ticks after turning back although it does not lead back: "
                + client.computeOnClient(PortalPairViews::describe));
            LOGGER.info("[{}] one-way arrival portal view absent for {} ticks after turning back: {}", label, SETTLE_TICKS,
                client.computeOnClient(PortalPairViews::describe));
        }
        Removal removal = new Removal(label, pair, PortalPairScene.Side.LOCAL, PortalPairScene.Stance.BEHIND, PortalPairScene.Side.LOCAL,
            expectation(server, pair, spec, PortalPairScene.Side.LOCAL));
        remove(client, server, removal, Method.MENU);
        return removal;
    }

    static void assertReloaded(SeamlessClient client, SeamlessServer server, Removal removal) {
        SeamlessScenario.join(client);
        server.stance(removal.pair(), removal.viewer(), removal.stance());
        PortalPairViews.awaitPlaced(client, server, removal.pair().level(removal.viewer()), removal.label() + " after relog");
        assertDeletion(server, removal, removal.label() + " after relog");
        assertClear(client, removal, removal.label() + " after relog");
        capture(client, removal.label() + "-after-relog");
        Vec3 position = server.playerPosition();
        ResourceKey<Level> level = removal.pair().level(removal.viewer());
        server.approachFrom(level, position.add(FAR_BLOCKS, 0.0D, 0.0D), 180.0F);
        client.waitForChunksDownload();
        client.waitTicks(SETTLE_TICKS);
        server.stance(removal.pair(), removal.viewer(), removal.stance());
        PortalPairViews.awaitPlaced(client, server, level, removal.label() + " after chunk reload");
        assertClear(client, removal, removal.label() + " after chunk reload");
        server.removePortalPair(removal.pair());
    }

    static void finish(SeamlessClient client, SeamlessServer server, Removal removal) {
        client.restoreDefaultGameOptions();
        server.removePortalPair(removal.pair());
    }

    private static void remove(SeamlessClient client, SeamlessServer server, Removal removal, Method method) {
        PortalPairScene.Pair pair = removal.pair();
        PortalPairScene.Side removed = removal.removed();
        int gunpowder = server.playerGunpowder();
        int notices = SystemNotices.mark();
        capture(client, removal.label() + "-before-removal");
        if (method == Method.MENU) {
            PortalPairViews.deleteThroughMenu(client, pair, removed);
        } else {
            PortalPairViews.deleteThroughCommand(client, pair, removed);
        }
        int deleted = awaitServer(client, server, pair, removed);
        assertTrue(deleted >= 0, removal.label() + ": the " + method + " removal did not delete the " + removed + " portal on the server");
        LOGGER.info("[{}] {} removal deleted the {} portal {} ticks after the request; gunpowder in inventory {} -> server {} client {}",
            removal.label(), method, removed, deleted, gunpowder, server.playerGunpowder(), client.computeOnClient(PortalPairViews::clientGunpowder));
        int cleared = PortalPairViews.awaitTicks(client, minecraft -> PortalPairViews.remaining(minecraft, pair, removed).isEmpty(), CLEAR_TICKS);
        if (cleared < 0) {
            String remaining = client.computeOnClient(minecraft -> PortalPairViews.remaining(minecraft, pair, removed));
            String state = client.computeOnClient(PortalPairViews::describe);
            capture(client, removal.label() + "-still-projected");
            int persisted = PortalPairViews.awaitTicks(client, minecraft -> PortalPairViews.remaining(minecraft, pair, removed).isEmpty(), PERSIST_TICKS);
            throw new AssertionError(removal.label() + ": " + CLEAR_TICKS + " ticks after the " + removed + " portal was removed the client still holds "
                + remaining + " (" + (persisted < 0 ? "still present " + (CLEAR_TICKS + PERSIST_TICKS) + " ticks after removal"
                : "released " + (CLEAR_TICKS + persisted) + " ticks after removal") + "); " + state);
        }
        capture(client, removal.label() + "-after-removal");
        LOGGER.info("[{}] client released every view of the pair {} ticks after removal", removal.label(), cleared);
        List<String> failures = new ArrayList<>(3);
        collect(failures, () -> assertDeletion(server, removal, removal.label() + " after removal"));
        collect(failures, () -> assertNotice(client, removal, notices));
        if (method == Method.MENU) {
            collect(failures, () -> assertInventory(client, server, removal, gunpowder));
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
        assertClear(client, removal, removal.label() + " after removal");
    }

    private static void collect(List<String> failures, Runnable check) {
        try {
            check.run();
        } catch (AssertionError failure) {
            failures.add(failure.getMessage());
        }
    }

    private static Expectation expectation(SeamlessServer server, PortalPairScene.Pair pair, PortalPairScene.Spec spec, PortalPairScene.Side removed) {
        String localName = server.computeOnServer(minecraftServer -> PortalPairScene.name(minecraftServer, pair.local()));
        Map<String, String> returnLink = Map.of("type", pair.remoteLevel() == Level.OVERWORLD ? "LOCAL" : "DIMENSIONAL",
            "destination", pair.portal(removed).toString());
        boolean sameWorld = pair.remoteLevel() == Level.OVERWORLD;
        return switch (spec.link()) {
            case LINK_AND_RETURN -> new Expectation(!sameWorld, Map.of(), localName);
            case MUTUAL -> new Expectation(!sameWorld, sameWorld ? returnLink : Map.of(), localName);
            case ONE_WAY -> new Expectation(false, removed == spec.start() ? Map.of() : returnLink, localName);
        };
    }

    private static void assertDeletion(SeamlessServer server, Removal removal, String label) {
        PortalPairScene.Side partnerSide = removal.removed() == PortalPairScene.Side.LOCAL ? PortalPairScene.Side.REMOTE : PortalPairScene.Side.LOCAL;
        PortalPairScene.Stored removed = server.computeOnServer(minecraftServer -> PortalPairScene.stored(minecraftServer, removal.pair().portal(removal.removed())));
        PortalPairScene.Stored partner = server.computeOnServer(minecraftServer -> PortalPairScene.stored(minecraftServer, removal.pair().portal(partnerSide)));
        assertTrue(removed.absent(), label + ": the removed " + removal.removed() + " portal is still registered or saved: " + removed);
        Expectation expected = removal.expectation();
        if (expected.partnerDeleted()) {
            assertTrue(partner.absent(), label + ": the " + partnerSide + " partner of a pair that links back across worlds survived the deletion: " + partner);
        } else {
            assertTrue(partner.keeps(expected.partnerTunnel()), label + ": the surviving " + partnerSide + " partner should keep tunnel "
                + expected.partnerTunnel() + " without a Link and return mark, but holds " + partner);
        }
        LOGGER.info("[{}] deletion contract holds: removed {} partner {}", label, removed, partner);
    }

    private static void assertNotice(SeamlessClient client, Removal removal, int mark) {
        if (removal.removed() != PortalPairScene.Side.LOCAL && !removal.expectation().partnerDeleted()) {
            return;
        }
        String expected = removal.expectation().localName();
        int shown = PortalPairViews.awaitTicks(client, minecraft -> deletedNotice(SystemNotices.since(mark), expected), NOTICE_TICKS);
        assertTrue(shown >= 0, removal.label() + ": no '" + expected + " Deleted' notice reached the player within " + NOTICE_TICKS + " ticks; notices "
            + SystemNotices.since(mark));
        LOGGER.info("[{}] deletion notice shown: {}", removal.label(), SystemNotices.since(mark));
    }

    private static boolean deletedNotice(List<String> notices, String portal) {
        for (String notice : notices) {
            if (notice.contains(portal) && notice.contains("Deleted")) {
                return true;
            }
        }
        return false;
    }

    private static void assertInventory(SeamlessClient client, SeamlessServer server, Removal removal, int before) {
        int held = server.playerGunpowder();
        assertTrue(held == before, removal.label() + ": the Delete Portal shift-click changed the server inventory from " + before + " to " + held + " gunpowder");
        int settled = PortalPairViews.awaitTicks(client, minecraft -> PortalPairViews.clientGunpowder(minecraft) == before, INVENTORY_TICKS);
        assertTrue(settled >= 0, removal.label() + ": " + INVENTORY_TICKS + " ticks after the Delete Portal shift-click the client inventory holds "
            + client.computeOnClient(PortalPairViews::clientGunpowder) + " gunpowder while the server holds " + held);
        client.waitTicks(INVENTORY_TICKS);
        int lingering = client.computeOnClient(PortalPairViews::clientGunpowder);
        assertTrue(lingering == before && server.playerGunpowder() == before, removal.label() + ": after resynchronising, the client inventory holds "
            + lingering + " gunpowder and the server " + server.playerGunpowder() + "; both should hold " + before);
        LOGGER.info("[{}] inventory resynchronised {} ticks after the Delete Portal click: client and server hold {} gunpowder", removal.label(), settled, before);
    }

    private static void capture(SeamlessClient client, String name) {
        Path output = client.computeOnClient(minecraft -> minecraft.gameDirectory.toPath()
            .resolve("screenshots/portal-removal/" + name.replaceAll("[^a-zA-Z0-9-]", "-") + ".png"));
        AtomicReference<FramebufferProbe> captured = new AtomicReference<>();
        client.runOnClient(minecraft -> FramebufferProbe.capture(minecraft, output, captured::set));
        PortalPairViews.awaitTicks(client, minecraft -> captured.get() != null, CAPTURE_TICKS);
    }

    private static void assertClear(SeamlessClient client, Removal removal, String label) {
        for (int tick = 0; tick < OBSERVE_TICKS; tick++) {
            String remaining = client.computeOnClient(minecraft -> PortalPairViews.remaining(minecraft, removal.pair(), removal.removed()));
            assertTrue(remaining.isEmpty(), label + ": the removed portal pair is still projected: " + remaining + "; "
                + client.computeOnClient(PortalPairViews::describe));
            client.waitTicks(1);
        }
        LOGGER.info("WORMHOLES_PORTAL_REMOVAL_CLEAR {}", label);
    }

    private static int awaitServer(SeamlessClient client, SeamlessServer server, PortalPairScene.Pair pair, PortalPairScene.Side removed) {
        for (int tick = 0; tick <= REMOVE_TICKS; tick++) {
            if (!server.portalExists(pair.portal(removed))) {
                return tick;
            }
            client.waitTicks(1);
        }
        return -1;
    }

    private static void awaitView(SeamlessClient client, PortalPairScene.Pair pair, PortalPairScene.Side side, String label) {
        client.waitForChunksDownload();
        int shown = PortalPairViews.awaitTicks(client, minecraft -> PortalPairViews.viewAt(minecraft, pair, side), VIEW_TICKS);
        assertTrue(shown >= 0, label + ": the " + side + " portal view never appeared: " + client.computeOnClient(PortalPairViews::describe));
        LOGGER.info("[{}] {} portal view present after {} ticks: {}", label, side, shown, client.computeOnClient(PortalPairViews::describe));
    }

    private static void await(SeamlessClient client, Predicate<Minecraft> condition, int ticks, String failure) {
        assertTrue(PortalPairViews.awaitTicks(client, condition, ticks) >= 0, failure + ": " + client.computeOnClient(PortalPairViews::describe));
    }

    private static String dimension(Minecraft minecraft) {
        return minecraft.level == null ? "" : minecraft.level.dimension().identifier().toString();
    }

    enum Method {
        MENU,
        COMMAND
    }

    record Removal(String label, PortalPairScene.Pair pair, PortalPairScene.Side viewer, PortalPairScene.Stance stance, PortalPairScene.Side removed,
                   Expectation expectation) {
    }

    record Expectation(boolean partnerDeleted, Map<String, String> partnerTunnel, String localName) {
    }
}
