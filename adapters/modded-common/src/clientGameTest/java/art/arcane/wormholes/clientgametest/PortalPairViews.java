package art.arcane.wormholes.clientgametest;

import art.arcane.optics.math.Vec3d;
import art.arcane.wormholes.clientgametest.menu.MenuScreenDriver;
import art.arcane.wormholes.menu.portal.PortalRoutes;
import art.arcane.wormholes.modded.client.ClientPortal;
import art.arcane.wormholes.modded.client.ClientSeamlessTravel;
import art.arcane.wormholes.modded.client.WormholesClient;
import art.arcane.wormholes.modded.client.menu.MenuConfirmOverlay;
import art.arcane.wormholes.modded.client.menu.NativeMenuListener;
import art.arcane.wormholes.modded.client.menu.NativeMenuScreen;
import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.modded.client.render.stencil.PortalStencilRenderer;
import art.arcane.wormholes.modded.client.render.stencil.PortalView;
import art.arcane.wormholes.network.client.TravelMessage;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

final class PortalPairViews {
    private static final String NATIVE_MENU = "native menu";
    private static final String CHEST_MENU = "chest menu";
    private static final String DELETE_ELEMENT = "destroy";
    private static final int MENU_TIMEOUT_TICKS = 100;
    private static final int LOOK_SETTLE_TICKS = 4;
    private static final int PLACE_TIMEOUT_TICKS = 400;
    private static final double PLACED_BLOCKS = 0.5D;

    private PortalPairViews() {
    }

    static boolean viewAt(Minecraft minecraft, PortalPairScene.Pair pair, PortalPairScene.Side side) {
        return !viewsAt(minecraft, pair, side).isEmpty();
    }

    static String remaining(Minecraft minecraft, PortalPairScene.Pair pair, PortalPairScene.Side removed) {
        WormholesClient client = WormholesClient.instance();
        if (client == null) {
            return "";
        }
        List<String> remaining = new ArrayList<>();
        for (PortalPairScene.Side side : PortalPairScene.Side.values()) {
            for (PortalView view : viewsAt(minecraft, pair, side)) {
                remaining.add(side + " " + view.kind() + " view into " + view.destination().dimension().identifier()
                    + (PortalStencilRenderer.instance().claims(view.surface().geometry()) ? " (claimed)" : ""));
            }
            for (ClientPortal portal : streams(client, pair, side)) {
                if (side != removed && NativeClientViewAssertions.sections(portal.portalKey()) == 0
                    && !ClientPortalRenderer.instance().available(portal.portalKey())) {
                    continue;
                }
                remaining.add(side + " stream " + portal.portalKey() + " sections " + NativeClientViewAssertions.sections(portal.portalKey())
                    + (ClientPortalRenderer.instance().available(portal.portalKey()) ? " (renderable)" : ""));
            }
            if (client.seamlessTravel().armed(pair.portal(side))) {
                remaining.add(side + " travel arm");
            }
        }
        ClientSeamlessTravel travel = client.seamlessTravel();
        for (TravelMessage.TravelBegin arm : travel.arms()) {
            if (arm.sourcePortal().equals(pair.local()) || arm.sourcePortal().equals(pair.remote())) {
                remaining.add("arm token " + arm.token() + " from " + arm.sourcePortal());
            }
        }
        if (travel.crossing() != null && minecraft.level != null && travel.crossing().level() != minecraft.level) {
            remaining.add("crossing view into " + travel.crossing().level().dimension().identifier());
        }
        return String.join("; ", remaining);
    }

    static String describe(Minecraft minecraft) {
        WormholesClient client = WormholesClient.instance();
        if (client == null || minecraft.level == null || minecraft.player == null) {
            return "no client level";
        }
        StringBuilder state = new StringBuilder();
        state.append("level ").append(minecraft.level.dimension().identifier()).append(" player ").append(minecraft.player.position())
            .append(" yaw ").append(minecraft.player.getYRot()).append(" pitch ").append(minecraft.player.getXRot())
            .append(" armed ").append(client.seamlessTravel().armed()).append(" pending ").append(client.seamlessTravel().pending())
            .append(" unprepared [").append(client.seamlessTravel().unprepared()).append(']')
            .append(" stencil [").append(PortalStencilRenderer.instance().debugLine()).append(']');
        for (TravelMessage.TravelBegin arm : client.seamlessTravel().arms()) {
            state.append(" arm ").append(arm.sourcePortal()).append(' ').append(arm.sourceWorld()).append("->").append(arm.world().dimension())
                .append(" resident ").append(arm.resident()).append(" handle ").append(arm.levelHandle());
        }
        for (PortalView view : client.portalViews().current()) {
            state.append(" view ").append(view.kind()).append(' ').append(view.source().dimension().identifier()).append("->")
                .append(view.destination().dimension().identifier()).append(' ').append(view.surface().area());
        }
        for (ClientPortal portal : client.session().portals().values()) {
            state.append(" stream ").append(portal.portalKey()).append(" nested ").append(portal.nested()).append(" origin ")
                .append(portal.geometry().originX()).append(',').append(portal.geometry().originY()).append(',').append(portal.geometry().originZ());
        }
        return state.toString();
    }

