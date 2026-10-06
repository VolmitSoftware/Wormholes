package art.arcane.wormholes.network.view;

import art.arcane.volmlib.nativelib.NativeAdapters;
import art.arcane.volmlib.nativelib.map.MapPixelsAccess;
import java.util.List;
import java.util.Optional;
import org.bukkit.entity.ItemFrame;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.MapMeta;
import org.bukkit.map.MapView;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMapData;
import art.arcane.optics.entity.MapSnapshot;

public final class BukkitProjectedMapData {
    private static volatile MapPixelsAccess nativeMaps;

    private BukkitProjectedMapData() {
    }

    public static Optional<MapSnapshot> capture(ItemFrame itemFrame) {
        if (itemFrame == null) {
            return Optional.empty();
        }
        try {
            ItemStack item = itemFrame.getItem();
            if (item == null) {
                return Optional.empty();
            }
            ItemMeta itemMeta = item.getItemMeta();
            if (!(itemMeta instanceof MapMeta mapMeta) || !mapMeta.hasMapView()) {
                return Optional.empty();
            }
            return capture(mapMeta.getMapView());
        } catch (RuntimeException | LinkageError error) {
            return Optional.empty();
        }
    }

    public static Optional<MapSnapshot> capture(MapView mapView) {
        MapPixelsAccess access = nativeMaps;
        if (access == null) {
            access = NativeAdapters.find(MapPixelsAccess.class).orElse(null);
            nativeMaps = access;
        }
        if (access == null) {
            return Optional.empty();
        }
        return access.capture(mapView).map(snapshot -> new MapSnapshot(
            snapshot.sourceMapId(), snapshot.scale(), snapshot.tracking(), snapshot.locked(), snapshot.pixels()));
    }

    public static WrapperPlayServerMapData toPacket(MapSnapshot data, int virtualMapId) {
        return new WrapperPlayServerMapData(
            virtualMapId, data.scale(), data.tracking(), data.locked(), List.of(),
            MapSnapshot.WIDTH, MapSnapshot.HEIGHT, 0, 0, data.pixels());
    }

}
