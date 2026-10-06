package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.volmlib.util.localization.TextKey;
import art.arcane.wormholes.access.PortalRole;
import art.arcane.wormholes.localization.AccessMessages;
import art.arcane.wormholes.localization.FidelityMessages;
import art.arcane.wormholes.localization.TransitMessages;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.render.FidelitySettings;
import art.arcane.optics.fidelity.AtmosphereMode;
import art.arcane.wormholes.transit.MomentumPolicy;
import art.arcane.wormholes.transit.OrientationPolicy;
import art.arcane.wormholes.transit.TransitionProfile;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.GameTestSequence;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.Items;
import org.slf4j.LoggerFactory;

import java.util.Objects;

final class MinecraftSubsystemMenuGameTest {
    private static final int CLICK_TICKS = 2;
    private static final int PROMPT_TICKS = 3;

    private final GameTestHelper helper;
    private final WormholesModRuntime runtime;
    private final MinecraftGameTestPlayer owner;
    private final MinecraftGameTestPlayer guest;
    private final MinecraftPortal portal;
    private MinecraftWindow opened;

    MinecraftSubsystemMenuGameTest(GameTestHelper helper, WormholesModRuntime runtime, MinecraftGameTestPlayer owner,
                                   MinecraftGameTestPlayer guest, MinecraftPortal portal) {
        this.helper = Objects.requireNonNull(helper);
        this.runtime = Objects.requireNonNull(runtime);
        this.owner = Objects.requireNonNull(owner);
        this.guest = Objects.requireNonNull(guest);
        this.portal = Objects.requireNonNull(portal);
    }

    GameTestSequence append(GameTestSequence sequence) {
        access(sequence);
        fidelity(sequence);
        transit(sequence);
        return sequence.thenExecute(() -> {
            viewer().closeContainer();
            LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS subsystem_menus access_layout access_prompts access_roles access_key access_groups access_listed fidelity_layout fidelity_cycle fidelity_reset transit_layout transit_toggles transit_prompts back_navigation");
        });
    }

