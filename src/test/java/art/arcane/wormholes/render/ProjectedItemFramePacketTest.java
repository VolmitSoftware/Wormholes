package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.entity.type.EntityTypes;
import com.github.retrooper.packetevents.protocol.component.ComponentTypes;
import com.github.retrooper.packetevents.protocol.item.ItemStack;
import com.github.retrooper.packetevents.protocol.item.type.ItemTypes;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.protocol.world.BlockFace;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerMapData;

import art.arcane.volmlib.util.collection.KList;
import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.network.view.PacketBlobs;
import art.arcane.wormholes.network.view.ProjectedMapData;
import art.arcane.wormholes.network.view.RemoteViewCache;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.render.view.ProjectionEntityView;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

public final class ProjectedItemFramePacketTest {
    @Test
    public void snapshotItemFramesSpawnAndStayOnTheTransformedAttachmentFace() {
        ProjectedEntityPacketRecorder recorder = ProjectedEntityPacketRecorder.install();
        try {
            EntityRenderPacketChannel channel = new EntityRenderPacketChannel();
            EntityRenderPlayerIdentity identity = new EntityRenderPlayerIdentity(channel);
            EntityRenderSpoofRegistry registry = new EntityRenderSpoofRegistry(channel, identity);
            EntityRenderMetadataBridge metadataBridge = new EntityRenderMetadataBridge(channel);
            EntityRenderVisualProjector projector = new EntityRenderVisualProjector(
                channel, registry, identity, metadataBridge);
            PortalFrame localFrame = PortalFrame.canonical(Direction.N);
            PortalFrame remoteFrame = PortalFrame.canonical(Direction.U);
            ILocalPortal localPortal = localPortal(new Vector(1.5D, 1.5D, 5.0D), localFrame);
            Frustum4D frustum = new Frustum4D(
                new Location(null, 1.5D, 1.5D, 0.0D), new TestStructure(), 16.0D, 16.0D);
            Player observer = ProjectedEntityPacketRecorder.player(true);

            assertTrue(project(projector, observer, localPortal, localFrame, remoteFrame,
                frustum, "minecraft:item_frame", false));
            assertTrue(project(projector, observer, localPortal, localFrame, remoteFrame,
                frustum, "minecraft:glow_item_frame", true));

            List<WrapperPlayServerSpawnEntity> spawns = recorder.sentOfType(WrapperPlayServerSpawnEntity.class);
            assertEquals(2, spawns.size());
            assertEquals(EntityTypes.ITEM_FRAME, spawns.get(0).getEntityType());
            assertEquals(EntityTypes.GLOW_ITEM_FRAME, spawns.get(1).getEntityType());
            for (WrapperPlayServerSpawnEntity spawn : spawns) {
                assertEquals(Direction.N.byteValue(), spawn.getData());
                assertEquals(1.0D, spawn.getPosition().getX(), 0.0D);
                assertEquals(1.0D, spawn.getPosition().getY(), 0.0D);
                assertEquals(7.0D, spawn.getPosition().getZ(), 0.0D);
            }

            List<WrapperPlayServerEntityMetadata> metadataPackets =
                recorder.sentOfType(WrapperPlayServerEntityMetadata.class);
            assertEquals(2, metadataPackets.size());
            assertEquals(BlockFace.NORTH, valueAt(metadataPackets.get(0).getEntityMetadata(), 8));
            assertEquals(Integer.valueOf(5), valueAt(metadataPackets.get(0).getEntityMetadata(), 10));
            assertEquals(BlockFace.NORTH, valueAt(metadataPackets.get(1).getEntityMetadata(), 8));
            assertEquals(Integer.valueOf(3), valueAt(metadataPackets.get(1).getEntityMetadata(), 10));
            List<WrapperPlayServerMapData> mapPackets = recorder.sentOfType(WrapperPlayServerMapData.class);
            assertEquals(1, mapPackets.size());
            int virtualMapId = -spawns.get(1).getEntityId();
            assertEquals(virtualMapId, mapPackets.get(0).getMapId());
            ItemStack projectedMap = (ItemStack) valueAt(metadataPackets.get(1).getEntityMetadata(), 9);
            assertEquals(Integer.valueOf(virtualMapId),
                projectedMap.getComponent(ComponentTypes.MAP_ID).orElseThrow());
            assertTrue(recorder.sent().indexOf(spawns.get(1)) < recorder.sent().indexOf(mapPackets.get(0)));
            assertTrue(recorder.sent().indexOf(mapPackets.get(0)) < recorder.sent().indexOf(metadataPackets.get(1)));
        } finally {
            recorder.uninstall();
        }
    }