    static String deleteThroughMenu(SeamlessClient client, PortalPairScene.Pair pair, PortalPairScene.Side side) {
        Vec3 center = pair.center(side);
        Vec3 eye = client.computeOnClient(minecraft -> minecraft.player.getEyePosition());
        double dx = center.x - eye.x;
        double dy = center.y - eye.y;
        double dz = center.z - eye.z;
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        client.lookAt(yaw, pitch);
        client.waitTicks(LOOK_SETTLE_TICKS);
        SeamlessScenario.assertTrue(client.computeOnClient(minecraft -> minecraft.player.getMainHandItem().is(Items.BLAZE_ROD)),
            "the player is not holding the portal wand");
        client.runOnClient(minecraft -> minecraft.gameMode.useItem(minecraft.player, InteractionHand.MAIN_HAND));
        int opened = awaitTicks(client, minecraft -> nativeDelete(minecraft) || deleteSlot(minecraft) >= 0, MENU_TIMEOUT_TICKS);
        SeamlessScenario.assertTrue(opened >= 0, "the portal wand did not open a native or chest portal menu with a Delete Portal control: screen "
            + client.computeOnClient(PortalPairViews::screen) + "; " + client.computeOnClient(PortalPairViews::describe));
        if (client.computeOnClient(PortalPairViews::nativeDelete)) {
            deleteThroughNativeMenu(client);
            return NATIVE_MENU;
        }
        client.runOnClient(minecraft -> {
            AbstractContainerMenu menu = minecraft.player.containerMenu;
            minecraft.gameMode.handleContainerInput(menu.containerId, deleteSlot(minecraft), 0, ContainerInput.QUICK_MOVE, minecraft.player);
        });
        return CHEST_MENU;
    }

    static void deleteThroughCommand(SeamlessClient client, PortalPairScene.Pair pair, PortalPairScene.Side side) {
        client.runOnClient(minecraft -> minecraft.getConnection().sendCommand("wormholes portals remove " + pair.portal(side)));
    }

    static int clientGunpowder(Minecraft minecraft) {
        return PortalPairScene.gunpowder(minecraft.player.getInventory());
    }

    static void awaitPlaced(SeamlessClient client, SeamlessServer server, ResourceKey<Level> level, String label) {
        Vec3 target = server.playerPosition();
        String dimension = level.identifier().toString();
        int placed = awaitTicks(client, minecraft -> minecraft.level != null && minecraft.player != null && ClientGui.screen(minecraft) == null
            && minecraft.level.dimension().identifier().toString().equals(dimension) && minecraft.player.position().distanceTo(target) < PLACED_BLOCKS,
            PLACE_TIMEOUT_TICKS);
        SeamlessScenario.assertTrue(placed >= 0, label + ": the client never settled at " + target + " in " + dimension + ": " + client.computeOnClient(PortalPairViews::describe)
            + " screen " + client.computeOnClient(minecraft -> String.valueOf(ClientGui.screen(minecraft))));
        client.waitForChunksDownload();
    }

    static int awaitTicks(SeamlessClient client, Predicate<Minecraft> condition, int timeoutTicks) {
        for (int tick = 0; tick <= timeoutTicks; tick++) {
            if (client.computeOnClient(condition::test)) {
                return tick;
            }
            client.waitTicks(1);
        }
        return -1;
    }

    private static void deleteThroughNativeMenu(SeamlessClient client) {
        MenuScreenDriver driver = new MenuScreenDriver(client.menuInput());
        driver.click(DELETE_ELEMENT);
        int armed = awaitTicks(client, minecraft -> NativeMenuListener.current().flatMap(NativeMenuScreen::confirmation)
            .filter(MenuConfirmOverlay::armed).isPresent(), MENU_TIMEOUT_TICKS);
        SeamlessScenario.assertTrue(armed >= 0, NATIVE_MENU + ": Delete Portal did not show an armed confirmation: screen "
            + client.computeOnClient(PortalPairViews::screen) + "; " + client.computeOnClient(PortalPairViews::describe));
        driver.confirm();
    }

    private static boolean nativeDelete(Minecraft minecraft) {
        return NativeMenuListener.current()
            .filter(screen -> screen.snapshot().screenId().equals(PortalRoutes.HOME) && screen.confirmation().isEmpty())
            .flatMap(screen -> screen.widgetFor(DELETE_ELEMENT))
            .isPresent();
    }

    private static String screen(Minecraft minecraft) {
        Optional<NativeMenuScreen> current = NativeMenuListener.current();
        if (current.isPresent()) {
            return NATIVE_MENU + " " + current.get().snapshot().screenId() + (current.get().confirmation().isPresent() ? " (confirming)" : "");
        }
        return String.valueOf(ClientGui.screen(minecraft));
    }

    private static int deleteSlot(Minecraft minecraft) {
        if (minecraft.player == null || minecraft.player.containerMenu == minecraft.player.inventoryMenu) {
            return -1;
        }
        for (Slot slot : minecraft.player.containerMenu.slots) {
            if (slot.container != minecraft.player.getInventory() && slot.getItem().is(Items.GUNPOWDER)) {
                return slot.index;
            }
        }
        return -1;
    }

    private static List<PortalView> viewsAt(Minecraft minecraft, PortalPairScene.Pair pair, PortalPairScene.Side side) {
        WormholesClient client = WormholesClient.instance();
        if (client == null || minecraft.level == null || !minecraft.level.dimension().equals(pair.level(side))) {
            return List.of();
        }
        Vec3 center = pair.center(side);
        Vec3d point = new Vec3d(center.x, center.y, center.z);
        List<PortalView> found = new ArrayList<>(2);
        for (PortalView view : client.portalViews().current()) {
            if (view.source() == minecraft.level && view.surface().area().contains(point)) {
                found.add(view);
            }
        }
        return found;
    }

    private static List<ClientPortal> streams(WormholesClient client, PortalPairScene.Pair pair, PortalPairScene.Side side) {
        BlockPos min = pair.min(side);
        List<ClientPortal> found = new ArrayList<>(1);
        for (ClientPortal portal : client.session().portals().values()) {
            if (!portal.nested() && portal.geometry().originX() == min.getX() && portal.geometry().originY() == min.getY()
                && portal.geometry().originZ() == min.getZ()) {
                found.add(portal);
            }
        }
        return found;
    }
}