    private void access(GameTestSequence sequence) {
        MinecraftAccessMenuEntry entry = new MinecraftAccessMenuEntry(runtime);
        sequence.thenExecute(() -> {
            helper.assertTrue(entry.id().equals("access") && entry.icon() == Items.NAME_TAG && entry.label() == AccessMessages.MENU_ENTRY,
                "Access entry identity differs from Bukkit");
            helper.assertTrue(!entry.enchanted(portal, viewer()), "Access entry glowed without roles");
            entry.onLeftClick(portal, viewer(), new MinecraftWindow(runtime, viewer()));
            assertAccessHeader(1);
            MinecraftSubsystemMenuProbe.left(viewer(), -1, 0);
        }).thenIdle(CLICK_TICKS + 1).thenExecute(() -> {
            MinecraftSubsystemMenuProbe.assertClosed(helper, viewer(), "Access player prompt");
            helper.assertTrue(MinecraftSubsystemMenuProbe.messaged(owner.messages(), MinecraftSubsystemMenuProbe.text(viewer(),
                AccessMessages.PROMPT_PLAYER, MinecraftPortalText.arguments("cancel", label(WormholesMessages.PORTAL_INPUT_CANCEL)))), "Access player prompt was not sent");
            helper.assertTrue(MinecraftChatInput.chat(viewer(), guest.player().getGameProfile().name()), "Access player prompt did not consume chat");
        }).thenIdle(PROMPT_TICKS).thenExecute(() -> {
            helper.assertTrue(portal.role(guest.player().getUUID()) == PortalRole.USER, "Access prompt did not add the player as trusted");
            helper.assertTrue(entry.enchanted(portal, viewer()), "Access entry did not glow with roles");
            assertAccessHeader(2);
            MinecraftSubsystemMenuProbe.assertElement(helper, viewer(), -4, 1, Items.STAINED_GLASS_PANE.lime(),
                MinecraftSubsystemMenuProbe.name(viewer(), AccessMessages.MENU_ROLE, MinecraftPortalText.arguments(
                    "name", guest.player().getGameProfile().name(), "state", label(AccessMessages.LABEL_USER))), false, "Access role");
            MinecraftSubsystemMenuProbe.left(viewer(), -4, 1);
        }).thenIdle(CLICK_TICKS).thenExecute(() -> {
            helper.assertTrue(portal.role(guest.player().getUUID()) == PortalRole.USER.next(), "Left click did not cycle the role forward");
            MinecraftSubsystemMenuProbe.assertElement(helper, viewer(), -4, 1, Items.STAINED_GLASS_PANE.red(),
                MinecraftSubsystemMenuProbe.name(viewer(), AccessMessages.MENU_ROLE, MinecraftPortalText.arguments(
                    "name", guest.player().getGameProfile().name(), "state", label(AccessMessages.LABEL_DENIED))), false, "Access cycled role");
            MinecraftSubsystemMenuProbe.right(viewer(), -4, 1);
            helper.assertTrue(portal.role(guest.player().getUUID()) == PortalRole.USER, "Right click did not cycle the role back");
            MinecraftSubsystemMenuProbe.shiftLeft(viewer(), -4, 1);
            helper.assertTrue(portal.role(guest.player().getUUID()) == null, "Shift-left click did not remove the role");
        }).thenIdle(CLICK_TICKS).thenExecute(() -> {
            assertAccessHeader(1);
            MinecraftSubsystemMenuProbe.left(viewer(), 0, 0);
        }).thenIdle(CLICK_TICKS + 1).thenExecute(() -> {
            MinecraftSubsystemMenuProbe.assertClosed(helper, viewer(), "Access key prompt");
            helper.assertTrue(MinecraftChatInput.chat(viewer(), "subsystem.menu-key"), "Access key prompt did not consume chat");
        }).thenIdle(PROMPT_TICKS).thenExecute(() -> {
            helper.assertTrue(portal.getPermissionKey().equals("subsystem.menu-key"), "Access key prompt did not set the key");
            helper.assertTrue(MinecraftSubsystemMenuProbe.messaged(owner.messages(), MinecraftSubsystemMenuProbe.text(viewer(),
                AccessMessages.KEY_SET, MinecraftPortalText.arguments("portal", portal.getName(), "key", "subsystem.menu-key"))),
                "Access key notice was not sent");
            assertAccessHeader(1);
            MinecraftSubsystemMenuProbe.left(viewer(), 1, 0);
        }).thenIdle(CLICK_TICKS + 1).thenExecute(() -> {
            helper.assertTrue(MinecraftChatInput.chat(viewer(), "subsystem.group"), "Access group prompt did not consume chat");
        }).thenIdle(PROMPT_TICKS).thenExecute(() -> {
            helper.assertTrue(portal.getGroups().contains("subsystem.group"), "Access group prompt did not add the node");
            MinecraftSubsystemMenuProbe.assertElement(helper, viewer(), 1, 0, Items.BOOK,
                MinecraftSubsystemMenuProbe.name(viewer(), AccessMessages.MENU_GROUPS, MinecraftPortalText.arguments("count", 1)), false,
                "Access groups");
            MinecraftSubsystemMenuProbe.right(viewer(), 1, 0);
            helper.assertTrue(portal.getGroups().isEmpty(), "Right click did not clear the access groups");
            MinecraftSubsystemMenuProbe.left(viewer(), 2, 0);
        }).thenIdle(CLICK_TICKS).thenExecute(() -> {
            helper.assertTrue(!portal.isListed(), "Listed toggle did not hide the portal");
            MinecraftSubsystemMenuProbe.assertElement(helper, viewer(), 2, 0, Items.ENDER_PEARL,
                MinecraftSubsystemMenuProbe.name(viewer(), AccessMessages.MENU_LISTED, MinecraftPortalText.arguments("state", label(AccessMessages.LABEL_UNLISTED))),
                false, "Access listed");
            MinecraftSubsystemMenuProbe.left(viewer(), -1, 0);
        }).thenIdle(CLICK_TICKS + 1).thenExecute(() -> {
            helper.assertTrue(MinecraftChatInput.chat(viewer(), "cancel"), "Access cancel did not consume chat");
        }).thenIdle(PROMPT_TICKS).thenExecute(() -> {
            helper.assertTrue(portal.getRoles().isEmpty(), "Cancelled access prompt changed roles");
            assertAccessHeader(1);
        });
    }