    private static boolean project(EntityRenderVisualProjector projector,
                                   Player observer,
                                   ILocalPortal localPortal,
                                   PortalFrame localFrame,
                                   PortalFrame remoteFrame,
                                   Frustum4D frustum,
                                   String typeKey,
                                   boolean filledMap) {
        UUID entityId = UUID.randomUUID();
        byte[] mapData = filledMap
            ? new ProjectedMapData(1, (byte) 0, true, false, mapPixels()).encode()
            : PacketBlobs.EMPTY;
        EntityVisual visual = EntityVisual.full(
            entityId,
            typeKey,
            0.0D, -3.0D, 0.0D,
            0.75D,
            0.0D, 1.0D, 0.0D,
            0.0F, -90.0F,
            0.0D, 0.0D, 0.0D,
            false,
            "", "", "",
            null, null,
            PacketBlobs.EMPTY, PacketBlobs.EMPTY, mapData,
            0);
        ProjectionEntityView view = entityView(entityId, filledMap);
        return projector.projectSnapshotVisual(
            observer,
            localPortal,
            0.0D, 0.0D, 0.0D,
            localFrame,
            remoteFrame,
            frustum,
            view,
            visual,
            false,
            false,
            0);
    }

    private static ProjectionEntityView entityView(UUID entityId, boolean filledMap) {
        List<EntityData<?>> metadata;
        if (filledMap) {
            ItemStack map = ItemStack.builder()
                .type(ItemTypes.FILLED_MAP)
                .component(ComponentTypes.MAP_ID, Integer.valueOf(1))
                .build();
            metadata = List.of(
                new EntityData<BlockFace>(8, EntityDataTypes.BLOCK_FACE, BlockFace.UP),
                new EntityData<ItemStack>(9, EntityDataTypes.ITEMSTACK, map),
                new EntityData<Integer>(10, EntityDataTypes.INT, Integer.valueOf(1)));
        } else {
            metadata = List.of(
                new EntityData<BlockFace>(8, EntityDataTypes.BLOCK_FACE, BlockFace.UP),
                new EntityData<Integer>(10, EntityDataTypes.INT, Integer.valueOf(1)));
        }
        return new ProjectionEntityView() {
            @Override
            public List<EntityVisual> getEntities(double centerX, double centerY, double centerZ, double range) {
                return List.of();
            }

            @Override
            public RemoteViewCache.RemoteProfile getProfile(UUID requestedId) {
                return null;
            }

            @Override
            public List<EntityData<?>> getMetadata(UUID requestedId) {
                return entityId.equals(requestedId) ? metadata : List.of();
            }

            @Override
            public List<Equipment> getEquipment(UUID requestedId) {
                return List.of();
            }

            @Override
            public int getStateVersion(UUID requestedId) {
                return 0;
            }
        };
    }

    private static ILocalPortal localPortal(Vector origin, PortalFrame frame) {
        UUID id = UUID.randomUUID();
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ("getOrigin".equals(name)) {
                return origin;
            }
            if ("getFrame".equals(name)) {
                return frame;
            }
            if ("getId".equals(name)) {
                return id;
            }
            if ("getName".equals(name)) {
                return "local";
            }
            if ("hashCode".equals(name)) {
                return Integer.valueOf(id.hashCode());
            }
            if ("equals".equals(name)) {
                return Boolean.valueOf(proxy == args[0]);
            }
            if ("toString".equals(name)) {
                return "local";
            }
            return null;
        };
        return (ILocalPortal) Proxy.newProxyInstance(
            ILocalPortal.class.getClassLoader(), new Class<?>[] {ILocalPortal.class}, handler);
    }

    private static Object valueAt(List<EntityData<?>> metadata, int index) {
        for (EntityData<?> data : metadata) {
            if (data.getIndex() == index) {
                return data.getValue();
            }
        }
        return null;
    }

    private static byte[] mapPixels() {
        byte[] pixels = new byte[ProjectedMapData.PIXEL_COUNT];
        for (int index = 0; index < pixels.length; index++) {
            pixels[index] = (byte) index;
        }
        return pixels;
    }

    private static final class TestStructure extends PortalStructure {
        @Override
        public AxisAlignedBB getArea() {
            return new AxisAlignedBB(0.0D, 3.0D, 0.0D, 3.0D, 5.0D, 5.0D);
        }

        @Override
        public Location getCenter() {
            return new Location(null, 1.5D, 1.5D, 5.0D);
        }

        @Override
        public List<AxisAlignedBB> getCachedApertureFaces(Direction face) {
            KList<AxisAlignedBB> faces = new KList<AxisAlignedBB>();
            faces.add(new AxisAlignedBB(0.0D, 3.0D, 0.0D, 3.0D, 5.0D, 5.0D));
            return faces;
        }
    }
}
