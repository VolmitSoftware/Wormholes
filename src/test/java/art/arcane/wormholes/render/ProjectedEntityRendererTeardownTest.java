package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.UUID;

import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;

import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.player.Equipment;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSetPassengers;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.network.view.RemoteViewCache;
import art.arcane.wormholes.portal.IPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.render.view.ProjectionEntityView;
import art.arcane.wormholes.util.Direction;

public final class ProjectedEntityRendererTeardownTest {
    @Test
    public void discardDestroysSpoofedEntitiesWhileTheObserverCanStillReceiveThem() {
        ProjectedEntityPacketRecorder recorder = ProjectedEntityPacketRecorder.install();
        try {
            EntityRenderPacketChannel channel = new EntityRenderPacketChannel();
            EntityRenderPlayerIdentity identity = new EntityRenderPlayerIdentity(channel);
            EntityRenderSpoofRegistry registry = new EntityRenderSpoofRegistry(channel, identity);
            EntityRenderSpoofedEntity ghost = EntityRenderSpoofedEntity.create(false, false, true);
            UUID sourceId = UUID.randomUUID();
            registry.track(sourceId, ghost);
            ProjectedEntityRenderer renderer = new ProjectedEntityRenderer(channel, identity, registry);

            renderer.discard(ProjectedEntityPacketRecorder.player(true));

            List<WrapperPlayServerDestroyEntities> destroys = recorder.sentOfType(WrapperPlayServerDestroyEntities.class);
            assertEquals(1, destroys.size());
            assertArrayEquals(new int[] { ghost.fakeId }, destroys.get(0).getEntityIds());
            assertFalse(renderer.hasProjectedEntity(sourceId));
            assertEquals(0, renderer.getSpoofedCount());
        } finally {
            recorder.uninstall();
        }
    }

    @Test
    public void discardDropsEverythingWithoutPacketsWhenTheObserverIsOffline() {
        ProjectedEntityPacketRecorder recorder = ProjectedEntityPacketRecorder.install();
        try {
            EntityRenderPacketChannel channel = new EntityRenderPacketChannel();
            EntityRenderPlayerIdentity identity = new EntityRenderPlayerIdentity(channel);
            EntityRenderSpoofRegistry registry = new EntityRenderSpoofRegistry(channel, identity);
            UUID sourceId = UUID.randomUUID();
            registry.track(sourceId, EntityRenderSpoofedEntity.create(false, false, true));
            ProjectedEntityRenderer renderer = new ProjectedEntityRenderer(channel, identity, registry);

            renderer.discard(ProjectedEntityPacketRecorder.player(false));

            assertTrue(recorder.sent().isEmpty());
            assertEquals(0, recorder.batchLookups());
            assertFalse(renderer.hasProjectedEntity(sourceId));
            assertEquals(0, renderer.getSpoofedCount());
        } finally {
            recorder.uninstall();
        }
    }

    @Test
    public void snapshotPassDestroysCulledSpoofsBeforeEmittingRelationshipPackets() {
        boolean spoofing = Settings.ENTITY_SPOOFING;
        int maxSpoofed = Settings.MAX_SPOOFED_ENTITIES;
        ProjectedEntityPacketRecorder recorder = ProjectedEntityPacketRecorder.install();
        try {
            Settings.ENTITY_SPOOFING = true;
            Settings.MAX_SPOOFED_ENTITIES = 24;
            EntityRenderPacketChannel channel = new EntityRenderPacketChannel();
            EntityRenderPlayerIdentity identity = new EntityRenderPlayerIdentity(channel);
            EntityRenderSpoofRegistry registry = new EntityRenderSpoofRegistry(channel, identity);
            EntityRenderSpoofedEntity vehicle = EntityRenderSpoofedEntity.create(false, false, true);
            vehicle.lastPassengers = new int[] { vehicle.fakeId + 1 };
            registry.track(UUID.randomUUID(), vehicle);
            ProjectedEntityRenderer renderer = new ProjectedEntityRenderer(channel, identity, registry);
            PortalFrame frame = PortalFrame.canonical(Direction.N);
            Player observer = ProjectedEntityPacketRecorder.player(true);

            renderer.applySnapshot(observer, null, portalAt(0.0D, 64.0D, 0.0D), false, 0, emptyEntityView(),
                null, 32.0D, frame, frame);

            assertTrue(recorder.sentOfType(WrapperPlayServerSetPassengers.class).isEmpty());
            assertEquals(1, recorder.sentOfType(WrapperPlayServerDestroyEntities.class).size());
            assertEquals(0, renderer.getSpoofedCount());
        } finally {
            recorder.uninstall();
            Settings.ENTITY_SPOOFING = spoofing;
            Settings.MAX_SPOOFED_ENTITIES = maxSpoofed;
        }
    }

    private static ProjectionEntityView emptyEntityView() {
        return new ProjectionEntityView() {
            @Override
            public List<EntityVisual> getEntities(double centerX, double centerY, double centerZ, double range) {
                return List.of();
            }

            @Override
            public RemoteViewCache.RemoteProfile getProfile(UUID entityId) {
                return null;
            }

            @Override
            public List<EntityData<?>> getMetadata(UUID entityId) {
                return List.of();
            }

            @Override
            public List<Equipment> getEquipment(UUID entityId) {
                return List.of();
            }

            @Override
            public int getStateVersion(UUID entityId) {
                return 0;
            }
        };
    }

    private static IPortal portalAt(double x, double y, double z) {
        UUID id = UUID.randomUUID();
        InvocationHandler handler = (proxy, method, args) -> {
            String name = method.getName();
            if ("getOrigin".equals(name)) {
                return new Vector(x, y, z);
            }
            if ("getId".equals(name)) {
                return id;
            }
            if ("getName".equals(name)) {
                return "remote";
            }
            if ("toString".equals(name)) {
                return "remote";
            }
            if ("hashCode".equals(name)) {
                return Integer.valueOf(id.hashCode());
            }
            if ("equals".equals(name)) {
                return Boolean.valueOf(proxy == args[0]);
            }
            return null;
        };
        return (IPortal) Proxy.newProxyInstance(IPortal.class.getClassLoader(), new Class<?>[] { IPortal.class }, handler);
    }
}
