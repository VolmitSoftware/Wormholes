package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.bukkit.World;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.junit.jupiter.api.Test;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMapData;

import art.arcane.wormholes.network.view.ProjectedMapData;

public final class ProjectedMapDataTest {
    @Test
    public void encodedPayloadRoundTripsEveryFieldAndOwnsItsPixels() {
        byte[] source = pixels();
        ProjectedMapData original = new ProjectedMapData(73, (byte) 3, true, true, source);
        source[0] = 99;

        ProjectedMapData decoded = ProjectedMapData.decode(original.encode());
        byte[] exposed = decoded.pixels();
        exposed[1] = 88;

        assertEquals(original, decoded);
        assertNotEquals(99, decoded.pixels()[0]);
        assertNotEquals(88, decoded.pixels()[1]);
        assertEquals(73, decoded.sourceMapId());
        assertEquals((byte) 3, decoded.scale());
        assertTrue(decoded.tracking());
        assertTrue(decoded.locked());
    }

    @Test
    public void decodeRejectsMalformedPayloadHeadersAndShape() {
        ProjectedMapData mapData = new ProjectedMapData(1, (byte) 0, false, false, pixels());
        byte[] encoded = mapData.encode();
        byte[] invalidMagic = encoded.clone();
        invalidMagic[0] ^= 1;
        byte[] invalidVersion = encoded.clone();
        invalidVersion[4] = 2;
        byte[] invalidScale = encoded.clone();
        invalidScale[9] = 5;
        byte[] invalidFlags = encoded.clone();
        invalidFlags[10] = 4;

        assertThrows(IllegalArgumentException.class,
            () -> ProjectedMapData.decode(Arrays.copyOf(encoded, encoded.length - 1)));
        assertThrows(IllegalArgumentException.class, () -> ProjectedMapData.decode(invalidMagic));
        assertThrows(IllegalArgumentException.class, () -> ProjectedMapData.decode(invalidVersion));
        assertThrows(IllegalArgumentException.class, () -> ProjectedMapData.decode(invalidScale));
        assertThrows(IllegalArgumentException.class, () -> ProjectedMapData.decode(invalidFlags));
        assertThrows(IllegalArgumentException.class,
            () -> new ProjectedMapData(1, (byte) 0, false, false, new byte[1]));
    }

    @Test
    public void horizontalMirrorReversesEachRowAndIsAnInvolution() {
        ProjectedMapData original = new ProjectedMapData(19, (byte) 2, true, false, pixels());
        ProjectedMapData mirrored = original.mirrorHorizontally();
        byte[] source = original.pixels();
        byte[] reflected = mirrored.pixels();

        for (int y = 0; y < ProjectedMapData.HEIGHT; y++) {
            int row = y * ProjectedMapData.WIDTH;
            for (int x = 0; x < ProjectedMapData.WIDTH; x++) {
                assertEquals(source[row + (ProjectedMapData.WIDTH - 1 - x)], reflected[row + x]);
            }
        }
        assertEquals(original, mirrored.mirrorHorizontally());
    }

    @Test
    public void packetUsesVirtualIdAndFullDefensivePixelPatch() {
        ProjectedEntityPacketRecorder recorder = ProjectedEntityPacketRecorder.install();
        try {
            ProjectedMapData mapData = new ProjectedMapData(7, (byte) 4, true, true, pixels());

            WrapperPlayServerMapData packet = mapData.toPacket(-31);

            assertEquals(-31, packet.getMapId());
            assertEquals((byte) 4, packet.getScale());
            assertTrue(packet.isTrackingPosition());
            assertTrue(packet.isLocked());
            assertEquals(List.of(), packet.getDecorations());
            assertEquals(ProjectedMapData.WIDTH, packet.getColumns());
            assertEquals(ProjectedMapData.HEIGHT, packet.getRows());
            assertEquals(0, packet.getX());
            assertEquals(0, packet.getZ());
            assertArrayEquals(mapData.pixels(), packet.getData());
            packet.getData()[0] = 101;
            assertNotEquals(101, mapData.pixels()[0]);
        } finally {
            recorder.uninstall();
        }
    }

    @Test
    public void guardedCaptureReadsOnlyAnUnlayeredVanillaMapView() {
        byte[] rawPixels = pixels();
        CraftMapView mapView = new CraftMapView(41, MapView.Scale.FAR, true, true, rawPixels);

        Optional<ProjectedMapData> captured = ProjectedMapData.capture(mapView);
        rawPixels[0] = 112;

        assertTrue(captured.isPresent());
        assertEquals(41, captured.orElseThrow().sourceMapId());
        assertEquals(MapView.Scale.FAR.getValue(), captured.orElseThrow().scale());
        assertTrue(captured.orElseThrow().tracking());
        assertTrue(captured.orElseThrow().locked());
        assertNotEquals(112, captured.orElseThrow().pixels()[0]);

        mapView.addRenderer(new CustomRenderer());
        assertFalse(ProjectedMapData.capture(mapView).isPresent());
        assertFalse(ProjectedMapData.capture((MapView) null).isPresent());
        assertFalse(ProjectedMapData.capture(
            new CraftMapView(41, MapView.Scale.FAR, true, true, new byte[1])).isPresent());
    }