    private void assertAccessHeader(int rows) {
        String title = MinecraftPortalText.router(runtime, portal, true);
        MinecraftSubsystemMenuProbe.assertWindow(helper, viewer(), title, rows, Items.STAINED_GLASS_PANE.gray(),
            MinecraftSubsystemMenuProbe.slot(4, 0), "Access window");
        MinecraftSubsystemMenuProbe.assertElement(helper, viewer(), -4, 0, Items.NAME_TAG,
            MinecraftSubsystemMenuProbe.name(viewer(), AccessMessages.MENU_PLACARD, MinecraftPortalText.arguments("portal", portal.getName(),
                "owner", viewer().getGameProfile().name(), "count", portal.getRoles().size(), "key", portal.getPermissionKey())),
            false, "Access placard");
        MinecraftSubsystemMenuProbe.assertElement(helper, viewer(), -1, 0, Items.WRITABLE_BOOK,
            MinecraftSubsystemMenuProbe.name(viewer(), AccessMessages.MENU_ADD, MessageArgs.empty()), false, "Access add");
        MinecraftSubsystemMenuProbe.assertElement(helper, viewer(), 0, 0, Items.TRIPWIRE_HOOK,
            MinecraftSubsystemMenuProbe.name(viewer(), AccessMessages.MENU_KEY, MinecraftPortalText.arguments("key", portal.getPermissionKey())),
            false, "Access key");
        MinecraftSubsystemMenuProbe.assertElement(helper, viewer(), 2, 0, portal.isListed() ? Items.ENDER_EYE : Items.ENDER_PEARL,
            MinecraftSubsystemMenuProbe.name(viewer(), AccessMessages.MENU_LISTED,
                MinecraftPortalText.arguments("state", label(portal.isListed() ? AccessMessages.LABEL_LISTED : AccessMessages.LABEL_UNLISTED))), false, "Access listed");
    }

    private void fidelity(GameTestSequence sequence) {
        MinecraftFidelityMenuEntry entry = new MinecraftFidelityMenuEntry(runtime);
        sequence.thenExecute(() -> {
            viewer().closeContainer();
            helper.assertTrue(entry.id().equals("fidelity") && entry.icon() == Items.SPYGLASS && entry.label() == FidelityMessages.MENU_ENTRY
                && entry.visible(portal, viewer()), "Fidelity entry identity differs from Bukkit");
            entry.onLeftClick(portal, viewer(), new MinecraftWindow(runtime, viewer()));
        }).thenIdle(CLICK_TICKS).thenExecute(() -> {
            assertFidelity(null);
            MinecraftSubsystemMenuProbe.left(viewer(), -3, 1);
        }).thenIdle(CLICK_TICKS + 1).thenExecute(() -> {
            AtmosphereMode expected = FidelitySettings.atmosphereModeDefault.next();
            helper.assertTrue(expected.configName().equals(portal.setting("fidelity.atmosphere")), "Fidelity left click did not cycle atmosphere");
            assertFidelity(expected.configName());
            MinecraftSubsystemMenuProbe.shiftLeft(viewer(), -3, 1);
        }).thenIdle(CLICK_TICKS).thenExecute(() -> {
            helper.assertTrue(portal.setting("fidelity.atmosphere") == null, "Fidelity shift-left click did not restore the default");
            assertFidelity(null);
            MinecraftSubsystemMenuProbe.left(viewer(), 3, 1);
        }).thenIdle(CLICK_TICKS + 1).thenExecute(() -> {
            helper.assertTrue(Boolean.valueOf(!FidelitySettings.blockEntities).equals(portal.setting("fidelity.block_entities")),
                "Fidelity block entity toggle did not persist");
            MinecraftSubsystemMenuProbe.shiftLeft(viewer(), 3, 1);
        }).thenIdle(CLICK_TICKS).thenExecute(() -> {
            helper.assertTrue(portal.setting("fidelity.block_entities") == null, "Fidelity block entity reset did not apply");
            opened = MinecraftWindow.active(viewer());
            MinecraftSubsystemMenuProbe.left(viewer(), 0, 2);
        }).thenIdle(CLICK_TICKS + 1).thenExecute(() -> {
            helper.assertTrue(opened != null && !opened.isVisible() && viewer().containerMenu != viewer().inventoryMenu,
                "Fidelity back did not open the portal menu");
        });
    }

