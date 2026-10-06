package art.arcane.wormholes.modded;

import art.arcane.volmlib.util.localization.LinesKey;
import art.arcane.volmlib.util.localization.MessageArgs;
import art.arcane.wormholes.access.PortalRole;
import art.arcane.wormholes.localization.WormholesMessages;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.BlackoutColor;
import art.arcane.optics.frame.QuarterTurn;
import art.arcane.wormholes.portal.NetworkViewQuality;
import art.arcane.optics.frame.Frame;
import art.arcane.wormholes.portal.PortalPermissionMode;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.portal.ProjectionRenderMode;
import art.arcane.optics.math.Face;
import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.PlayerChatMessage;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class MinecraftPortalMenuGameTest {
    private static final long SETTLE_TICKS = 3L;

    private final GameTestHelper helper;
    private final WormholesModRuntime runtime;
    private final ConnectedPlayer owner;
    private final ConnectedPlayer outsider;
    private final MinecraftPortal source;
    private final MinecraftPortal destination;
    private final List<Runnable> steps = new ArrayList<>();
    private final List<String> clicks = new ArrayList<>();
    private final CompletableFuture<Boolean> result = new CompletableFuture<>();
    private AutoCloseable permissions;
    private Frame originalFrame;
    private String homeTitle;
    private String sortLabel;
    private int heartbeat;
    private boolean cleaned;

    private MinecraftPortalMenuGameTest(GameTestHelper helper) {
        this.helper = helper;
        runtime = WormholesGameTests.RUNTIME;
        owner = player(helper, "menu-owner");
        outsider = player(helper, "menu-outsider");
        source = runtime.portals().create(owner.player().getUUID(), helper.getLevel(), cells(helper, 3), PortalType.PORTAL, new Vec3(0, 0, -1));
        destination = runtime.portals().create(owner.player().getUUID(), helper.getLevel(), cells(helper, 15), PortalType.PORTAL, new Vec3(0, 0, -1));
    }

    public static CompletableFuture<Boolean> run(GameTestHelper helper) {
        MinecraftPortalMenuGameTest test = new MinecraftPortalMenuGameTest(helper);
        test.home();
        test.settings();
        test.quality();
        test.advanced();
        test.cosmetics();
        test.extensionsAndCosts();
        test.modes();
        test.orientation();
        test.rename();
        test.destinations();
        test.gateway();
        test.selection();
        test.revocation();
        test.destroy();
        test.next(0);
        return test.result;
    }

    private void home() {
        step(() -> {
            helper.assertTrue(runtime.menus().open(outsider.player(), source.getId()) == 0, "Unrelated player opened portal editor");
            helper.assertTrue(outsider.player().containerMenu == outsider.player().inventoryMenu, "Denied player received a portal window");
            runtime.server().getCommands().performPrefixedCommand(owner.player().createCommandSourceStack(), "wormholes edit " + source.getId());
            homeTitle = MinecraftPortalText.router(runtime, source, true);
            window(4, Items.STAINED_GLASS_PANE.gray(), homeTitle, "home");
            int accessible = 0;
            for (MinecraftPortal portal : runtime.portals().snapshot()) {
                accessible += portal.getType() == PortalType.GATEWAY ? 0 : 1;
            }
            slot(4, Items.BOOK, false);
            slot(11, Items.ENDER_EYE, false);
            helper.assertTrue(item(11).getCount() == Math.max(1, accessible - 1), "Destination count did not mirror accessible portals");
            named(11, Items.ENDER_EYE, null);
            slot(13, Items.REDSTONE_TORCH, true);
            named(13, Items.REDSTONE_TORCH, WormholesMessages.PORTAL_MENU_PROJECTION_ON);
            slot(15, Items.LEVER, false);
            slot(20, Items.NAME_TAG, false);
            slot(22, Items.COMPASS, false);
            slot(24, Items.ENDER_EYE, true);
            named(31, Items.GUNPOWDER, WormholesMessages.PORTAL_MENU_DELETE);
            owner.player().getInventory().setItem(0, new ItemStack(Items.EMERALD, 7));
            owner.player().containerMenu.setCarried(new ItemStack(Items.DIAMOND, 3));
            click(13);
        });
        step(() -> {
            ChestMenu home = chest();
            helper.assertTrue(source.getProjectionMode() == ProjectionMode.OFF, "Projection toggle did not mutate portal");
            slot(13, Items.TORCH, false);
            named(13, Items.TORCH, WormholesMessages.PORTAL_MENU_PROJECTION_OFF);
            helper.assertTrue(home.getCarried().is(Items.DIAMOND) && home.getCarried().getCount() == 3, "Portal window consumed cursor item");
            home.clicked(36, 0, ContainerInput.QUICK_MOVE, owner.player());
            home.clicked(13, 0, ContainerInput.QUICK_CRAFT, owner.player());
            helper.assertTrue(owner.player().getInventory().getItem(0).getCount() == 7, "Inventory transfer changed player items");
            home.setCarried(ItemStack.EMPTY);
            click(15);
        });
        step(() -> {
            helper.assertTrue(source.getProjectionMode() == ProjectionMode.OFF, "Inventory transfer changed portal state");
            window(5, Items.STAINED_GLASS_PANE.gray(), homeTitle, "settings");
        });
    }

    private void settings() {
        step(() -> {
            slot(4, Items.LEVER, false);
            named(4, Items.LEVER, WormholesMessages.PORTAL_MENU_SETTINGS_PLACARD_GATEWAY);
            slot(10, Items.IRON_HELMET, false);
            slot(12, Items.RECOVERY_COMPASS, true);
            slot(14, Items.CONDUIT, false);
            slot(16, Items.SOUL_LANTERN, true);
            slot(18, Items.GLASS, false);
            slot(20, Items.LODESTONE, false);
            slot(22, Items.TINTED_GLASS, true);
            slot(24, Items.FIREWORK_STAR, true);
            slot(26, Items.GLASS, false);
            slot(30, Items.NAME_TAG, false);
            slot(32, Items.HOPPER, false);
            named(34, Items.COMPARATOR, WormholesMessages.PORTAL_MENU_EXTENSIONS);
            named(40, Items.ARROW, WormholesMessages.PORTAL_MENU_BACK);
            click(10);
        });
        step(() -> {
            helper.assertTrue(source.getPermissionMode() == PortalPermissionMode.WHITELIST, "Access-mode toggle failed");
            slot(10, Items.GOLDEN_HELMET, true);
            click(12);
        });
        step(() -> {
            helper.assertTrue(source.isOutgoingTraversalsEnabled() && !source.isIncomingTraversalsEnabled(), "Travel-mode cycle failed");
            slot(12, Items.ENDER_PEARL, true);
            click(16);
        });
        step(() -> {
            helper.assertTrue(!source.isSettingsSyncEnabled(), "Settings-sync toggle failed");
            slot(16, Items.LANTERN, false);
            click(20);
        });
        step(() -> {
            int global = (int) Math.round(runtime.configuration().settings().getProjection().range);
            helper.assertTrue(source.getActivationRange() == activationRange(global + 8), "Activation range did not follow global base");
            slot(20, Items.LODESTONE, true);
            chest().clicked(20, 1, ContainerInput.PICKUP, owner.player());
            helper.assertTrue(source.getActivationRange() == activationRange(activationRange(global + 8) - 8), "Activation range right click did not step down");
            click(22);
        });
        step(() -> {
            helper.assertTrue(source.getRenderMode() == ProjectionRenderMode.PANOPTIC, "Render mode did not cycle");
            slot(22, Items.SPYGLASS, false);
            click(30);
        });
        step(() -> {
            helper.assertTrue(source.isPublicLookLabel(), "Public look label toggle did not persist");
            slot(30, Items.NAME_TAG, true);
            click(40);
        });
        step(() -> window(4, Items.STAINED_GLASS_PANE.gray(), homeTitle, "home after settings back"));
    }

    private void quality() {
        step(() -> click(15));
        step(() -> click(14));
        step(() -> {
            helper.assertTrue(source.getNetworkViewQuality() == NetworkViewQuality.STANDARD.next(), "Stream quality did not cycle in place");
            window(5, Items.STAINED_GLASS_PANE.gray(), homeTitle, "settings after quality cycle");
            runtime.portals().update(owner.player(), source.getId(), portal -> portal.setNetworkViewQuality(NetworkViewQuality.values()[NetworkViewQuality.CUSTOM.ordinal() - 1]));
            owner.player().closeContainer();
            runtime.menus().open(owner.player(), source.getId());
        });
        step(() -> click(15));
        step(() -> {
            helper.assertTrue(source.getNetworkViewQuality().next() == NetworkViewQuality.CUSTOM, "Quality fixture was not before custom");
            click(14);
        });
        step(() -> {
            helper.assertTrue(source.getNetworkViewQuality() == NetworkViewQuality.CUSTOM, "Stream quality did not reach custom");
            window(6, Items.STAINED_GLASS_PANE.gray(), homeTitle, "custom settings");
            slot(19, Items.SPYGLASS, false);
            slot(21, Items.CLOCK, false);
            slot(23, Items.ENDER_EYE, false);
            slot(25, Items.REDSTONE, false);
            slot(27, Items.GLASS, false);
            slot(29, Items.FIREWORK_STAR, true);
            slot(31, Items.GLASS, false);
            slot(33, Items.GLASS, false);
            slot(35, Items.LODESTONE, source.getActivationRange() > 0);
            slot(38, Items.SPYGLASS, false);
            slot(40, Items.NAME_TAG, true);
            slot(42, Items.HOPPER, false);
            slot(44, Items.COMPARATOR, false);
            named(49, Items.ARROW, WormholesMessages.PORTAL_MENU_BACK);
            int depth = source.getNetworkViewDepth();
            chest().clicked(19, 1, ContainerInput.PICKUP, owner.player());
            helper.assertTrue(source.getNetworkViewDepth() == depth - 4, "Capture radius right click did not step down");
            chest().clicked(19, 0, ContainerInput.QUICK_MOVE, owner.player());
            helper.assertTrue(source.getNetworkViewDepth() == depth + 12, "Capture radius shift click did not use the large step");
            click(14);
        });
        step(() -> {
            helper.assertTrue(source.getNetworkViewQuality() == NetworkViewQuality.CUSTOM.next(), "Stream quality did not leave custom");
            window(5, Items.STAINED_GLASS_PANE.gray(), homeTitle, "settings after leaving custom");
        });
    }

    private void advanced() {
        step(() -> chest().clicked(14, 0, ContainerInput.QUICK_MOVE, owner.player()));
        step(() -> {
            window(5, Items.STAINED_GLASS_PANE.black(), homeTitle, "advanced settings");
            named(4, Items.COMPARATOR, WormholesMessages.PORTAL_MENU_ADVANCED_SETTINGS);
            slot(10, Items.SPYGLASS, false);
            slot(12, Items.CLOCK, false);
            slot(14, Items.ENDER_EYE, false);
            slot(16, Items.REDSTONE, false);
            slot(20, Items.FIREWORK_STAR, true);
            slot(22, Items.GLASS, false);
            slot(24, Items.GLASS, false);
            slot(29, Items.GLASS, false);
            slot(31, Items.SPYGLASS, false);
            slot(33, Items.LODESTONE, source.getActivationRange() > 0);
            named(40, Items.ARROW, WormholesMessages.PORTAL_MENU_BACK_SETTINGS);
            click(22);
        });
        step(() -> {
            helper.assertTrue(owner.player().containerMenu == owner.player().inventoryMenu, "Fallback prompt did not close the window");
            broadcast(owner.player(), "minecraft:stone");
        });
        step(() -> {
            helper.assertTrue(source.getNetworkViewFallbackBlock().equals("minecraft:stone"), "Network fallback prompt did not apply");
            window(5, Items.STAINED_GLASS_PANE.gray(), homeTitle, "settings after fallback prompt");
            chest().clicked(14, 0, ContainerInput.QUICK_MOVE, owner.player());
        });
        step(() -> {
            chest().clicked(22, 1, ContainerInput.PICKUP, owner.player());
            helper.assertTrue(source.getNetworkViewFallbackBlock().equals("minecraft:air"), "Network fallback reset did not apply");
            heartbeat = source.getNetworkViewHeartbeatTicks();
            click(12);
            helper.assertTrue(source.getNetworkViewHeartbeatTicks() == heartbeat, "Left click applied before the click tick");
        });
        step(() -> {
            helper.assertTrue(source.getNetworkViewHeartbeatTicks() == heartbeat + 10, "Full refresh control did not step up");
            click(40);
        });
        step(() -> {
            helper.assertTrue(chest().getRowCount() == (source.getNetworkViewQuality() == NetworkViewQuality.CUSTOM ? 6 : 5),
                "Back to settings did not reopen the settings window");
            runtime.portals().update(owner.player(), source.getId(), portal -> portal.setNetworkViewQuality(NetworkViewQuality.STANDARD));
            owner.player().closeContainer();
            runtime.menus().open(owner.player(), source.getId());
        });
    }

    private void cosmetics() {
        step(() -> click(15));
        step(() -> {
            window(5, Items.STAINED_GLASS_PANE.gray(), homeTitle, "settings for cosmetics");
            click(18);
        });
        step(() -> {
            helper.assertTrue(source.isBlackoutBackground(), "Blackout toggle did not apply");
            slot(18, Items.CONCRETE.pick(DyeColor.BLACK), true);
            chest().clicked(18, 1, ContainerInput.PICKUP, owner.player());
        });
        step(() -> {
            window(4, Items.STAINED_GLASS_PANE.black(), homeTitle, "blackout colours");
            slot(9, Items.CONCRETE.pick(DyeColor.WHITE), false);
            slot(16, Items.CONCRETE.pick(DyeColor.GRAY), false);
            slot(18, Items.CONCRETE.pick(DyeColor.LIGHT_GRAY), false);
            slot(25, Items.CONCRETE.pick(DyeColor.BLACK), true);
            named(31, Items.ARROW, WormholesMessages.PORTAL_MENU_BACK_SETTINGS);
            click(11);
        });
        step(() -> {
            helper.assertTrue(source.getBlackoutColor() == BlackoutColor.values()[2], "Blackout palette did not apply");
            slot(11, Items.CONCRETE.pick(DyeColor.MAGENTA), true);
            slot(4, Items.CONCRETE.pick(DyeColor.MAGENTA), false);
            click(31);
        });
        step(() -> {
            window(5, Items.STAINED_GLASS_PANE.gray(), homeTitle, "settings after blackout");
            click(24);
        });
        step(() -> {
            helper.assertTrue(source.getAmbientStyle() == AmbientParticleStyle.SPARKS.next(), "Ambient particles did not cycle");
            chest().clicked(24, 1, ContainerInput.PICKUP, owner.player());
        });
        step(() -> {
            window(5, Items.STAINED_GLASS_PANE.black(), homeTitle, "ambient colours");
            slot(4, Items.FIREWORK_STAR, false);
            slot(11, Items.DYE.red(), false);
            slot(13, Items.DYE.green(), false);
            slot(15, Items.DYE.blue(), false);
            slot(18, Items.WOOL.pick(DyeColor.WHITE), false);
            slot(34, Items.WOOL.pick(DyeColor.BLACK), false);
            named(40, Items.ARROW, WormholesMessages.PORTAL_MENU_BACK_SETTINGS);
            click(18);
        });
        step(() -> {
            helper.assertTrue(source.getAmbientColor() == (DyeColor.WHITE.getTextureDiffuseColor() & 0xFFFFFF), "Ambient dye option did not apply");
            slot(18, Items.WOOL.pick(DyeColor.WHITE), true);
            chest().clicked(11, 1, ContainerInput.PICKUP, owner.player());
            helper.assertTrue(((source.getAmbientColor() >> 16) & 0xFF) == ((DyeColor.WHITE.getTextureDiffuseColor() >> 16) & 0xFF) - 8,
                "Ambient red channel did not step down");
            click(40);
        });
        step(() -> chest().clicked(26, 1, ContainerInput.PICKUP, owner.player()));
        step(() -> {
            window(3, Items.STAINED_GLASS_PANE.cyan(), homeTitle, "surface skins");
            slot(4, Items.GLASS, false);
            named(12, Items.GLASS, WormholesMessages.PORTAL_MENU_SURFACE_SKIN_GLASS);
            named(14, Items.BARRIER, WormholesMessages.PORTAL_MENU_SURFACE_SKIN_CLEAR);
            named(22, Items.ARROW, WormholesMessages.PORTAL_MENU_BACK_SETTINGS);
            permissions = runtime.access().register((player, node) -> player == owner.player() && node.equals("wormholes.admin")
                ? MinecraftAccessService.Decision.ALLOW : MinecraftAccessService.Decision.UNSET);
            click(12);
        });
        step(() -> {
            helper.assertTrue(source.getSurfaceSkin().equals("minecraft:glass"), "Skin picker did not apply glass");
            slot(12, Items.GLASS, true);
            click(14);
        });
        step(() -> {
            helper.assertTrue(source.getSurfaceSkin().isEmpty(), "Skin picker did not clear glass");
            MinecraftPortalSurfaceGameTest.run(new MinecraftPortalSurfaceGameTest.Options(helper, runtime, owner.player(), owner.channel()));
            closePermissions();
            click(22);
        });
        step(() -> window(5, Items.STAINED_GLASS_PANE.gray(), homeTitle, "settings after skins"));
    }

    private void extensionsAndCosts() {
        step(() -> {
            MinecraftPortalExtensionsMenu extensions = runtime.menus().extensions();
            helper.assertTrue(extensions.hasEntries(), "More settings had no registered entries");
            List<Class<?>> order = List.of(MinecraftAccessMenuEntry.class, MinecraftRulesMenuEntry.class, MinecraftNetworkMenuEntry.class,
                MinecraftTransitMenuEntry.class, MinecraftFidelityMenuEntry.class);
            int previous = -1;
            for (MinecraftPortalMenuEntry entry : extensions.visibleEntries(source, owner.player())) {
                int position = order.indexOf(entry.getClass());
                helper.assertTrue(position > previous, "More settings entry " + entry.id() + " is out of registration order");
                previous = position;
            }
            List<MinecraftPortalMenuEntry> synthetic = new ArrayList<>();
            for (int index = 0; index < 11; index++) {
                synthetic.add(new RecordingEntry("synthetic-" + index, index == 0, index != 10, clicks));
            }
            new MinecraftPortalExtensionsMenu(runtime.menus(), synthetic).open(owner.player(), source);
            window(4, Items.STAINED_GLASS_PANE.cyan(), homeTitle, "more settings");
            named(4, Items.COMPARATOR, WormholesMessages.PORTAL_MENU_EXTENSIONS);
            for (int index = 0; index < 10; index++) {
                named(9 + index, Items.PAPER, WormholesMessages.PORTAL_MENU_DELETE);
            }
            slot(9, Items.PAPER, true);
            slot(10, Items.PAPER, false);
            helper.assertTrue(item(19).is(Items.STAINED_GLASS_PANE.cyan()), "Hidden more settings entry was shown");
            named(31, Items.ARROW, WormholesMessages.PORTAL_MENU_BACK_SETTINGS);
            chest().clicked(9, 1, ContainerInput.PICKUP, owner.player());
            chest().clicked(10, 0, ContainerInput.QUICK_MOVE, owner.player());
            click(11);
        });
        step(() -> {
            helper.assertTrue(clicks.equals(List.of("synthetic-0:right", "synthetic-1:shift-left", "synthetic-2:left")),
                "More settings entries did not receive their click types: " + clicks);
            click(31);
        });
        step(() -> {
            window(5, Items.STAINED_GLASS_PANE.gray(), homeTitle, "settings after more settings");
            click(32);
        });
        step(() -> {
            window(4, Items.STAINED_GLASS_PANE.brown(), homeTitle, "travel cost");
            slot(4, Items.CHEST, false);
            named(11, Items.FEATHER, WormholesMessages.PORTAL_MENU_COST_MODE_FREE);
            helper.assertTrue(item(11).hasFoil(), "Free cost mode was not marked active");
            named(13, Items.HOPPER, WormholesMessages.PORTAL_MENU_COST_MODE_VANILLA);
            boolean currency = runtime.costs().currencyAvailable();
            named(15, currency ? Items.EMERALD : Items.REDSTONE,
                currency ? WormholesMessages.PORTAL_MENU_COST_MODE_VAULT : WormholesMessages.PORTAL_MENU_COST_MODE_VAULT_UNAVAILABLE);
            named(21, Items.STAINED_GLASS_PANE.white(), WormholesMessages.PORTAL_MENU_COST_FREE_DETAIL);
            named(23, Items.STAINED_GLASS_PANE.brown(), WormholesMessages.PORTAL_MENU_COST_SECONDARY_EMPTY);
            named(31, Items.ARROW, WormholesMessages.PORTAL_MENU_BACK_SETTINGS);
            click(31);
        });
        step(() -> {
            window(5, Items.STAINED_GLASS_PANE.gray(), homeTitle, "settings after cost");
            owner.player().closeContainer();
        });
        step(() -> helper.assertTrue(owner.player().containerMenu == owner.player().inventoryMenu, "Settings window reopened after closing"));
    }

    private void modes() {
        step(() -> {
            permissions = runtime.access().register((player, node) -> player == owner.player() && node.equals("wormholes.portals.wormhole")
                ? MinecraftAccessService.Decision.DENY
                : player == owner.player() && node.startsWith("wormholes.portals.") ? MinecraftAccessService.Decision.ALLOW
                : MinecraftAccessService.Decision.UNSET);
            runtime.menus().open(owner.player(), source.getId());
            click(24);
        });
        step(() -> {
            window(3, Items.STAINED_GLASS_PANE.gray(), homeTitle, "mode");
            slot(4, Items.BEACON, false);
            slot(9, Items.ENDER_EYE, true);
            slot(11, Items.ENDER_PEARL, false);
            slot(13, Items.END_CRYSTAL, true);
            slot(15, Items.COMPASS, false);
            slot(17, Items.COPPER_TORCH, false);
            named(17, Items.COPPER_TORCH, WormholesMessages.PORTAL_MENU_MIRROR_AVAILABLE);
            named(22, Items.ARROW, WormholesMessages.PORTAL_MENU_BACK);
            chest().clicked(17, 1, ContainerInput.PICKUP, owner.player());
            helper.assertTrue(!source.isMirrorMode() && source.getMirrorRotation() == QuarterTurn.DEGREES_0, "Mirror rotation applied before mirror mode");
            owner.player().closeContainer();
        });
        step(() -> {
            window(4, Items.STAINED_GLASS_PANE.gray(), homeTitle, "home reopened after mode close");
            click(24);
        });
        step(() -> click(11));
        step(() -> {
            helper.assertTrue(source.getType() == PortalType.PORTAL, "Denied mode change applied");
            helper.assertTrue(owner.player().containerMenu == owner.player().inventoryMenu, "Denied mode change left the window open");
            runtime.menus().open(owner.player(), source.getId());
            click(24);
        });
        step(() -> click(17));
        step(() -> {
            helper.assertTrue(source.isMirrorMode(), "Mirror mode was not selected");
            helper.assertTrue(owner.player().containerMenu == owner.player().inventoryMenu, "Mirror selection did not close the window");
            runtime.menus().open(owner.player(), source.getId());
            slot(24, Items.COPPER_TORCH, true);
            click(24);
        });
        step(() -> {
            slot(17, Items.COPPER_TORCH, true);
            QuarterTurn expected = source.getMirrorRotation().clockwiseFor(source.getFrame());
            chest().clicked(17, 1, ContainerInput.PICKUP, owner.player());
            helper.assertTrue(source.getMirrorRotation() == expected, "Mirror right click did not rotate clockwise");
            click(9);
        });
        step(() -> {
            helper.assertTrue(!source.isMirrorMode() && source.getType() == PortalType.PORTAL, "Portal mode did not clear mirror state");
            runtime.menus().open(owner.player(), source.getId());
            click(24);
        });
        step(() -> click(15));
        step(() -> {
            helper.assertTrue(source.getType() == PortalType.RTP, "Mode picker did not select RTP");
            runtime.menus().open(owner.player(), source.getId());
            slot(11, Items.COMPASS, false);
            helper.assertTrue(item(11).getCount() == 1, "RTP destination count was not one");
            slot(24, Items.COMPASS, true);
            click(11);
        });
        step(() -> {
            helper.assertTrue(owner.player().containerMenu != owner.player().inventoryMenu && MinecraftWindow.active(owner.player()) == null
                || MinecraftWindow.active(owner.player()) != null && !MinecraftWindow.active(owner.player()).getTitle().equals(homeTitle),
                "RTP destination did not open the RTP editor");
            owner.player().closeContainer();
            runtime.portals().update(owner.player(), source.getId(), portal -> portal.setType(PortalType.PORTAL));
            closePermissions();
        });
    }

    private void orientation() {
        step(() -> {
            originalFrame = source.getFrame();
            runtime.menus().open(owner.player(), source.getId());
            click(22);
        });
        step(() -> {
            window(3, Items.STAINED_GLASS_PANE.blue(), homeTitle, "orientation");
            slot(4, Items.COMPASS, false);
            slot(10, Items.COMPASS, false);
            slot(12, Items.TARGET, false);
            slot(14, Items.REPEATER, false);
            slot(16, Items.LEVER, false);
            named(22, Items.ARROW, WormholesMessages.PORTAL_MENU_BACK);
            click(12);
        });
        step(() -> {
            helper.assertTrue(source.getDirection() == originalFrame.getNormal().reverse(), "Flip face control did not reverse portal");
            window(4, Items.STAINED_GLASS_PANE.gray(), homeTitle, "home after flip");
            click(22);
        });
        step(() -> click(12));
        step(() -> click(22));
        step(() -> click(16));
        step(() -> click(22));
        step(() -> click(14));
        step(() -> {
            helper.assertTrue(source.getFrame().getNormal() == originalFrame.getNormal() && source.getFrame().getUp() == originalFrame.getUp(),
                "Orientation roll controls did not invert each other");
            click(22);
        });
        step(() -> click(10));
        step(() -> {
            helper.assertTrue(owner.player().containerMenu == owner.player().inventoryMenu, "Direction prompt did not close the window");
            owner.player().setShiftKeyDown(true);
            MinecraftPortalMenus.directionInput(owner.player(), false);
            owner.player().setShiftKeyDown(false);
            helper.assertTrue(source.getFrame().getNormal() == originalFrame.getNormal(), "Sneaking cancelled direction still applied");
            runtime.menus().open(owner.player(), source.getId());
            click(22);
        });
        step(() -> click(10));
        step(() -> {
            owner.player().setYRot(90.0F);
            owner.player().setXRot(0.0F);
            Vec3 look = owner.player().getLookAngle();
            Face expected = Face.closest(look.x, look.y, look.z);
            MinecraftPortalMenus.directionInput(owner.player(), false);
            helper.assertTrue(source.getDirection() == expected, "Direction prompt did not apply the looked direction");
            runtime.portals().update(owner.player(), source.getId(), portal -> portal.setFrame(originalFrame));
        });
    }

    private void rename() {
        step(() -> {
            runtime.menus().open(owner.player(), source.getId());
            click(20);
        });
        step(() -> {
            helper.assertTrue(owner.player().containerMenu == owner.player().inventoryMenu, "Rename prompt did not close the window");
            broadcast(owner.player(), "Renamed through native chat");
        });
        step(() -> {
            helper.assertTrue(source.getName().equals("Renamed through native chat"), "Validated chat hook did not consume rename input");
            homeTitle = MinecraftPortalText.router(runtime, source, true);
            window(4, Items.STAINED_GLASS_PANE.gray(), homeTitle, "home after rename");
            click(20);
        });
        step(() -> broadcast(owner.player(), MinecraftPortalText.localized(owner.player(), WormholesMessages.PORTAL_INPUT_CANCEL)));
        step(() -> {
            helper.assertTrue(source.getName().equals("Renamed through native chat"), "Cancelled rename changed the portal");
            window(4, Items.STAINED_GLASS_PANE.gray(), homeTitle, "home after cancelled rename");
        });
    }

    private void destinations() {
        step(() -> click(11));
        step(() -> {
            window(6, Items.STAINED_GLASS_PANE.gray(), homeTitle, "destinations");
            named(47, Items.COMPARATOR, null);
            named(49, Items.PAPER, null);
            helper.assertTrue(!item(51).is(Items.ENDER_EYE), "Link and return offered without a link");
            click(find(destination.getName()));
        });
        step(() -> {
            helper.assertTrue(destination.getId().equals(source.getDestinationId()), "Destination picker did not link portal");
            helper.assertTrue(owner.player().containerMenu == owner.player().inventoryMenu, "Destination selection reopened a window");
            runtime.menus().open(owner.player(), source.getId());
            homeTitle = MinecraftPortalText.router(runtime, source, true);
            helper.assertTrue(homeTitle.endsWith(destination.getName()), "Home title did not route to the linked destination");
            window(4, Items.STAINED_GLASS_PANE.gray(), homeTitle, "linked home");
            click(11);
        });
        step(() -> {
            int linked = find(destination.getName());
            helper.assertTrue(item(linked).hasFoil(), "Linked destination was not highlighted");
            sortLabel = name(item(47));
            click(47);
            helper.assertTrue(name(item(47)).equals(sortLabel), "Sort applied before the click tick");
        });
        step(() -> {
            helper.assertTrue(!name(item(47)).equals(sortLabel), "Sort control did not advance");
            named(51, Items.ENDER_EYE, null);
            click(51);
        });
        step(() -> {
            helper.assertTrue(source.getId().equals(destination.getDestinationId()) && destination.getId().equals(source.getDestinationId()),
                "Link and return did not pair both portals");
            helper.assertTrue(owner.player().containerMenu == owner.player().inventoryMenu, "Link and return left the window open");
            runtime.menus().open(owner.player(), source.getId());
            click(11);
        });
        step(() -> click(find(destination.getName())));
        step(() -> {
            helper.assertTrue(source.getDestinationId() == null, "Destination picker did not unlink selected destination");
            runtime.portals().link(owner.player(), destination.getId(), null);
            homeTitle = MinecraftPortalText.router(runtime, source, true);
            runtime.menus().open(owner.player(), source.getId());
            click(11);
        });
        step(() -> {
            window(6, Items.STAINED_GLASS_PANE.gray(), homeTitle, "destinations before close");
            owner.player().closeContainer();
        });
        step(() -> window(4, Items.STAINED_GLASS_PANE.gray(), homeTitle, "home reopened after destination close"));
    }

    private void gateway() {
        step(() -> {
            runtime.portals().update(owner.player(), source.getId(), portal -> portal.setType(PortalType.GATEWAY));
            owner.player().closeContainer();
            runtime.menus().open(owner.player(), source.getId());
            slot(11, Items.END_CRYSTAL, true);
            click(11);
        });
        step(() -> {
            window(3, Items.STAINED_GLASS_PANE.black(), homeTitle, "gateway pairing");
            slot(4, Items.RESPAWN_ANCHOR, false);
            named(4, Items.RESPAWN_ANCHOR, WormholesMessages.PORTAL_MENU_GATEWAY_UNPAIRED);
            named(11, Items.PAPER, WormholesMessages.PORTAL_MENU_GATEWAY_EXPORT);
            named(13, Items.END_CRYSTAL, WormholesMessages.PORTAL_MENU_GATEWAY_CHOOSE);
            named(15, Items.WRITABLE_BOOK, WormholesMessages.PORTAL_MENU_GATEWAY_IMPORT);
            named(22, Items.ARROW, WormholesMessages.PORTAL_MENU_BACK);
            click(15);
        });
        step(() -> {
            helper.assertTrue(owner.player().containerMenu == owner.player().inventoryMenu, "Import prompt did not close the window");
            broadcast(owner.player(), MinecraftPortalText.localized(owner.player(), WormholesMessages.PORTAL_INPUT_CANCEL));
        });
        step(() -> {
            window(3, Items.STAINED_GLASS_PANE.black(), homeTitle, "gateway pairing after cancelled import");
            click(22);
        });
        step(() -> {
            window(4, Items.STAINED_GLASS_PANE.gray(), homeTitle, "home after gateway back");
            owner.player().closeContainer();
            runtime.portals().update(owner.player(), source.getId(), portal -> portal.setType(PortalType.PORTAL));
        });
    }

    private void selection() {
        step(() -> {
            runtime.server().getCommands().performPrefixedCommand(owner.player().createCommandSourceStack(), "wormholes edit");
            window(6, Items.STAINED_GLASS_PANE.gray(), "", "portal selection");
            click(find(source.getName()));
        });
        step(() -> window(4, Items.STAINED_GLASS_PANE.gray(), homeTitle, "home from selection"));
    }

    private void revocation() {
        step(() -> {
            runtime.portals().update(owner.player(), source.getId(), portal -> portal.setRole(outsider.player().getUUID(), PortalRole.CO_OWNER));
            helper.assertTrue(runtime.menus().open(outsider.player(), source.getId()) == 1, "Co-owner could not open portal editor");
            helper.assertTrue(!runtime.menus().transfer(outsider.player(), source.getId(), outsider.player().getUUID()), "Co-owner stole portal ownership");
            runtime.portals().update(owner.player(), source.getId(), portal -> portal.setRole(outsider.player().getUUID(), PortalRole.DENIED));
            outsider.player().containerMenu.clicked(13, 0, ContainerInput.PICKUP, outsider.player());
        });
        step(() -> {
            helper.assertTrue(source.getProjectionMode() == ProjectionMode.OFF, "Revoked co-owner changed portal through stale window");
            helper.assertTrue(outsider.player().containerMenu == outsider.player().inventoryMenu, "Revoked window remained open");
            runtime.menus().open(owner.player(), source.getId());
            click(20);
        });
        step(() -> {
            helper.assertTrue(runtime.menus().transfer(owner.player(), source.getId(), outsider.player().getUUID()), "Owner transfer failed");
            broadcast(owner.player(), "Must not replace the name");
        });
        step(() -> {
            helper.assertTrue(source.getName().equals("Renamed through native chat"), "Pending rename survived ownership revocation");
            helper.assertTrue(source.getOwner().equals(outsider.player().getUUID()) && source.role(outsider.player().getUUID()) == null,
                "Ownership transfer retained new owner's denial override");
            helper.assertTrue(owner.player().containerMenu == owner.player().inventoryMenu, "Former owner reopened the portal editor");
        });
    }

    private void destroy() {
        step(() -> {
            runtime.menus().open(owner.player(), destination.getId());
            chest().clicked(31, 0, ContainerInput.PICKUP, owner.player());
        });
        step(() -> {
            helper.assertTrue(runtime.portals().get(destination.getId()) != null, "Plain click destroyed the portal");
            chest().clicked(31, 0, ContainerInput.QUICK_MOVE, owner.player());
            helper.assertTrue(runtime.portals().get(destination.getId()) == null, "Shift click did not destroy the portal");
            helper.assertTrue(owner.player().containerMenu == owner.player().inventoryMenu, "Destroy left the window open");
            LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS portal_menu home settings custom_quality advanced cosmetics "
                + "more_settings costs modes mirror orientation direction rename destinations reciprocal gateway selection revocation transfer destroy "
                + "inventory_integrity");
        });
    }

    private static int activationRange(int value) {
        return value < 8 ? 0 : Math.min(256, value);
    }

    private void step(Runnable action) {
        steps.add(action);
    }

    private void next(int index) {
        if (index >= steps.size()) {
            cleanup();
            result.complete(true);
            return;
        }
        try {
            steps.get(index).run();
        } catch (RuntimeException | AssertionError failure) {
            cleanup();
            result.completeExceptionally(failure);
            return;
        }
        if (!runtime.schedule(() -> next(index + 1), SETTLE_TICKS)) {
            cleanup();
            result.completeExceptionally(new IllegalStateException("Wormholes stopped during the portal menu test"));
        }
    }

    private void cleanup() {
        if (cleaned) {
            return;
        }
        cleaned = true;
        closePermissions();
        runtime.menus().playerDisconnected(owner.player());
        runtime.menus().playerDisconnected(outsider.player());
        runtime.chatInput().disconnected(owner.player());
        runtime.chatInput().disconnected(outsider.player());
        if (runtime.portals().get(source.getId()) != null) {
            runtime.portals().remove(source.getId());
        }
        if (runtime.portals().get(destination.getId()) != null) {
            runtime.portals().remove(destination.getId());
        }
        owner.close();
        outsider.close();
    }

    private void closePermissions() {
        if (permissions == null) {
            return;
        }
        try {
            permissions.close();
        } catch (Exception error) {
            throw new IllegalStateException("Could not release test permissions", error);
        }
        permissions = null;
    }

    private void window(int rows, Item pane, String title, String description) {
        ServerPlayer viewer = owner.player();
        helper.assertTrue(viewer.containerMenu instanceof MinecraftWindowMenu, "Expected native portal window: " + description);
        helper.assertTrue(chest().getRowCount() == rows, "Window " + description + " has " + chest().getRowCount() + " rows, expected " + rows);
        MinecraftWindow active = MinecraftWindow.active(viewer);
        helper.assertTrue(active != null && active.getTitle().equals(title), "Window " + description + " has the wrong title");
        boolean paned = false;
        for (int slot = 0; slot < rows * 9; slot++) {
            ItemStack stack = item(slot);
            if (stack.is(pane) && name(stack).equals(" ")) {
                paned = true;
                break;
            }
        }
        helper.assertTrue(paned, "Window " + description + " is missing its pane background");
    }

    private void slot(int slot, Item expected, boolean glint) {
        ItemStack stack = item(slot);
        helper.assertTrue(stack.is(expected), "Slot " + slot + " holds " + stack + ", expected " + expected);
        helper.assertTrue(stack.hasFoil() == glint, "Slot " + slot + " glint was not " + glint);
    }

    private void named(int slot, Item expected, LinesKey key) {
        ItemStack stack = item(slot);
        helper.assertTrue(stack.is(expected), "Slot " + slot + " holds " + stack + ", expected " + expected);
        String actual = name(stack);
        helper.assertTrue(!actual.isBlank(), "Slot " + slot + " has no name");
        if (key != null) {
            String expectedName = MinecraftLegacyText.component(MinecraftLegacyText.lines(owner.player(), key, MessageArgs.empty()).getFirst()).getString();
            helper.assertTrue(actual.equals(expectedName), "Slot " + slot + " is named " + actual + ", expected " + expectedName);
        }
    }

    private ItemStack item(int slot) {
        return owner.player().containerMenu.getSlot(slot).getItem();
    }

    private ChestMenu chest() {
        helper.assertTrue(owner.player().containerMenu instanceof ChestMenu, "Expected an open chest window");
        return (ChestMenu) owner.player().containerMenu;
    }

    private void click(int slot) {
        chest().clicked(slot, 0, ContainerInput.PICKUP, owner.player());
    }

    private int find(String name) {
        for (int slot = 0; slot < 45; slot++) {
            if (name(item(slot)).equals(name)) {
                return slot;
            }
        }
        helper.fail("Portal missing from native picker: " + name);
        return -1;
    }

    private static String name(ItemStack stack) {
        Component title = stack.get(DataComponents.CUSTOM_NAME);
        return title == null ? "" : title.getString();
    }

    private static void broadcast(ServerPlayer player, String message) {
        try {
            Method broadcast = ServerGamePacketListenerImpl.class.getDeclaredMethod("broadcastChatMessage", PlayerChatMessage.class);
            broadcast.setAccessible(true);
            broadcast.invoke(player.connection, PlayerChatMessage.unsigned(player.getUUID(), message));
        } catch (NoSuchMethodException | IllegalAccessException error) {
            throw new IllegalStateException("Native chat test could not invoke the validated message stage", error);
        } catch (InvocationTargetException error) {
            throw new IllegalStateException("Native chat test failed", error.getCause());
        }
    }

    private static ConnectedPlayer player(GameTestHelper helper, String name) {
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(new GameProfile(UUID.randomUUID(), name), false);
        ServerPlayer player = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        EmbeddedChannel channel = new EmbeddedChannel(connection);
        player.connection = new ServerGamePacketListenerImpl(helper.getLevel().getServer(), connection, player, cookie);
        player.initInventoryMenu();
        return new ConnectedPlayer(player, channel);
    }

    private static List<BlockPos> cells(GameTestHelper helper, int x) {
        List<BlockPos> result = new ArrayList<>();
        for (int y = 2; y <= 4; y++) {
            result.add(helper.absolutePos(new BlockPos(x, y, 1)));
            result.add(helper.absolutePos(new BlockPos(x + 1, y, 1)));
        }
        return result;
    }

    private record RecordingEntry(String id, boolean highlighted, boolean shown, List<String> clicks) implements MinecraftPortalMenuEntry {
        @Override
        public Item icon() {
            return Items.PAPER;
        }

        @Override
        public LinesKey label() {
            return WormholesMessages.PORTAL_MENU_DELETE;
        }

        @Override
        public boolean visible(MinecraftPortal portal, ServerPlayer viewer) {
            return shown;
        }

        @Override
        public boolean enchanted(MinecraftPortal portal, ServerPlayer viewer) {
            return highlighted;
        }

        @Override
        public void onLeftClick(MinecraftPortal portal, ServerPlayer viewer, MinecraftWindow window) {
            clicks.add(id + ":left");
        }

        @Override
        public void onRightClick(MinecraftPortal portal, ServerPlayer viewer, MinecraftWindow window) {
            clicks.add(id + ":right");
        }

        @Override
        public void onShiftLeftClick(MinecraftPortal portal, ServerPlayer viewer, MinecraftWindow window) {
            clicks.add(id + ":shift-left");
        }
    }

    private record ConnectedPlayer(ServerPlayer player, EmbeddedChannel channel) implements AutoCloseable {
        @Override
        public void close() {
            player.closeContainer();
            channel.finishAndReleaseAll();
        }
    }
}
