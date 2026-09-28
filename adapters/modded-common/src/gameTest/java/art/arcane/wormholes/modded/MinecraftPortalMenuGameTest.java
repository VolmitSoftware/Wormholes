package art.arcane.wormholes.modded;

import art.arcane.wormholes.access.PortalRole;
import art.arcane.wormholes.portal.PortalPermissionMode;
import art.arcane.wormholes.portal.PortalType;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.AmbientParticleStyle;
import art.arcane.wormholes.portal.BlackoutColor;
import art.arcane.wormholes.transit.MomentumPolicy;
import art.arcane.wormholes.transit.TransitionProfile;
import art.arcane.wormholes.portal.rtp.RtpSettings;
import art.arcane.wormholes.portal.rtp.RtpAllocationMode;
import art.arcane.wormholes.portal.rtp.RtpVerticalMode;
import art.arcane.wormholes.portal.rtp.RtpSafetyMode;
import art.arcane.wormholes.portal.ProjectionMode;
import art.arcane.wormholes.portal.ProjectionRenderMode;
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
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public final class MinecraftPortalMenuGameTest {
    private MinecraftPortalMenuGameTest() {
    }

    public static void run(GameTestHelper helper) {
        WormholesModRuntime runtime = WormholesGameTests.RUNTIME;
        ConnectedPlayer owner = player(helper, "menu-owner");
        ConnectedPlayer outsider = player(helper, "menu-outsider");
        MinecraftPortal source = runtime.portals().create(owner.player().getUUID(), helper.getLevel(), cells(helper, 3), PortalType.PORTAL, new Vec3(0, 0, -1));
        MinecraftPortal destination = runtime.portals().create(owner.player().getUUID(), helper.getLevel(), cells(helper, 15), PortalType.PORTAL, new Vec3(0, 0, -1));
        try {
            helper.assertTrue(runtime.menus().open(outsider.player(), source.getId()) == 0, "Unrelated player opened portal editor");
            runtime.server().getCommands().performPrefixedCommand(owner.player().createCommandSourceStack(), "wormholes edit " + source.getId());
            MinecraftInventoryMenu home = menu(helper, owner.player());
            owner.player().getInventory().setItem(0, new ItemStack(Items.EMERALD, 7));
            home.setCarried(new ItemStack(Items.DIAMOND, 3));
            click(owner.player(), 13);
            helper.assertTrue(source.getProjectionMode() == ProjectionMode.OFF, "Projection toggle did not mutate portal");
            helper.assertTrue(home.getCarried().is(Items.DIAMOND) && home.getCarried().getCount() == 3,
                "Portal editor consumed cursor item");
            home.clicked(54, 0, ContainerInput.QUICK_MOVE, owner.player());
            home.clicked(13, 0, ContainerInput.QUICK_CRAFT, owner.player());
            helper.assertTrue(source.getProjectionMode() == ProjectionMode.OFF && owner.player().getInventory().getItem(0).getCount() == 7,
                "Inventory transfer changed editor state or player items");
            home.setCarried(ItemStack.EMPTY);
            click(owner.player(), 15);
            home.clicked(13, 0, ContainerInput.PICKUP, owner.player());
            helper.assertTrue(source.getProjectionMode() == ProjectionMode.OFF, "Retired menu still accepted actions");
            click(owner.player(), 10);
            click(owner.player(), 12);
            click(owner.player(), 16);
            click(owner.player(), 20);
            click(owner.player(), 22);
            helper.assertTrue(source.getPermissionMode() == PortalPermissionMode.WHITELIST, "Access-mode toggle failed");
            helper.assertTrue(source.isOutgoingTraversalsEnabled() && !source.isIncomingTraversalsEnabled(), "Travel-mode cycle failed");
            helper.assertTrue(!source.isSettingsSyncEnabled(), "Settings-sync toggle failed");
            helper.assertTrue(source.getActivationRange() == Math.round(runtime.configuration().settings().getProjection().range) + 8, "Activation range did not follow global base");
            helper.assertTrue(source.getRenderMode() == ProjectionRenderMode.PANOPTIC, "Render mode did not cycle");
            click(owner.player(), 49);
            click(owner.player(), 20);
            broadcast(owner.player(), "Renamed through native chat");
            helper.assertTrue(source.getName().equals("Renamed through native chat"), "Validated chat hook did not consume rename input");
            click(owner.player(), 11);
            click(owner.player(), find(helper, owner.player(), destination.getName()));
            helper.assertTrue(destination.getId().equals(source.getDestinationId()), "Destination picker did not link portal");
            click(owner.player(), 11);
            click(owner.player(), find(helper, owner.player(), destination.getName()));
            helper.assertTrue(source.getDestinationId() == null, "Destination picker did not unlink selected destination");
            advanced(helper, runtime, owner.player(), source);
            settings(helper, runtime, owner.player(), source);
            MinecraftPortalSurfaceGameTest.run(new MinecraftPortalSurfaceGameTest.Options(helper, runtime, owner.player(), owner.channel()));
            click(owner.player(), 24);
            click(owner.player(), 47);
            broadcast(owner.player(), outsider.player().getUUID().toString());
            helper.assertTrue(source.role(outsider.player().getUUID()) == PortalRole.USER, "Access prompt did not add trusted player");
            MinecraftInventoryMenu access = menu(helper, owner.player());
            access.clicked(0, 1, ContainerInput.PICKUP, owner.player());
            helper.assertTrue(source.role(outsider.player().getUUID()) == PortalRole.CO_OWNER, "Access menu did not cycle role");
            helper.assertTrue(runtime.menus().open(outsider.player(), source.getId()) == 1, "Co-owner could not open portal editor");
            helper.assertTrue(!runtime.menus().transfer(outsider.player(), source.getId(), outsider.player().getUUID()), "Co-owner stole portal ownership");
            MinecraftInventoryMenu revoked = menu(helper, outsider.player());
            runtime.portals().update(owner.player(), source.getId(), portal -> portal.setRole(outsider.player().getUUID(), PortalRole.DENIED));
            revoked.clicked(13, 0, ContainerInput.PICKUP, outsider.player());
            helper.assertTrue(source.getProjectionMode() == ProjectionMode.OFF, "Revoked co-owner changed portal through stale menu");
            runtime.menus().tick();
            helper.assertTrue(outsider.player().containerMenu != revoked, "Revoked menu remained open");
            runtime.menus().open(owner.player(), source.getId());
            click(owner.player(), 20);
            helper.assertTrue(runtime.menus().transfer(owner.player(), source.getId(), outsider.player().getUUID()), "Owner transfer failed");
            broadcast(owner.player(), "Must not replace the name");
            helper.assertTrue(source.getName().equals("Renamed through native chat"), "Pending rename survived ownership revocation");
            helper.assertTrue(source.getOwner().equals(outsider.player().getUUID()) && source.role(outsider.player().getUUID()) == null,
                "Ownership transfer retained new owner's denial override");
            LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS portal_menu owner_access settings chat_rename link_unlink roles revocation transfer inventory_integrity");
        } finally {
            runtime.menus().playerDisconnected(owner.player());
            runtime.menus().playerDisconnected(outsider.player());
            runtime.portals().remove(source.getOwner().equals(owner.player().getUUID()) ? owner.player() : outsider.player(), source.getId());
            runtime.portals().remove(owner.player(), destination.getId());
            owner.close();
            outsider.close();
        }
    }

    private static void advanced(GameTestHelper helper, WormholesModRuntime runtime, ServerPlayer viewer, MinecraftPortal portal) {
        try (AutoCloseable permissions = runtime.access().register((player, node) -> player == viewer && node.startsWith("wormholes.portals.")
            ? MinecraftAccessService.Decision.ALLOW : MinecraftAccessService.Decision.UNSET)) {
            click(viewer, 22);
            click(viewer, 16);
            helper.assertTrue(portal.getType() == PortalType.RTP && portal.getDestinationId() == null, "Mode picker did not select RTP");
            click(viewer, 49);
            click(viewer, 11);
            RtpSettings initial = runtime.rtp().settings(portal);
            click(viewer, 25);
            click(viewer, 15);
            click(viewer, 33);
            helper.assertTrue(!runtime.rtp().settings(portal).isRimEnabled() && !runtime.rtp().settings(portal).isSoundEnabled(),
                "Native RTP effect controls did not persist");
            click(viewer, 49);
            click(viewer, 19);
            click(viewer, 33);
            double centerX = runtime.rtp().settings(portal).getCustomCenterX();
            click(viewer, 37);
            click(viewer, 24);
            helper.assertTrue(runtime.rtp().settings(portal).getCustomCenterX() == centerX + 16D, "Native RTP numeric coordinate control failed");
            click(viewer, 49);
            click(viewer, 52);
            click(viewer, 10);
            helper.assertTrue(runtime.rtp().settings(portal).getTargetBiomeKey() != null, "Native biome picker did not select biome");
            click(viewer, 9);
            helper.assertTrue(runtime.rtp().settings(portal).getTargetBiomeKey() == null, "Native biome picker did not clear biome");
            click(viewer, 49);
            click(viewer, 49);
            click(viewer, 23);
            click(viewer, 15);
            click(viewer, 28);
            click(viewer, 24);
            helper.assertTrue(runtime.rtp().settings(portal).getAllocationMode() == RtpAllocationMode.PER_PLAYER
                && runtime.rtp().settings(portal).getCycleDurationMillis() == initial.getCycleDurationMillis() + 30_000L,
                "Native private rotation controls did not apply");
            click(viewer, 49);
            click(viewer, 49);
            click(viewer, 21);
            click(viewer, 15);
            click(viewer, 22);
            helper.assertTrue(runtime.rtp().settings(portal).getVerticalMode() == RtpVerticalMode.PREFERRED_AVERAGE
                && runtime.rtp().settings(portal).getSafetyMode() == RtpSafetyMode.UNSAFE, "Native landing controls did not apply");
            click(viewer, 49);
            click(viewer, 40);
            helper.assertTrue(runtime.rtp().settings(portal).equals(initial), "Native RTP reset did not restore all defaults");
            click(viewer, 49);
            click(viewer, 22);
            click(viewer, 31);
            helper.assertTrue(portal.isMirrorMode() && portal.getType() == PortalType.PORTAL, "Mirror conversion retained RTP travel");
            menu(helper, viewer).clicked(31, 1, ContainerInput.PICKUP, viewer);
            helper.assertTrue(portal.getMirrorRotation().getDegrees() == 180, "Wall mirror rotation was not coherent");
            click(viewer, 10);
            helper.assertTrue(!portal.isMirrorMode() && portal.getType() == PortalType.PORTAL, "Normal mode did not clear mirror state");
            click(viewer, 49);
            PortalFrame original = portal.getFrame();
            click(viewer, 40);
            click(viewer, 12);
            helper.assertTrue(portal.getDirection() == original.getNormal().reverse(), "Flip face control did not reverse portal");
            click(viewer, 12);
            click(viewer, 16);
            click(viewer, 14);
            helper.assertTrue(portal.getFrame().getNormal() == original.getNormal() && portal.getFrame().getUp() == original.getUp(),
                "Orientation roll controls did not invert each other");
            click(viewer, 49);
            LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS portal_editor type mirror orientation rtp_effects rtp_coordinates rtp_biomes rtp_private rtp_landing rtp_reset");
        } catch (Exception failure) {
            throw new IllegalStateException("Native portal editor acceptance failed", failure);
        }
    }

    private static void settings(GameTestHelper helper, WormholesModRuntime runtime, ServerPlayer viewer, MinecraftPortal portal) {
        click(viewer, 15);
        menu(helper, viewer).clicked(14, 0, ContainerInput.QUICK_MOVE, viewer);
        int depth = portal.getNetworkViewDepth();
        click(viewer, 10);
        helper.assertTrue(portal.getNetworkViewDepth() == depth + 4, "Network editor did not increase depth");
        click(viewer, 31);
        broadcast(viewer, "minecraft:stone");
        helper.assertTrue(portal.getNetworkViewFallbackBlock().equals("minecraft:stone"), "Network fallback prompt did not apply");
        menu(helper, viewer).clicked(31, 1, ContainerInput.PICKUP, viewer);
        helper.assertTrue(portal.getNetworkViewFallbackBlock().equals("minecraft:air"), "Network fallback reset did not apply");
        click(viewer, 49);
        click(viewer, 15);
        click(viewer, 28);
        boolean blackout = portal.isBlackoutBackground();
        click(viewer, 11);
        helper.assertTrue(portal.isBlackoutBackground() != blackout, "Blackout toggle did not apply");
        menu(helper, viewer).clicked(11, 1, ContainerInput.PICKUP, viewer);
        click(viewer, 12);
        helper.assertTrue(portal.getBlackoutColor() == BlackoutColor.values()[2], "Blackout palette did not apply");
        click(viewer, 49);
        AmbientParticleStyle particles = portal.getAmbientStyle();
        click(viewer, 13);
        helper.assertTrue(portal.getAmbientStyle() == particles.next(), "Ambient particles did not cycle");
        menu(helper, viewer).clicked(13, 1, ContainerInput.PICKUP, viewer);
        int red = (portal.getAmbientColor() >> 16) & 255;
        menu(helper, viewer).clicked(10, red == 255 ? 1 : 0, ContainerInput.PICKUP, viewer);
        helper.assertTrue(((portal.getAmbientColor() >> 16) & 255) == red + (red == 255 ? -1 : 1), "Ambient RGB control did not apply");
        click(viewer, 49);
        menu(helper, viewer).clicked(15, 1, ContainerInput.PICKUP, viewer);
        click(viewer, 12);
        helper.assertTrue(portal.getSurfaceSkin().equals("minecraft:glass"), "Skin picker did not apply glass");
        click(viewer, 14);
        helper.assertTrue(portal.getSurfaceSkin().isEmpty(), "Skin picker did not clear glass");
        click(viewer, 49);
        click(viewer, 49);
        click(viewer, 15);
        click(viewer, 32);
        click(viewer, 10);
        click(viewer, 12);
        click(viewer, 14);
        click(viewer, 16);
        helper.assertTrue(portal.setting("fidelity.atmosphere") != null && portal.setting("fidelity.acoustics") != null
            && portal.setting("fidelity.lod") != null && portal.setting("fidelity.block_entities") != null, "Fidelity controls did not persist overrides");
        menu(helper, viewer).clicked(10, 0, ContainerInput.QUICK_MOVE, viewer);
        helper.assertTrue(portal.setting("fidelity.atmosphere") == null, "Fidelity shift click did not restore default");
        click(viewer, 49);
        click(viewer, 15);
        click(viewer, 34);
        menu(helper, viewer).clicked(10, 1, ContainerInput.PICKUP, viewer);
        broadcast(viewer, "2.5");
        helper.assertTrue(MomentumPolicy.decode((String) portal.setting("transit.momentum")).factor() == 2.5D, "Momentum prompt did not apply factor");
        click(viewer, 14);
        click(viewer, 16);
        helper.assertTrue(Boolean.TRUE.equals(portal.setting("transit.membrane")) && Boolean.TRUE.equals(portal.setting("transit.bounce")),
            "Transit membrane and bounce controls did not persist");
        menu(helper, viewer).clicked(31, 0, ContainerInput.QUICK_MOVE, viewer);
        broadcast(viewer, "17");
        helper.assertTrue(TransitionProfile.decode((String) portal.setting("transit.profile")).maskOverrideTicks() == 17,
            "Arrival mask prompt did not apply");
        click(viewer, 49);
        LoggerFactory.getLogger("WormholesGameTest").info("WORMHOLES_GAME_TEST_PASS portal_settings network fallback blackout ambient skin fidelity transit");
    }

    private static MinecraftInventoryMenu menu(GameTestHelper helper, ServerPlayer player) {
        helper.assertTrue(player.containerMenu instanceof MinecraftInventoryMenu, "Expected native portal inventory");
        return (MinecraftInventoryMenu) player.containerMenu;
    }

    private static void click(ServerPlayer player, int slot) {
        player.containerMenu.clicked(slot, 0, ContainerInput.PICKUP, player);
    }

    private static int find(GameTestHelper helper, ServerPlayer player, String name) {
        for (int slot = 0; slot < 45; slot++) {
            Component title = player.containerMenu.getSlot(slot).getItem().get(DataComponents.CUSTOM_NAME);
            if (title != null && title.getString().equals(name)) {
                return slot;
            }
        }
        helper.fail("Destination missing from native picker: " + name);
        return -1;
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

    private record ConnectedPlayer(ServerPlayer player, EmbeddedChannel channel) implements AutoCloseable {
        @Override
        public void close() {
            player.closeContainer();
            channel.finishAndReleaseAll();
        }
    }
}