    private void assertFidelity(String atmosphere) {
        MinecraftSubsystemMenuProbe.assertWindow(helper, viewer(), MinecraftPortalText.router(runtime, portal, true), 3,
            Items.STAINED_GLASS_PANE.cyan(), MinecraftSubsystemMenuProbe.slot(-4, 0), "Fidelity window");
        MinecraftSubsystemMenuProbe.assertElement(helper, viewer(), 0, 0, Items.COMPARATOR,
            MinecraftSubsystemMenuProbe.name(viewer(), FidelityMessages.MENU_PLACARD, MessageArgs.empty()), false, "Fidelity placard");
        String fallback = label(FidelityMessages.MENU_DEFAULT);
        MinecraftSubsystemMenuProbe.assertElement(helper, viewer(), -3, 1, Items.STAINED_GLASS.lightBlue(),
            MinecraftSubsystemMenuProbe.legacy(viewer(), FidelityMessages.MENU_ATMOSPHERE,
                MinecraftPortalText.arguments("mode", atmosphere == null ? fallback : atmosphere)), atmosphere != null, "Fidelity atmosphere");
        MinecraftSubsystemMenuProbe.assertElement(helper, viewer(), -1, 1, Items.NOTE_BLOCK,
            MinecraftSubsystemMenuProbe.legacy(viewer(), FidelityMessages.MENU_ACOUSTICS, MinecraftPortalText.arguments("mode", fallback)),
            false, "Fidelity acoustics");
        MinecraftSubsystemMenuProbe.assertElement(helper, viewer(), 1, 1, Items.SPYGLASS,
            MinecraftSubsystemMenuProbe.legacy(viewer(), FidelityMessages.MENU_LOD, MinecraftPortalText.arguments("mode", fallback)),
            false, "Fidelity detail");
        MinecraftSubsystemMenuProbe.assertElement(helper, viewer(), 3, 1, Items.OAK_SIGN,
            MinecraftSubsystemMenuProbe.legacy(viewer(), FidelityMessages.MENU_BLOCK_ENTITIES, MinecraftPortalText.arguments("state", fallback)),
            false, "Fidelity block entities");
        MinecraftSubsystemMenuProbe.assertElement(helper, viewer(), 0, 2, Items.ARROW,
            MinecraftSubsystemMenuProbe.name(viewer(), FidelityMessages.MENU_BACK, MessageArgs.empty()), false, "Fidelity back");
    }