    @Test
    public void itemFrameCaptureResolvesItsMapViewWithoutPlatformClasses() {
        CraftMapView mapView = new CraftMapView(52, MapView.Scale.CLOSE, false, true, pixels());
        MapMeta mapMeta = mapMeta(mapView);
        ItemStack item = new TestMapItemStack(mapMeta);
        ItemFrame itemFrame = itemFrame(item);

        Optional<ProjectedMapData> captured = ProjectedMapData.capture(itemFrame);

        assertTrue(captured.isPresent());
        assertEquals(52, captured.orElseThrow().sourceMapId());
        assertEquals(MapView.Scale.CLOSE.getValue(), captured.orElseThrow().scale());
        assertFalse(captured.orElseThrow().tracking());
        assertTrue(captured.orElseThrow().locked());
    }

    private static byte[] pixels() {
        byte[] pixels = new byte[ProjectedMapData.PIXEL_COUNT];
        for (int y = 0; y < ProjectedMapData.HEIGHT; y++) {
            int row = y * ProjectedMapData.WIDTH;
            for (int x = 0; x < ProjectedMapData.WIDTH; x++) {
                pixels[row + x] = (byte) ((x * 31) + (y * 17));
            }
        }
        return pixels;
    }

    private static MapMeta mapMeta(MapView mapView) {
        InvocationHandler handler = (proxy, method, arguments) -> {
            String name = method.getName();
            if ("hasMapView".equals(name)) {
                return Boolean.TRUE;
            }
            if ("getMapView".equals(name)) {
                return mapView;
            }
            if ("hasMapId".equals(name)) {
                return Boolean.TRUE;
            }
            if ("getMapId".equals(name)) {
                return Integer.valueOf(mapView.getId());
            }
            if ("clone".equals(name)) {
                return proxy;
            }
            return defaultValue(method);
        };
        return (MapMeta) Proxy.newProxyInstance(
            MapMeta.class.getClassLoader(), new Class<?>[] {MapMeta.class}, handler);
    }

    private static ItemFrame itemFrame(ItemStack item) {
        InvocationHandler handler = (proxy, method, arguments) -> {
            if ("getItem".equals(method.getName())) {
                return item;
            }
            return defaultValue(method);
        };
        return (ItemFrame) Proxy.newProxyInstance(
            ItemFrame.class.getClassLoader(), new Class<?>[] {ItemFrame.class}, handler);
    }

    private static Object defaultValue(Method method) {
        Class<?> type = method.getReturnType();
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == byte.class) {
            return Byte.valueOf((byte) 0);
        }
        if (type == short.class) {
            return Short.valueOf((short) 0);
        }
        if (type == int.class) {
            return Integer.valueOf(0);
        }
        if (type == long.class) {
            return Long.valueOf(0L);
        }
        if (type == float.class) {
            return Float.valueOf(0.0F);
        }
        if (type == double.class) {
            return Double.valueOf(0.0D);
        }
        if (type == char.class) {
            return Character.valueOf('\0');
        }
        return null;
    }

    private static final class TestMapItemStack extends ItemStack {
        private final ItemMeta itemMeta;

        private TestMapItemStack(ItemMeta itemMeta) {
            super();
            this.itemMeta = itemMeta;
        }

        @Override
        public ItemMeta getItemMeta() {
            return itemMeta;
        }
    }

    private static final class CraftMapView implements MapView {
        private final FakeWorldMap worldMap;
        private final int id;
        private final List<MapRenderer> renderers;
        private Scale scale;
        private boolean tracking;
        private boolean locked;

        private CraftMapView(int id, Scale scale, boolean tracking, boolean locked, byte[] colors) {
            this.worldMap = new FakeWorldMap(colors);
            this.id = id;
            this.renderers = new ArrayList<MapRenderer>();
            this.renderers.add(new CraftMapRenderer());
            this.scale = scale;
            this.tracking = tracking;
            this.locked = locked;
        }

        @Override
        public int getId() {
            return id;
        }

        @Override
        public boolean isVirtual() {
            return false;
        }

        @Override
        public Scale getScale() {
            return scale;
        }

        @Override
        public void setScale(Scale scale) {
            this.scale = scale;
        }

        @Override
        public int getCenterX() {
            return 0;
        }

        @Override
        public int getCenterZ() {
            return 0;
        }

        @Override
        public void setCenterX(int x) {
        }

        @Override
        public void setCenterZ(int z) {
        }

        @Override
        public World getWorld() {
            return null;
        }

        @Override
        public void setWorld(World world) {
        }

        @Override
        public List<MapRenderer> getRenderers() {
            return List.copyOf(renderers);
        }

        @Override
        public void addRenderer(MapRenderer renderer) {
            renderers.add(renderer);
        }

        @Override
        public boolean removeRenderer(MapRenderer renderer) {
            return renderers.remove(renderer);
        }

        @Override
        public boolean isTrackingPosition() {
            return tracking;
        }

        @Override
        public void setTrackingPosition(boolean tracking) {
            this.tracking = tracking;
        }

        @Override
        public boolean isUnlimitedTracking() {
            return false;
        }

        @Override
        public void setUnlimitedTracking(boolean unlimited) {
        }

        @Override
        public boolean isLocked() {
            return locked;
        }

        @Override
        public void setLocked(boolean locked) {
            this.locked = locked;
        }
    }

    private static final class FakeWorldMap {
        private final byte[] colors;

        private FakeWorldMap(byte[] colors) {
            this.colors = colors;
        }
    }

    private static final class CraftMapRenderer extends MapRenderer {
        @Override
        public void render(MapView map, MapCanvas canvas, Player player) {
        }
    }

    private static final class CustomRenderer extends MapRenderer {
        @Override
        public void render(MapView map, MapCanvas canvas, Player player) {
        }
    }
}
