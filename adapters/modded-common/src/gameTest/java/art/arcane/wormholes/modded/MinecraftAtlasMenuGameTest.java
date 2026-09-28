package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.config.toml.AtlasConfig;
import art.arcane.wormholes.localization.AtlasMessages;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.portal.PortalType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;

public final class MinecraftAtlasMenuGameTest {
    private static final int PREVIOUS_SLOT = 45;
    private static final int SORT_SLOT = 46;
    private static final int FAVORITES_SLOT = 47;
    private static final int RECENTS_SLOT = 48;
    private static final int PAGE_SLOT = 49;
    private static final int GUIDE_SLOT = 51;
    private static final int NEXT_SLOT = 53;

    private final GameTestHelper helper;
    private final WormholesModRuntime runtime;
    private final MinecraftGameTestPlayer viewer;
    private final AtlasConfig atlas;
    private final boolean discoveryRequired;
    private MinecraftPortal first;
    private MinecraftPortal second;

    private MinecraftAtlasMenuGameTest(GameTestHelper helper, WormholesModRuntime runtime) {
        this.helper = helper;
        this.runtime = runtime;
        viewer = MinecraftGameTestPlayer.connect(runtime, helper.getLevel(), "atlas-viewer");
        atlas = runtime.configuration().settings().getAtlas();
        discoveryRequired = atlas.discoveryRequired;
    }

    public static CompletableFuture<Boolean> verify(GameTestHelper helper, WormholesModRuntime runtime) {
        return new MinecraftAtlasMenuGameTest(helper, runtime).run();
    }

