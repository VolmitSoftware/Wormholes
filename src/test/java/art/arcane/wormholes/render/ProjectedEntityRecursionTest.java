package art.arcane.wormholes.render;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.bukkit.util.BoundingBox;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerDestroyEntities;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnEntity;

import art.arcane.wormholes.Settings;
import art.arcane.wormholes.network.view.EntityVisual;
import art.arcane.wormholes.render.view.ProjectionEntityView;
import art.arcane.wormholes.render.view.ProjectionWorldView;
import art.arcane.wormholes.portal.ILocalPortal;
import art.arcane.wormholes.portal.PortalFrame;
import art.arcane.wormholes.portal.PortalStructure;
import art.arcane.wormholes.util.AxisAlignedBB;
import art.arcane.wormholes.util.Direction;

final class ProjectedEntityRecursionTest {
    @Test
    void projectsNestedPositionVelocityAndDespawnsClosedPath() {
        withSettings(() -> {
            Fixture fixture = new Fixture();
            Map<String, Object> gate = fixture.gate(1.5D, 8.0D);
            fixture.entityState.put("velocity", new Vector(0.125D, 0.25D, 0.5D));
            fixture.render();

            List<WrapperPlayServerSpawnEntity> spawns = fixture.recorder.sentOfType(WrapperPlayServerSpawnEntity.class);
            assertEquals(1, spawns.size());
            assertEquals(1.5D, spawns.getFirst().getPosition().getX(), 1.0E-9D);
            assertEquals(1.0D, spawns.getFirst().getPosition().getY(), 1.0E-9D);
            assertEquals(11.0D, spawns.getFirst().getPosition().getZ(), 1.0E-9D);
            assertEquals(0.5D, spawns.getFirst().getVelocity().orElseThrow().getZ(), 1.0E-9D);
            assertTrue(fixture.renderer.hasProjectedEntity(fixture.entityId));

            gate.put("open", Boolean.FALSE);
            fixture.render();
            assertEquals(0, fixture.renderer.getSpoofedCount());
            assertFalse(fixture.renderer.hasProjectedEntity(fixture.entityId));
            assertEquals(1, fixture.recorder.sentOfType(WrapperPlayServerDestroyEntities.class).size());
            fixture.close();
        });
    }

    @Test
    void keepsDistinctInstancesOfTheSameEntityAndSharesOneCap() {
        withSettings(() -> {
            Fixture fixture = new Fixture();
            fixture.gate(0.0D, 8.0D);
            fixture.gate(3.0D, 8.0D);
            fixture.render();
            List<WrapperPlayServerSpawnEntity> spawns = fixture.recorder.sentOfType(WrapperPlayServerSpawnEntity.class);
            assertEquals(2, spawns.size());
            assertNotEquals(spawns.get(0).getEntityId(), spawns.get(1).getEntityId());
            assertEquals(2, fixture.renderer.getSpoofedCount());
            assertEquals(1, fixture.renderer.getProjectedEntityIds().size());

            Settings.MAX_SPOOFED_ENTITIES = 1;
            fixture.render();
            assertEquals(1, fixture.renderer.getSpoofedCount());
            fixture.close();
        });
    }

    @Test
    void rejectsEntitiesOutsideTheNestedAperture() {
        withSettings(() -> {
            Fixture fixture = new Fixture();
            fixture.gate(1.5D, 8.0D);
            fixture.entityState.put("location", new Location(fixture.finalWorld, 5.0D, 1.0D, 103.0D));
            fixture.entityState.put("boundingBox", new BoundingBox(4.5D, 1.0D, 102.5D, 5.5D, 2.0D, 103.5D));
            fixture.render();
            assertEquals(0, fixture.renderer.getSpoofedCount());
            fixture.close();
        });
    }

    @Test
    void retainsTheAnchorWhenOnlyTheBodyEdgeIsVisible() {
        withSettings(() -> {
            Fixture fixture = new Fixture();
            fixture.gate(1.5D, 8.0D);
            fixture.entityState.put("location", new Location(fixture.finalWorld, 2.9D, 1.0D, 103.0D));
            fixture.entityState.put("boundingBox", new BoundingBox(2.4D, 1.0D, 102.5D, 3.4D, 2.0D, 103.5D));
            fixture.render();
            assertEquals(1, fixture.renderer.getSpoofedCount());
            assertEquals(2.9D, fixture.recorder.sentOfType(WrapperPlayServerSpawnEntity.class)
                .getFirst().getPosition().getX(), 1.0E-9D);
            fixture.close();
        });
    }

