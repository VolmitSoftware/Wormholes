package art.arcane.wormholes.network.view;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.World;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.util.Cuboid;
import art.arcane.wormholes.util.Direction;

public final class ViewServerProjectionBoxTest {
    @Test
    public void horizontalPortalUsesDepthOnlyAlongItsNormal() {
        double previousPadding = Settings.PROJECTION_APERTURE_PADDING_BLOCKS;
        Settings.PROJECTION_APERTURE_PADDING_BLOCKS = 0.75D;
        try {
            ILocalPortal portal = portal(Direction.E, 12);

            assertEquals(new ViewBox(-54, 51, 7, 74, 79, 37), ViewServer.computeBox(portal, 64));
        } finally {
            Settings.PROJECTION_APERTURE_PADDING_BLOCKS = previousPadding;
        }
    }

    @Test
    public void verticalPortalUsesDepthOnlyAlongItsNormalAndClampsWorldHeight() {
        double previousPadding = Settings.PROJECTION_APERTURE_PADDING_BLOCKS;
        Settings.PROJECTION_APERTURE_PADDING_BLOCKS = 0.75D;
        try {
            ILocalPortal portal = portal(Direction.U, 12);

            assertEquals(new ViewBox(-3, 0, 7, 27, 127, 35), ViewServer.computeBox(portal, 64));
        } finally {
            Settings.PROJECTION_APERTURE_PADDING_BLOCKS = previousPadding;
        }
    }

    @Test
    public void largerAperturePaddingAddsEnoughWholeBlockCaptureGuard() {
        double previousPadding = Settings.PROJECTION_APERTURE_PADDING_BLOCKS;
        Settings.PROJECTION_APERTURE_PADDING_BLOCKS = 1.25D;
        try {
            ILocalPortal portal = portal(Direction.E, 12);

            assertEquals(new ViewBox(-54, 50, 6, 74, 80, 38), ViewServer.computeBox(portal, 64));
        } finally {
            Settings.PROJECTION_APERTURE_PADDING_BLOCKS = previousPadding;
        }
    }

    @Test
    public void everyOppositePortalNormalUsesTheSameCaptureAxisPolicy() {
        double previousPadding = Settings.PROJECTION_APERTURE_PADDING_BLOCKS;
        Settings.PROJECTION_APERTURE_PADDING_BLOCKS = 0.75D;
        try {
            assertEquals(ViewServer.computeBox(portal(Direction.E, 12), 64),
                ViewServer.computeBox(portal(Direction.W, 12), 64));
            assertEquals(new ViewBox(-3, 51, -44, 27, 79, 84),
                ViewServer.computeBox(portal(Direction.N, 12), 64));
            assertEquals(ViewServer.computeBox(portal(Direction.N, 12), 64),
                ViewServer.computeBox(portal(Direction.S, 12), 64));
            assertEquals(ViewServer.computeBox(portal(Direction.U, 12), 64),
                ViewServer.computeBox(portal(Direction.D, 12), 64));
        } finally {
            Settings.PROJECTION_APERTURE_PADDING_BLOCKS = previousPadding;
        }
    }

    @Test
    public void apertureGuardRefreshesEachActivePortalOnlyWhenItsWholeBlockGuardChanges() {
        double previousPadding = Settings.PROJECTION_APERTURE_PADDING_BLOCKS;
        UUID firstPortal = UUID.fromString("00000000-0000-0000-0000-000000000101");
        UUID secondPortal = UUID.fromString("00000000-0000-0000-0000-000000000102");
        List<UUID> activePortalIds = List.of(firstPortal, secondPortal);
        List<UUID> refreshedPortalIds = new ArrayList<UUID>();
        try {
            Settings.PROJECTION_APERTURE_PADDING_BLOCKS = 0.75D;
            int storedGuard = ViewServer.currentCaptureApertureGuard();

            Settings.PROJECTION_APERTURE_PADDING_BLOCKS = 0.95D;
            storedGuard = ViewServer.updateCaptureApertureGuard(
                storedGuard,
                ViewServer.currentCaptureApertureGuard(),
                activePortalIds,
                refreshedPortalIds::add);

            assertEquals(1, storedGuard);
            assertEquals(List.of(), refreshedPortalIds);

            Settings.PROJECTION_APERTURE_PADDING_BLOCKS = 1.25D;
            storedGuard = ViewServer.updateCaptureApertureGuard(
                storedGuard,
                ViewServer.currentCaptureApertureGuard(),
                activePortalIds,
                refreshedPortalIds::add);

            assertEquals(2, storedGuard);
            assertEquals(activePortalIds, refreshedPortalIds);

            storedGuard = ViewServer.updateCaptureApertureGuard(
                storedGuard,
                ViewServer.currentCaptureApertureGuard(),
                activePortalIds,
                refreshedPortalIds::add);

            assertEquals(2, storedGuard);
            assertEquals(activePortalIds, refreshedPortalIds);
        } finally {
            Settings.PROJECTION_APERTURE_PADDING_BLOCKS = previousPadding;
        }
    }

    private static ILocalPortal portal(Direction normal, int lateralPad) {
        World world = world();
        PortalStructure structure = new PortalStructure();
        Map<String, Object> area = new HashMap<String, Object>();
        area.put("worldKey", "minecraft:overworld");
        if (normal.x() != 0) {
            area.put("x1", Integer.valueOf(10));
            area.put("y1", Integer.valueOf(64));
            area.put("z1", Integer.valueOf(20));
            area.put("x2", Integer.valueOf(10));
            area.put("y2", Integer.valueOf(66));
            area.put("z2", Integer.valueOf(24));
        } else if (normal.y() != 0) {
            area.put("x1", Integer.valueOf(10));
            area.put("y1", Integer.valueOf(64));
            area.put("z1", Integer.valueOf(20));
            area.put("x2", Integer.valueOf(14));
            area.put("y2", Integer.valueOf(64));
            area.put("z2", Integer.valueOf(22));
        } else {
            area.put("x1", Integer.valueOf(10));
            area.put("y1", Integer.valueOf(64));
            area.put("z1", Integer.valueOf(20));
            area.put("x2", Integer.valueOf(14));
            area.put("y2", Integer.valueOf(66));
            area.put("z2", Integer.valueOf(20));
        }
        structure.setArea(new Cuboid(area));
        structure.setWorld(world);
        PortalFrame frame = PortalFrame.canonical(normal);
        return (ILocalPortal) Proxy.newProxyInstance(
            ILocalPortal.class.getClassLoader(),
            new Class<?>[] { ILocalPortal.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "getStructure" -> structure;
                case "getFrame" -> frame;
                case "getNetworkViewLateralPad" -> Integer.valueOf(lateralPad);
                default -> null;
            });
    }

    private static World world() {
        return (World) Proxy.newProxyInstance(
            World.class.getClassLoader(),
            new Class<?>[] { World.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "getMinHeight" -> Integer.valueOf(0);
                case "getMaxHeight" -> Integer.valueOf(128);
                default -> null;
            });
    }
}