    private CompletableFuture<Boolean> run() {
        ServerPlayer player = viewer.player();
        atlas.discoveryRequired = false;
        first = runtime.portals().create(player.getUUID(), helper.getLevel(), cells(2), PortalType.PORTAL, new Vec3(0, 0, -1));
        second = runtime.portals().create(player.getUUID(), helper.getLevel(), cells(6), PortalType.PORTAL, new Vec3(0, 0, -1));
        command("atlas nonsense");
        for (String line : MinecraftLegacyText.lines(player, AtlasMessages.USAGE, MessageArgs.empty())) {
            helper.assertTrue(received(MinecraftLegacyText.component(line).getString()), "Atlas usage line was not sent: " + line);
        }
        command("atlas");
        return await(() -> window() != null && window().getTitle().equals(title(candidates())))
            .thenCompose(ignored -> {
                layout();
                click(slotOf(first), 1, ContainerInput.PICKUP);
                return await(() -> slotOf(first) == 0 && item(0).has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE)
                    && received(text(AtlasMessages.FAVORITE_ADDED, MinecraftPortalText.arguments("portal", first.getName()))));
            }).thenCompose(ignored -> {
                click(slotOf(first), 0, ContainerInput.QUICK_MOVE);
                return await(() -> item(GUIDE_SLOT).is(Items.COMPASS)
                    && received(text(AtlasMessages.GUIDE_SET, MinecraftPortalText.arguments("portal", first.getName()))));
            }).thenCompose(ignored -> {
                helper.assertTrue(name(GUIDE_SLOT).equals(firstLine(AtlasMessages.MENU_GUIDE, MinecraftPortalText.arguments("portal", first.getName()))),
                    "Atlas guide control does not name the guided portal");
                click(GUIDE_SLOT, 0, ContainerInput.PICKUP);
                return await(() -> item(GUIDE_SLOT).is(Items.STAINED_GLASS_PANE.blue())
                    && received(text(AtlasMessages.GUIDE_CLEARED, MessageArgs.empty())));
            }).thenCompose(ignored -> {
                click(FAVORITES_SLOT, 0, ContainerInput.PICKUP);
                return await(() -> window() != null && window().getTitle().equals(title(1)) && slotOf(first) == 0
                    && name(FAVORITES_SLOT).equals(firstLine(AtlasMessages.MENU_FAVORITES, MinecraftPortalText.arguments("state", "true"))));
            }).thenCompose(ignored -> {
                click(FAVORITES_SLOT, 0, ContainerInput.PICKUP);
                return await(() -> window() != null && window().getTitle().equals(title(candidates())));
            }).thenCompose(ignored -> {
                click(SORT_SLOT, 0, ContainerInput.PICKUP);
                return await(() -> name(SORT_SLOT).equals(sortName(WormholesMessages.PORTAL_MENU_DESTINATION_SORT_NAME)));
            }).thenCompose(ignored -> {
                click(slotOf(second), 0, ContainerInput.PICKUP);
                return await(() -> received(text(AtlasMessages.ROW, MinecraftPortalText.arguments("portal", second.getName(),
                    "destination", second.getWorldKey(), "state", text(second.isOpen() ? WormholesMessages.LABEL_OPEN : WormholesMessages.LABEL_CLOSED,
                        MessageArgs.empty())))));
            }).thenCompose(ignored -> {
                helper.assertTrue(window() != null && window().isVisible(), "Dialing an unnetworked atlas row closed the atlas");
                command("atlas guide " + second.getName());
                return await(() -> received(text(AtlasMessages.GUIDE_SET, MinecraftPortalText.arguments("portal", second.getName()))));
            }).thenApply(ignored -> {
                command("atlas guide off");
                helper.assertTrue(received(text(AtlasMessages.GUIDE_CLEARED, MessageArgs.empty())), "Atlas guide off did not clear the guide");
                LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS atlas_menu usage title six_rows blue_pane rows controls pin guide guide_clear favorites_filter sort dial_row guide_command guide_off");
                return true;
            }).whenCompleteAsync((passed, failure) -> {
                atlas.discoveryRequired = discoveryRequired;
                player.closeContainer();
                runtime.portals().remove(first.getId());
                runtime.portals().remove(second.getId());
                viewer.close();
            }, runtime.server());
    }

    private void layout() {
        MinecraftWindowMenu menu = (MinecraftWindowMenu) viewer.player().containerMenu;
        helper.assertTrue(menu.getRowCount() == 6, "Atlas window is not six rows tall");
        for (MinecraftPortal portal : List.of(first, second)) {
            int slot = slotOf(portal);
            helper.assertTrue(slot >= 0 && slot < 45, "Atlas row for " + portal.getName() + " is missing");
            helper.assertTrue(item(slot).is(Items.ENDER_PEARL), "Unnetworked atlas row is not an ender pearl");
            helper.assertTrue(!item(slot).has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE), "Unpinned atlas row glows");
        }
        for (int slot : List.of(PREVIOUS_SLOT, 50, GUIDE_SLOT, 52, NEXT_SLOT)) {
            helper.assertTrue(item(slot).is(Items.STAINED_GLASS_PANE.blue()), "Atlas control slot " + slot + " is not a blue pane");
        }
        helper.assertTrue(item(SORT_SLOT).is(Items.COMPARATOR) && name(SORT_SLOT).equals(sortName(WormholesMessages.PORTAL_MENU_DESTINATION_SORT_SMART)),
            "Atlas sort control differs");
        helper.assertTrue(item(FAVORITES_SLOT).is(Items.NETHER_STAR)
            && name(FAVORITES_SLOT).equals(firstLine(AtlasMessages.MENU_FAVORITES, MinecraftPortalText.arguments("state", "false"))),
            "Atlas favorites control differs");
        helper.assertTrue(item(RECENTS_SLOT).is(Items.CLOCK)
            && name(RECENTS_SLOT).equals(firstLine(AtlasMessages.MENU_RECENTS, MinecraftPortalText.arguments("state", "false"))),
            "Atlas recents control differs");
        helper.assertTrue(item(PAGE_SLOT).is(Items.PAPER) && name(PAGE_SLOT).equals(firstLine(WormholesMessages.PORTAL_MENU_DESTINATION_PAGE,
            MinecraftPortalText.arguments("page", 1, "pages", 1, "count", candidates()))), "Atlas page control differs");
    }

    private void command(String command) {
        runtime.server().getCommands().performPrefixedCommand(viewer.player().createCommandSourceStack(), command);
    }

    private int candidates() {
        return runtime.atlas().candidates(viewer.player()).size();
    }

    private String title(int count) {
        return MinecraftLegacyText.text(viewer.player(), AtlasMessages.TITLE, MinecraftPortalText.arguments("count", count));
    }

    private String sortName(TextKey mode) {
        return firstLine(WormholesMessages.PORTAL_MENU_DESTINATION_SORT, MinecraftPortalText.arguments("mode", text(mode, MessageArgs.empty())));
    }

    private String firstLine(LinesKey key, MessageArgs arguments) {
        return MinecraftLegacyText.component(MinecraftLegacyText.lines(viewer.player(), key, arguments).getFirst()).getString();
    }

    private String text(TextKey key, MessageArgs arguments) {
        return MinecraftMenuText.text(viewer.player(), key, arguments).getString();
    }

    private boolean received(String expected) {
        for (Component message : viewer.messages()) {
            if (message.getString().equals(expected)) {
                return true;
            }
        }
        return false;
    }

    private int slotOf(MinecraftPortal portal) {
        for (int slot = 0; slot < 45; slot++) {
            if (name(slot).equals(portal.getName()) && (item(slot).is(Items.ENDER_PEARL) || item(slot).is(Items.COMPASS))) {
                return slot;
            }
        }
        return -1;
    }

    private void click(int slot, int button, ContainerInput input) {
        viewer.player().containerMenu.clicked(slot, button, input, viewer.player());
    }

    private MinecraftWindow window() {
        return MinecraftWindow.active(viewer.player());
    }

    private ItemStack item(int slot) {
        return viewer.player().containerMenu instanceof MinecraftWindowMenu menu && slot >= 0 && slot < menu.size() ? menu.item(slot) : ItemStack.EMPTY;
    }

    private String name(int slot) {
        ItemStack item = item(slot);
        return item.isEmpty() ? "" : item.getHoverName().getString();
    }

    private List<BlockPos> cells(int minX) {
        List<BlockPos> cells = new ArrayList<>(9);
        for (int x = minX; x < minX + 3; x++) {
            for (int y = 2; y < 5; y++) {
                cells.add(helper.absolutePos(new BlockPos(x, y, 4)));
            }
        }
        return cells;
    }

    private CompletableFuture<Boolean> await(BooleanSupplier condition) {
        return await(condition, 0);
    }

    private CompletableFuture<Boolean> await(BooleanSupplier condition, int ticks) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        if (!runtime.schedule(() -> {
            try {
                if (condition.getAsBoolean()) {
                    result.complete(true);
                } else if (ticks >= 100) {
                    result.completeExceptionally(new IllegalStateException("Atlas menu change did not happen within 100 ticks"));
                } else {
                    await(condition, ticks + 1).whenComplete((value, failure) -> {
                        if (failure == null) {
                            result.complete(value);
                        } else {
                            result.completeExceptionally(failure);
                        }
                    });
                }
            } catch (RuntimeException failure) {
                result.completeExceptionally(failure);
            }
        }, 1L)) {
            result.completeExceptionally(new IllegalStateException("Atlas menu test scheduler stopped"));
        }
        return result;
    }
}