    private void transit(GameTestSequence sequence) {
        MinecraftTransitMenuEntry entry = new MinecraftTransitMenuEntry(runtime);
        sequence.thenExecute(() -> {
            viewer().closeContainer();
            helper.assertTrue(entry.id().equals("transit") && entry.icon() == Items.FEATHER && entry.label() == TransitMessages.MENU_ENTRY
                && entry.visible(portal, viewer()) && !entry.enchanted(portal, viewer()), "Transit entry identity differs from Bukkit");
            entry.onLeftClick(portal, viewer(), new MinecraftWindow(runtime, viewer()));
            MinecraftSubsystemMenuProbe.assertWindow(helper, viewer(), MinecraftPortalText.router(runtime, portal, true), 3,
                Items.STAINED_GLASS_PANE.cyan(), MinecraftSubsystemMenuProbe.slot(0, 0), "Transit window");
            MinecraftSubsystemMenuProbe.assertElement(helper, viewer(), -2, 1, Items.SLIME_BALL,
                MinecraftSubsystemMenuProbe.name(viewer(), TransitMessages.MENU_MOMENTUM, MinecraftPortalText.arguments("mode", defaultMomentum().label(), "value", "1")),
                false, "Transit momentum");
            MinecraftSubsystemMenuProbe.assertElement(helper, viewer(), -1, 1, Items.COMPASS,
                MinecraftSubsystemMenuProbe.name(viewer(), TransitMessages.MENU_ORIENTATION, MinecraftPortalText.arguments("mode", OrientationPolicy.parse(runtime.configuration().settings().getTransit().orientationDefault, OrientationPolicy.FRAME).label())),
                false, "Transit orientation");
            String off = label(WormholesMessages.LABEL_OFF);
            MinecraftSubsystemMenuProbe.assertElement(helper, viewer(), 0, 1, Items.PHANTOM_MEMBRANE,
                MinecraftSubsystemMenuProbe.name(viewer(), TransitMessages.MENU_MEMBRANE, MinecraftPortalText.arguments("state", off)),
                false, "Transit membrane");
            MinecraftSubsystemMenuProbe.assertElement(helper, viewer(), 1, 1, Items.SLIME_BLOCK,
                MinecraftSubsystemMenuProbe.name(viewer(), TransitMessages.MENU_BOUNCE, MinecraftPortalText.arguments("state", off)),
                false, "Transit bounce");
            MinecraftSubsystemMenuProbe.assertElement(helper, viewer(), 2, 1, Items.NOTE_BLOCK,
                MinecraftSubsystemMenuProbe.name(viewer(), TransitMessages.MENU_PROFILE, MinecraftPortalText.arguments("mode", "default", "state", "default")),
                false, "Transit profile");
            MinecraftSubsystemMenuProbe.assertElement(helper, viewer(), 0, 2, Items.ARROW,
                MinecraftSubsystemMenuProbe.name(viewer(), WormholesMessages.PORTAL_MENU_BACK_SETTINGS, MessageArgs.empty()), false, "Transit back");
            MinecraftSubsystemMenuProbe.left(viewer(), 0, 1);
        }).thenIdle(CLICK_TICKS).thenExecute(() -> {
            helper.assertTrue(Boolean.TRUE.equals(portal.setting("transit.membrane")), "Transit membrane toggle did not persist");
            String on = label(WormholesMessages.LABEL_ON);
            MinecraftSubsystemMenuProbe.assertElement(helper, viewer(), 0, 1, Items.PHANTOM_MEMBRANE,
                MinecraftSubsystemMenuProbe.name(viewer(), TransitMessages.MENU_MEMBRANE, MinecraftPortalText.arguments("state", on)),
                true, "Transit membrane in place");
            helper.assertTrue(entry.enchanted(portal, viewer()), "Transit entry did not glow with overrides");
            MinecraftSubsystemMenuProbe.left(viewer(), 1, 1);
        }).thenIdle(CLICK_TICKS).thenExecute(() -> {
            helper.assertTrue(Boolean.TRUE.equals(portal.setting("transit.bounce")), "Transit bounce toggle did not persist");
            MinecraftSubsystemMenuProbe.left(viewer(), -2, 1);
        }).thenIdle(CLICK_TICKS).thenExecute(() -> {
            MomentumPolicy momentum = MomentumPolicy.decode((String) portal.setting("transit.momentum"));
            helper.assertTrue(momentum != null && momentum.mode() == defaultMomentum().next(), "Transit momentum did not cycle");
            MinecraftSubsystemMenuProbe.right(viewer(), -2, 1);
            MinecraftSubsystemMenuProbe.assertClosed(helper, viewer(), "Transit momentum prompt");
            helper.assertTrue(MinecraftChatInput.chat(viewer(), "2.5"), "Transit factor prompt did not consume chat");
        }).thenIdle(PROMPT_TICKS).thenExecute(() -> {
            helper.assertTrue(MomentumPolicy.decode((String) portal.setting("transit.momentum")).factor() == 2.5D,
                "Transit factor prompt did not apply");
            MinecraftSubsystemMenuProbe.window(helper, viewer(), "Transit reopened after prompt");
            MinecraftSubsystemMenuProbe.shiftLeft(viewer(), 2, 1);
            helper.assertTrue(MinecraftChatInput.chat(viewer(), "17"), "Transit mask prompt did not consume chat");
        }).thenIdle(PROMPT_TICKS).thenExecute(() -> {
            helper.assertTrue(TransitionProfile.decode((String) portal.setting("transit.profile")).maskOverrideTicks() == 17,
                "Transit mask prompt did not apply");
            MinecraftSubsystemMenuProbe.assertElement(helper, viewer(), 2, 1, Items.NOTE_BLOCK,
                MinecraftSubsystemMenuProbe.name(viewer(), TransitMessages.MENU_PROFILE, MinecraftPortalText.arguments("mode", "default", "state", "default / 17t")),
                true, "Transit profile after mask");
            opened = MinecraftWindow.active(viewer());
            MinecraftSubsystemMenuProbe.left(viewer(), 0, 2);
        }).thenIdle(CLICK_TICKS).thenExecute(() -> helper.assertTrue(opened != null && !opened.isVisible()
            && viewer().containerMenu != viewer().inventoryMenu, "Transit back did not open the portal menu"));
    }

    private MomentumPolicy.Mode defaultMomentum() {
        return MomentumPolicy.Mode.parse(runtime.configuration().settings().getTransit().momentumDefault, MomentumPolicy.Mode.PRESERVE);
    }

    private String label(TextKey key) {
        return MinecraftSubsystemMenuProbe.text(viewer(), key, MessageArgs.empty());
    }

    private ServerPlayer viewer() {
        return owner.player();
    }
}
