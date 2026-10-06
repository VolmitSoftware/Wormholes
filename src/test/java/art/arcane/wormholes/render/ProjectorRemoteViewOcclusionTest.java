package art.arcane.wormholes.render;

import art.arcane.wormholes.network.view.BukkitRemoteViewCodec;

import com.github.retrooper.packetevents.protocol.player.Equipment;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.UUID;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.junit.jupiter.api.Test;
import art.arcane.wormholes.network.view.RemoteViewCache;
import art.arcane.optics.math.BlockBox;
import art.arcane.wormholes.render.view.RemoteWorldView;
import art.arcane.wormholes.render.view.OccludedMarker;
import art.arcane.optics.math.Face;
import static org.junit.jupiter.api.Assertions.assertTrue;
import art.arcane.optics.occlusion.ProjectorViewOcclusion;

public final class ProjectorRemoteViewOcclusionTest {
    @Test
    public void unavailableRemoteSliceCellFailsOpenWithoutLeakingTargetSample() throws Exception {
        RemoteViewCache<BlockData, EntityData<?>, Equipment> cache = new RemoteViewCache<>(BukkitRemoteViewCodec.INSTANCE, RemoteViewCache.Options.defaults());
        RemoteViewCache.RemoteView<BlockData, EntityData<?>, Equipment> cached = cache.getOrCreate("peer", UUID.randomUUID());
        Field box = RemoteViewCache.RemoteView.class.getDeclaredField("box");
        box.setAccessible(true);
        box.set(cached, BlockBox.spanning(0, -64, 0, 15, 319, 15));
        RemoteWorldView remoteView = new RemoteWorldView(cached, blockData(Material.AIR));
        ProjectorViewOcclusion<BlockData> occlusion = new ProjectorViewOcclusion<BlockData>(OccludedMarker::isOccluding);
        occlusion.beginPass(0.5D, 0.5D, 0.5D, Face.W);

        assertTrue(occlusion.visible(remoteView, 7, 0, 0, 0.5D, 0.5D, 0.5D));
    }
    private static BlockData blockData(Material material) {
        return (BlockData) Proxy.newProxyInstance(BlockData.class.getClassLoader(), new Class<?>[] { BlockData.class },
            (proxy, method, args) -> switch (method.getName()) {
                case "getMaterial" -> material;
                case "toString", "getAsString" -> material.name();
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == args[0]);
                case "clone" -> proxy;
                default -> null;
            });
    }

}