    @Test
    void hidesImmediateEntitiesReplacedByANestedView() {
        withSettings(() -> {
            Fixture fixture = new Fixture();
            fixture.gate(1.5D, 8.0D);
            Map<String, Object> behindState = RenderTestSupport.entityState(UUID.randomUUID(), fixture.middleWorld,
                EntityType.ZOMBIE, 1.5D, 1.0D, 11.0D, 1.0D);
            fixture.middleEntities.add(RenderTestSupport.entity(LivingEntity.class, behindState));
            fixture.render();
            assertEquals(1, fixture.renderer.getSpoofedCount());
            assertFalse(fixture.renderer.hasProjectedEntity((UUID) behindState.get("uniqueId")));
            fixture.close();
        });
    }

    @Test
    void selectsNearestPortalWhenNestedAperturesOverlap() {
        withSettings(() -> {
            Fixture fixture = new Fixture();
            fixture.gate(1.5D, 8.0D);
            fixture.gate(1.5D, 9.0D);
            fixture.render();
            assertEquals(1, fixture.renderer.getSpoofedCount());
            assertEquals(11.0D, fixture.recorder.sentOfType(WrapperPlayServerSpawnEntity.class)
                .getFirst().getPosition().getZ(), 1.0E-9D);
            fixture.close();
        });
    }

    @Test
    void composesRotatedPositionAndVelocity() {
        withSettings(() -> {
            Fixture fixture = new Fixture();
            fixture.destinationState.put("frame", PortalFrame.canonical(Direction.E));
            fixture.gate(1.5D, 8.0D);
            fixture.entityState.put("location", new Location(fixture.finalWorld, -1.5D, 1.0D, 100.0D));
            fixture.entityState.put("boundingBox", new BoundingBox(-2.0D, 1.0D, 99.5D, -1.0D, 2.0D, 100.5D));
            fixture.entityState.put("velocity", new Vector(-0.5D, 0.25D, 0.125D));
            fixture.render();
            WrapperPlayServerSpawnEntity spawn = fixture.recorder.sentOfType(WrapperPlayServerSpawnEntity.class).getFirst();
            assertEquals(1.5D, spawn.getPosition().getX(), 1.0E-9D);
            assertEquals(11.0D, spawn.getPosition().getZ(), 1.0E-9D);
            assertEquals(0.125D, spawn.getVelocity().orElseThrow().getX(), 1.0E-9D);
            assertEquals(0.25D, spawn.getVelocity().orElseThrow().getY(), 1.0E-9D);
            assertEquals(0.5D, spawn.getVelocity().orElseThrow().getZ(), 1.0E-9D);
            fixture.close();
        });
    }

    @Test
    void snapshotPathProjectsEntitiesWithoutLiveCapture() {
        withSettings(() -> {
            Fixture fixture = new Fixture();
            fixture.gate(1.5D, 8.0D);
            ProjectionWorldView snapshot = mock(ProjectionWorldView.class,
                Mockito.withSettings().extraInterfaces(ProjectionEntityView.class));
            ProjectionEntityView entities = (ProjectionEntityView) snapshot;
            EntityVisual visual = EntityVisual.full(fixture.entityId, "minecraft:zombie",
                1.5D, 1.0D, 103.0D, 1.0D, 0.0D, 0.0D, 1.0D, 0.0F, 0.0F,
                0.125D, 0.25D, 0.5D, true, "", "", "", null, null, null, null, 0);
            when(entities.getEntities(anyDouble(), anyDouble(), anyDouble(), anyDouble())).thenReturn(List.of(visual));
            when(entities.isVisibleTo(any(), any())).thenReturn(true);
            fixture.finalEntities.clear();
            fixture.renderer.prepareRecursiveProjection(new EntityProjectionPath.Root(fixture.local, fixture.remote,
                fixture.frame, fixture.frame, false, 0, fixture.eye, fixture.frustum), fixture.recursive);
            fixture.renderer.applyRecursive(fixture.observer, new ProjectedEntityRenderer.RecursiveRender(
                fixture.local, fixture.frame, fixture.frustum, 32.0D, true, ignored -> snapshot, fixture.occlusion));
            assertEquals(1, fixture.renderer.getSpoofedCount());
            assertEquals(11.0D, fixture.recorder.sentOfType(WrapperPlayServerSpawnEntity.class)
                .getFirst().getPosition().getZ(), 1.0E-9D);
            fixture.close();
        });
    }

    @Test
    void recursionDepthStopsNestedEntityCapture() {
        withSettings(() -> {
            Fixture fixture = new Fixture();
            fixture.gate(1.5D, 8.0D);
            int depth = Settings.PROJECTION_RECURSIVE_PORTAL_DEPTH;
            try {
                Settings.PROJECTION_RECURSIVE_PORTAL_DEPTH = 0;
                fixture.render();
                assertEquals(0, fixture.renderer.getSpoofedCount());
            } finally {
                Settings.PROJECTION_RECURSIVE_PORTAL_DEPTH = depth;
                fixture.close();
            }
        });
    }

