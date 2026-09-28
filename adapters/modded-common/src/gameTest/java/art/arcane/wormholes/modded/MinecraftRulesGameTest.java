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
import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.wormholes.localization.RulesMessages;
import art.arcane.wormholes.rules.RulesMenuModel;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

public final class MinecraftRulesGameTest {
    private static final int CLICK_TICKS = 2;
    private static final int PROMPT_TICKS = 3;

    private final GameTestHelper helper;
    private final WormholesModRuntime runtime;
    private final ServerPlayer player;
    private final MinecraftGameTestPlayer connection;
    private final MinecraftGameTestPlayer guest;
    private final MinecraftPortal source;
    private final MinecraftPortal destination;
    private final MinecraftPortal subsystem;
    private final AtomicInteger retried = new AtomicInteger();
    private boolean cleaned;
    private int messagesBeforePaid;

    private MinecraftRulesGameTest(GameTestHelper helper) {
        this.helper = helper;
        runtime = WormholesGameTests.RUNTIME;
        connection = MinecraftGameTestPlayer.connect(runtime, helper.getLevel(), "rule-traveler");
        player = connection.player();
        player.setGameMode(GameType.SURVIVAL);
        guest = MinecraftGameTestPlayer.connect(runtime, helper.getLevel(), "rule-guest");
        source = runtime.portals().create(player.getUUID(), helper.getLevel(), cells(2), PortalType.PORTAL, new Vec3(0, 0, -1));
        destination = runtime.portals().create(player.getUUID(), helper.getLevel(), cells(14), PortalType.PORTAL, new Vec3(0, 0, -1));
        subsystem = runtime.portals().create(player.getUUID(), helper.getLevel(), cells(8), PortalType.PORTAL, new Vec3(0, 0, -1));
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
        GameTestSequence sequence = helper.startSequence();
        editor(sequence);
        new MinecraftSubsystemMenuGameTest(helper, runtime, connection, guest, subsystem).append(sequence)
            .thenExecute(() -> {
                paymentRollback();
                runtime.rules().setDocument(source, RuleDocument.EMPTY.withProfile(profile(0, 400)));
                approach();
                helper.assertTrue(!runtime.rules().depart(player, source, retried::incrementAndGet), "Warmup did not defer");
                player.setPos(player.getX() + 2, player.getY(), player.getZ());
                runtime.rules().tick();
            })
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

    private void editor(GameTestSequence sequence) {
        MinecraftRulesMenuEntry entry = new MinecraftRulesMenuEntry(runtime);
        sequence.thenExecute(() -> {
            helper.assertTrue(entry.id().equals("rules") && entry.icon() == Items.BOOK && entry.label() == RulesMessages.MENU_ENTRY,
                "Rules entry identity differs from Bukkit");
            helper.assertTrue(!entry.enchanted(source, player), "Inert rules entry glowed");
            entry.onLeftClick(source, player, new MinecraftWindow(runtime, player));
        }).thenIdle(CLICK_TICKS).thenExecute(() -> {
            assertProfile(0L, 0L, "-", RuleOutcome.Kind.ALLOW);
            MinecraftSubsystemMenuProbe.left(player, -3, 0);
        }).thenIdle(CLICK_TICKS).thenExecute(() -> {
            MinecraftSubsystemMenuProbe.left(player, -1, 0);
        }).thenIdle(CLICK_TICKS).thenExecute(() -> {
            helper.assertTrue(profileOf().cooldownMillis() == 1000L && profileOf().warmupMillis() == 1000L,
                "Rule profile left clicks did not add one second");
            assertProfile(1000L, 1000L, "-", RuleOutcome.Kind.ALLOW);
            MinecraftSubsystemMenuProbe.shiftLeft(player, -3, 0);
            helper.assertTrue(profileOf().cooldownMillis() == 11_000L, "Rule profile shift-left click did not add ten seconds");
            MinecraftSubsystemMenuProbe.right(player, -3, 0);
            helper.assertTrue(profileOf().cooldownMillis() == 10_000L, "Rule profile right click did not remove one second");
            MinecraftSubsystemMenuProbe.shiftRight(player, -3, 0);
            helper.assertTrue(profileOf().cooldownMillis() == 0L, "Rule profile shift-right click did not remove ten seconds");
            MinecraftSubsystemMenuProbe.right(player, -2, 1);
            helper.assertTrue(profileOf().pushbackScale() == 0.75D, "Rule pushback right click did not lower the scale");
            MinecraftSubsystemMenuProbe.left(player, 2, 1);
        }).thenIdle(CLICK_TICKS).thenExecute(() -> {
            helper.assertTrue(!runtime.rules().document(source).defaultOutcome().allowed(), "Default outcome toggle did not deny");
            assertProfile(0L, 1000L, "-", RuleOutcome.Kind.DENY);
            helper.assertTrue(entry.enchanted(source, player), "Active rules entry did not glow");
            MinecraftSubsystemMenuProbe.left(player, 2, 1);
        }).thenIdle(CLICK_TICKS).thenExecute(() -> {
            helper.assertTrue(runtime.rules().document(source).defaultOutcome().allowed(), "Default outcome toggle did not allow");
            MinecraftSubsystemMenuProbe.left(player, 1, 0);
        }).thenIdle(CLICK_TICKS).thenExecute(() -> {
            MinecraftSubsystemMenuProbe.assertClosed(helper, player, "Rule group prompt");
            helper.assertTrue(MinecraftChatInput.chat(player, "cancel"), "Rule group cancel did not consume chat");
        }).thenIdle(PROMPT_TICKS).thenExecute(() -> {
            assertProfile(0L, 1000L, "-", RuleOutcome.Kind.ALLOW);
            MinecraftSubsystemMenuProbe.left(player, 1, 0);
        }).thenIdle(CLICK_TICKS).thenExecute(() -> {
            helper.assertTrue(MinecraftChatInput.chat(player, "gates"), "Rule group prompt did not consume chat");
        }).thenIdle(PROMPT_TICKS).thenExecute(() -> {
            helper.assertTrue(profileOf().cooldownGroup().equals("gates"), "Rule group prompt did not apply");
            assertProfile(0L, 1000L, "gates", RuleOutcome.Kind.ALLOW);
            MinecraftSubsystemMenuProbe.left(player, -1, 2);
        }).thenIdle(CLICK_TICKS).thenExecute(() -> {
            assertList(Items.STAINED_GLASS_PANE.gray(), RulesMessages.MENU_ADD_RULE, 0);
            MinecraftSubsystemMenuProbe.left(player, -2, 5);
        }).thenIdle(CLICK_TICKS).thenExecute(() -> {
            MinecraftSubsystemMenuProbe.assertClosed(helper, player, "Rule id prompt");
            helper.assertTrue(MinecraftChatInput.chat(player, "toll"), "Rule id prompt did not consume native chat");
        }).thenIdle(PROMPT_TICKS).thenExecute(() -> {
            assertList(Items.STAINED_GLASS_PANE.gray(), RulesMessages.MENU_ADD_RULE, 1);
            Rule rule = runtime.rules().document(source).rules().getFirst();
            MinecraftSubsystemMenuProbe.assertElement(helper, player, -4, 0, Items.PAPER,
                MinecraftSubsystemMenuProbe.name(player, RulesMessages.MENU_RULE, MinecraftPortalText.arguments("name", "toll",
                    "value", RulesMenuModel.summary(rule))), false, "Rule list entry");
            MinecraftSubsystemMenuProbe.left(player, -4, 0);
        }).thenIdle(CLICK_TICKS).thenExecute(() -> {
            assertList(Items.STAINED_GLASS_PANE.cyan(), RulesMessages.MENU_ADD_LINE, 0);
            MinecraftSubsystemMenuProbe.left(player, -2, 5);
        }).thenIdle(CLICK_TICKS).thenExecute(() -> {
            helper.assertTrue(MinecraftChatInput.chat(player, "kind=ITEM;matcher.material=EMERALD;quantity=3"), "Rule line prompt did not consume native chat");
        }).thenIdle(PROMPT_TICKS).thenExecute(() -> {
            helper.assertTrue(runtime.rules().document(source).rules().size() == 1
                && !runtime.rules().document(source).rules().getFirst().costs().isEmpty(), "Native rule line editor did not persist cost");
            assertList(Items.STAINED_GLASS_PANE.cyan(), RulesMessages.MENU_ADD_LINE, 1);
            MinecraftSubsystemMenuProbe.assertElement(helper, player, -4, 0, Items.STRING,
                MinecraftSubsystemMenuProbe.name(player, RulesMessages.MENU_LINE, MinecraftPortalText.arguments("value",
                    RulesMenuModel.lines(runtime.rules().document(source).rules().getFirst()).getFirst())), false, "Rule line entry");
            MinecraftSubsystemMenuProbe.shiftLeft(player, -4, 0);
            helper.assertTrue(runtime.rules().document(source).rules().getFirst().costs().isEmpty(), "Rule line shift-left click did not remove the line");
            MinecraftSubsystemMenuProbe.left(player, 2, 5);
        }).thenIdle(CLICK_TICKS).thenExecute(() -> {
            assertList(Items.STAINED_GLASS_PANE.gray(), RulesMessages.MENU_ADD_RULE, 1);
            MinecraftSubsystemMenuProbe.shiftLeft(player, -4, 0);
            helper.assertTrue(runtime.rules().document(source).rules().isEmpty(), "Rule shift-left click did not remove the rule");
            MinecraftSubsystemMenuProbe.left(player, 2, 5);
        }).thenIdle(CLICK_TICKS).thenExecute(() -> {
            assertProfile(0L, 1000L, "gates", RuleOutcome.Kind.ALLOW);
            MinecraftSubsystemMenuProbe.left(player, 1, 2);
        }).thenIdle(CLICK_TICKS).thenExecute(() -> {
            MinecraftSubsystemMenuProbe.assertWindow(helper, player, MinecraftPortalText.router(runtime, source, true), 6,
                Items.STAINED_GLASS_PANE.lightBlue(), MinecraftSubsystemMenuProbe.slot(-2, 5), "Rule templates");
            MinecraftSubsystemMenuProbe.assertElement(helper, player, 2, 5, Items.ARROW,
                MinecraftSubsystemMenuProbe.name(player, RulesMessages.MENU_BACK, MessageArgs.empty()), false, "Rule templates back");
            MinecraftSubsystemMenuProbe.left(player, 2, 5);
        }).thenIdle(CLICK_TICKS).thenExecute(() -> {
            assertProfile(0L, 1000L, "gates", RuleOutcome.Kind.ALLOW);
            player.closeContainer();
            LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS rules_menu profile_layout click_types default_outcome group_prompt cancel rule_list rule_lines templates back_navigation");
        });
    }

    private TraversalProfile profileOf() {
        return runtime.rules().document(source).profile();
    }

    private void assertProfile(long cooldown, long warmup, String group, RuleOutcome.Kind outcome) {
        MinecraftSubsystemMenuProbe.assertWindow(helper, player, MinecraftPortalText.router(runtime, source, true), 4,
            Items.STAINED_GLASS_PANE.brown(), MinecraftSubsystemMenuProbe.slot(0, 3), "Rule profile");
        TraversalProfile profile = profileOf();
        MinecraftSubsystemMenuProbe.assertElement(helper, player, -3, 0, Items.CLOCK,
            MinecraftSubsystemMenuProbe.name(player, RulesMessages.MENU_COOLDOWN, MinecraftPortalText.arguments("seconds", cooldown / 1000L)),
            false, "Rule cooldown");
        MinecraftSubsystemMenuProbe.assertElement(helper, player, -1, 0, Items.COMPASS,
            MinecraftSubsystemMenuProbe.name(player, RulesMessages.MENU_WARMUP, MinecraftPortalText.arguments("seconds", warmup / 1000L)),
            false, "Rule warmup");
        MinecraftSubsystemMenuProbe.assertElement(helper, player, 1, 0, Items.NAME_TAG,
            MinecraftSubsystemMenuProbe.name(player, RulesMessages.MENU_GROUP, MinecraftPortalText.arguments("value", group)), false, "Rule group");
        MinecraftSubsystemMenuProbe.assertElement(helper, player, 3, 0, Items.AMETHYST_SHARD,
            MinecraftSubsystemMenuProbe.name(player, RulesMessages.MENU_CHARGES, MinecraftPortalText.arguments("count", profile.chargeCapacity())),
            false, "Rule charges");
        MinecraftSubsystemMenuProbe.assertElement(helper, player, -2, 1, Items.PISTON,
            MinecraftSubsystemMenuProbe.name(player, RulesMessages.MENU_PUSHBACK, MinecraftPortalText.arguments("value",
                String.format(Locale.ROOT, "%.2f", profile.pushbackScale()))), false, "Rule pushback");
        MinecraftSubsystemMenuProbe.assertElement(helper, player, 0, 1, Items.NOTE_BLOCK,
            MinecraftSubsystemMenuProbe.name(player, RulesMessages.MENU_SOUND, MinecraftPortalText.arguments("value",
                String.format(Locale.ROOT, "%.2f", profile.soundVolume()))), false, "Rule sound");
        MinecraftSubsystemMenuProbe.assertElement(helper, player, 2, 1, Items.LEVER,
            MinecraftSubsystemMenuProbe.name(player, RulesMessages.MENU_DEFAULT_OUTCOME, MinecraftPortalText.arguments("mode", outcome.name())),
            outcome != RuleOutcome.Kind.ALLOW, "Rule default outcome");
        MinecraftSubsystemMenuProbe.assertElement(helper, player, -1, 2, Items.BOOK,
            MinecraftSubsystemMenuProbe.name(player, RulesMessages.MENU_RULES, MinecraftPortalText.arguments("count",
                runtime.rules().document(source).rules().size())), false, "Rule list opener");
        MinecraftSubsystemMenuProbe.assertElement(helper, player, 1, 2, Items.WRITABLE_BOOK,
            MinecraftSubsystemMenuProbe.name(player, RulesMessages.MENU_TEMPLATES, MessageArgs.empty()), false, "Rule templates opener");
    }

    private void assertList(Item pane, LinesKey add, int entries) {
        MinecraftSubsystemMenuProbe.assertWindow(helper, player, MinecraftPortalText.router(runtime, source, true), 6, pane,
            MinecraftSubsystemMenuProbe.slot(4, 4), "Rule list");
        MinecraftSubsystemMenuProbe.assertElement(helper, player, -2, 5, Items.DYE.lime(),
            MinecraftSubsystemMenuProbe.name(player, add, MessageArgs.empty()), false, "Rule add");
        MinecraftSubsystemMenuProbe.assertElement(helper, player, 0, 5, Items.PAPER,
            MinecraftSubsystemMenuProbe.name(player, RulesMessages.MENU_PAGE, MinecraftPortalText.arguments("page", 1, "pages",
                RulesMenuModel.pageCount(entries))), false, "Rule page");
        MinecraftSubsystemMenuProbe.assertElement(helper, player, 2, 5, Items.ARROW,
            MinecraftSubsystemMenuProbe.name(player, RulesMessages.MENU_BACK, MessageArgs.empty()), false, "Rule back");
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
        runtime.portals().remove(player, subsystem.getId());
        guest.close();
        connection.close();
    }
}
