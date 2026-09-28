package art.arcane.wormholes.modded;

import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.rules.Cost;
import art.arcane.wormholes.rules.Effect;
import art.arcane.wormholes.rules.ItemMatcher;
import art.arcane.wormholes.rules.Rule;
import art.arcane.wormholes.rules.RuleDocument;
import art.arcane.wormholes.rules.RuleOutcome;
import art.arcane.wormholes.rules.TraversalProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class MinecraftRulesGameTest {
    private final GameTestHelper helper;
    private final WormholesModRuntime runtime;
    private final ServerPlayer player;
    private final MinecraftGameTestPlayer connection;
    private final MinecraftPortal source;
    private final MinecraftPortal destination;
    private final AtomicInteger retried = new AtomicInteger();
    private boolean cleaned;
    private int messagesBeforePaid;

    private MinecraftRulesGameTest(GameTestHelper helper) {
        this.helper = helper;
        runtime = WormholesGameTests.RUNTIME;
        connection = MinecraftGameTestPlayer.connect(runtime, helper.getLevel(), "rule-traveler");
        player = connection.player();
        player.setGameMode(GameType.SURVIVAL);
        source = runtime.portals().create(player.getUUID(), helper.getLevel(), cells(2), PortalType.PORTAL, new Vec3(0, 0, -1));
        destination = runtime.portals().create(player.getUUID(), helper.getLevel(), cells(14), PortalType.PORTAL, new Vec3(0, 0, -1));
        helper.assertTrue(runtime.portals().link(player, source.getId(), destination.getId()), "Rules fixture link failed");
    }

    public static void run(GameTestHelper helper) {
        MinecraftRulesGameTest test = new MinecraftRulesGameTest(helper);
        try {
            test.start();
        } catch (RuntimeException error) {
            test.cleanup();
            throw error;
        }
    }

    private void start() {
        runtime.schedule(this::cleanup, 1190);
        for (int x = 0; x < 20; x++) {
            for (int z = 1; z < 8; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.STONE);
            }
        }
        editor();
        paymentRollback();
        runtime.rules().setDocument(source, RuleDocument.EMPTY.withProfile(profile(0, 400)));
        approach();
        helper.assertTrue(!runtime.rules().depart(player, source, retried::incrementAndGet), "Warmup did not defer");
        player.setPos(player.getX() + 2, player.getY(), player.getZ());
        runtime.rules().tick();
        helper.startSequence()
            .thenIdle(12)
            .thenExecute(() -> {
                helper.assertTrue(retried.get() == 0, "Moving warmup traveler retried the crossing");
                runtime.rules().disconnected(player);
                approach();
                helper.assertTrue(!runtime.rules().depart(player, source, retried::incrementAndGet), "Damage warmup did not defer");
                float health = player.getHealth();
                helper.assertTrue(player.hurtServer(helper.getLevel(), helper.getLevel().damageSources().generic(), 1.0F), "Warmup fixture damage was rejected");
                helper.assertTrue(player.getHealth() < health, "Warmup fixture damage did not reduce health");
            })
            .thenIdle(12)
            .thenExecute(() -> {
                helper.assertTrue(retried.get() == 0, "Damaged warmup traveler retried the crossing");
                runtime.rules().disconnected(player);
                runtime.rules().setDocument(source, RuleDocument.EMPTY.withDefaultOutcome(RuleOutcome.deny("rules.denied.default")));
                approach();
            })
            .thenIdle(3)
            .thenExecute(this::cross)
            .thenIdle(12)
            .thenExecute(() -> {
                helper.assertTrue(!arrived(), "Denied native traversal crossed portal");
                helper.assertTrue(player.getInventory().getItem(0).getCount() == 20, "Denied crossing charged rule items");
                approach();
            })
            .thenIdle(3)
            .thenExecute(() -> {
                runtime.rules().disconnected(player);
                runtime.rules().setDocument(source, paid(profile(3000, 400)));
                messagesBeforePaid = connection.messages().size();
                approach();
            })
            .thenIdle(3)
            .thenExecute(this::cross)
            .thenIdle(15)
            .thenExecute(() -> helper.assertTrue(arrived() || connection.messages().size() > messagesBeforePaid,
                "Paid crossing never reached the rules gate: " + diagnostic()))
            .thenWaitUntil(() -> helper.assertTrue(arrived(), "Warmup traversal did not arrive: " + diagnostic()))
            .thenExecute(() -> {
                helper.assertTrue(player.getInventory().getItem(0).getCount() == 17, "Rule toll was not charged exactly once");
                helper.assertTrue(!runtime.rules().depart(player, source, retried::incrementAndGet), "Source profile cooldown allowed immediate traversal");
                runtime.rules().failed(player);
                helper.assertTrue(player.getInventory().getItem(0).getCount() == 17, "Committed rule toll was refunded");
                LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS rules_runtime editor rule_lines denial rollback warmup_move warmup_damage paid_traversal cooldown commit_once");
                cleanup();
            })
            .thenSucceed();
    }

    private void editor() {
        runtime.menus().rules().open(player, source.getId());
        click(10);
        click(12);
        helper.assertTrue(runtime.rules().document(source).profile().cooldownMillis() == 1000L
            && runtime.rules().document(source).profile().warmupMillis() == 1000L, "Rule profile editor did not persist");
        click(30);
        click(47);
        helper.assertTrue(runtime.menus().rules().acceptChat(player, "toll"), "Rule id prompt did not consume native chat");
        click(0);
        click(47);
        helper.assertTrue(runtime.menus().rules().acceptChat(player, "kind=ITEM;matcher.material=EMERALD;quantity=3"), "Rule line prompt did not consume native chat");
        helper.assertTrue(runtime.rules().document(source).rules().size() == 1
            && !runtime.rules().document(source).rules().getFirst().costs().isEmpty(), "Native rule line editor did not persist cost");
        player.closeContainer();
    }

    private void paymentRollback() {
        player.getInventory().setItem(0, new ItemStack(Items.EMERALD, 20));
        runtime.rules().setDocument(source, paid(TraversalProfile.DEFAULT));
        helper.assertTrue(runtime.rules().depart(player, source, retried::incrementAndGet), "Affordable rule gate refused player");
        helper.assertTrue(runtime.rules().reserve(player, source), "Rule reservation failed");
        helper.assertTrue(player.getInventory().getItem(0).getCount() == 17, "Rule reservation did not take three items");
        helper.assertTrue(!runtime.rules().reserve(player, source), "Rule reservation charged twice");
        runtime.rules().failed(player);
        helper.assertTrue(player.getInventory().getItem(0).getCount() == 20, "Failed delivery did not refund rule items");
    }

    private RuleDocument paid(TraversalProfile profile) {
        return new RuleDocument(List.of(new Rule("toll", List.of(), RuleOutcome.allow(),
            List.of(new Cost.Item(ItemMatcher.material("EMERALD"), 3)), List.of(new Effect.Message("Arrived")))),
            RuleOutcome.allow(), profile, 0);
    }

    private static TraversalProfile profile(long cooldown, long warmup) {
        return new TraversalProfile(cooldown, "", warmup, 1.0D, 1.0D, 0, 0);
    }

    private void click(int slot) {
        helper.assertTrue(player.containerMenu instanceof MinecraftInventoryMenu, "Rules inventory was not open");
        player.containerMenu.clicked(slot, 0, ContainerInput.PICKUP, player);
    }

    private String diagnostic() {
        return "position=" + player.position() + " health=" + player.getHealth() + " items=" + player.getInventory().getItem(0).getCount()
            + " messages=" + connection.messages().stream().map(Component::getString).toList();
    }

    private boolean arrived() {
        return player.getX() > helper.absolutePos(new BlockPos(10, 0, 0)).getX();
    }

    private void approach() {
        Vec3 start = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 2, 3)));
        player.setPos(start);
        player.xo = start.x;
        player.yo = start.y;
        player.zo = start.z;
        player.setDeltaMovement(Vec3.ZERO);
    }

    private void cross() {
        Vec3 start = Vec3.atBottomCenterOf(helper.absolutePos(new BlockPos(3, 2, 3)));
        player.xo = start.x;
        player.yo = start.y;
        player.zo = start.z;
        player.setPos(start.x, start.y, start.z + 1.5);
    }

    private List<BlockPos> cells(int x) {
        List<BlockPos> cells = new ArrayList<>();
        for (int y = 2; y <= 4; y++) {
            cells.add(helper.absolutePos(new BlockPos(x, y, 4)));
            cells.add(helper.absolutePos(new BlockPos(x + 1, y, 4)));
        }
        return cells;
    }

    private void cleanup() {
        if (cleaned) {
            return;
        }
        cleaned = true;
        player.closeContainer();
        runtime.rules().disconnected(player);
        runtime.menus().playerDisconnected(player);
        runtime.portals().remove(player, source.getId());
        runtime.portals().remove(player, destination.getId());
        connection.close();
    }
}