    private static void withSettings(Runnable action) {
        boolean spoofing = Settings.ENTITY_SPOOFING;
        int maximum = Settings.MAX_SPOOFED_ENTITIES;
        double padding = Settings.PROJECTION_APERTURE_PADDING_BLOCKS;
        try {
            Settings.ENTITY_SPOOFING = true;
            Settings.MAX_SPOOFED_ENTITIES = 24;
            Settings.PROJECTION_APERTURE_PADDING_BLOCKS = 0.0D;
            RenderTestSupport.withBukkitServer(action);
        } finally {
            Settings.ENTITY_SPOOFING = spoofing;
            Settings.MAX_SPOOFED_ENTITIES = maximum;
            Settings.PROJECTION_APERTURE_PADDING_BLOCKS = padding;
        }
    }

    private static final class Fixture {
        private final List<Entity> middleEntities = new ArrayList<Entity>();
        private final List<Entity> finalEntities = new ArrayList<Entity>();
        private final World localWorld = RenderTestSupport.world("recursive-local", List.of());
        private final World middleWorld = RenderTestSupport.world("recursive-middle", middleEntities);
        private final World finalWorld = RenderTestSupport.world("recursive-final", finalEntities);
        private final PortalFrame frame = PortalFrame.canonical(Direction.N);
        private final ILocalPortal local = RenderTestSupport.portal(localWorld, new Vector(1.5D, 1.5D, 5.0D), frame);
        private final ILocalPortal remote = RenderTestSupport.portal(middleWorld, new Vector(1.5D, 1.5D, 5.0D), frame);
        private final Map<String, Object> destinationState = RenderTestSupport.portalState(finalWorld,
            new Vector(1.5D, 1.5D, 100.0D), frame);
        private final ILocalPortal destination = RenderTestSupport.portal(destinationState);
        private final List<ILocalPortal> portals = new ArrayList<ILocalPortal>();
        private final ProjectorRecursivePortals recursive = new ProjectorRecursivePortals(() -> portals);
        private final Location eye = new Location(localWorld, 1.5D, 1.5D, 0.0D);
        private final Frustum4D frustum = new Frustum4D(eye, new RenderTestSupport.ApertureStructure(), 32.0D, 32.0D);
        private final ProjectedEntityPacketRecorder recorder = ProjectedEntityPacketRecorder.install();
        private final ProjectedEntityRenderer renderer = new ProjectedEntityRenderer();
        private final Player observer = ProjectedEntityPacketRecorder.player(true);
        private final UUID entityId = UUID.randomUUID();
        private final Map<String, Object> entityState = RenderTestSupport.entityState(entityId, finalWorld,
            EntityType.ZOMBIE, 1.5D, 1.0D, 103.0D, 1.0D);
        private final ProjectedEntityOcclusion occlusion = new ProjectedEntityOcclusion();

        private Fixture() {
            finalEntities.add(RenderTestSupport.entity(LivingEntity.class, entityState));
        }

        private Map<String, Object> gate(double x, double z) {
            Map<String, Object> state = RenderTestSupport.portalState(middleWorld, new Vector(x, 1.5D, z), frame);
            state.put("structure", new Aperture(new AxisAlignedBB(x - 0.7D, x + 0.7D, 0.0D, 3.0D, z, z)));
            state.put("view", new AxisAlignedBB(-32.0D, 32.0D, -32.0D, 32.0D, z, z + 32.0D));
            state.put("supportsProjections", Boolean.TRUE);
            state.put("projecting", Boolean.TRUE);
            state.put("open", Boolean.TRUE);
            state.put("tunnel", RenderTestSupport.tunnel(destination));
            portals.add(RenderTestSupport.portal(state));
            return state;
        }

        private void render() {
            renderer.prepareRecursiveProjection(new EntityProjectionPath.Root(local, remote, frame, frame,
                false, 0, eye, frustum), recursive);
            renderer.apply(observer, local, remote, frustum, 32.0D, frame, frame, 0, occlusion);
            renderer.applyRecursive(observer, new ProjectedEntityRenderer.RecursiveRender(local, frame, frustum,
                32.0D, false, ignored -> null, occlusion));
        }

        private void close() {
            renderer.close(observer);
            recorder.uninstall();
        }
    }

    private static final class Aperture extends PortalStructure {
        private final AxisAlignedBB area;

        private Aperture(AxisAlignedBB area) {
            this.area = area;
        }

        @Override
        public AxisAlignedBB getArea() {
            return area;
        }

        @Override
        public boolean isFullCuboid() {
            return true;
        }

        @Override
        public List<AxisAlignedBB> getCachedApertureFaces(Direction face) {
            return List.of(area);
        }
    }
}
