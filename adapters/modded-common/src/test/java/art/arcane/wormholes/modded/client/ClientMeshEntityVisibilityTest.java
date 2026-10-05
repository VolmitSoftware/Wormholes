package art.arcane.wormholes.modded.client;

import art.arcane.wormholes.geometry.GeometryVector;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import art.arcane.wormholes.util.Direction;
import net.minecraft.client.Minecraft;
import net.minecraft.SharedConstants;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.junit.Test;
import org.junit.BeforeClass;
import org.mockito.MockedStatic;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

public class ClientMeshEntityVisibilityTest {
    @BeforeClass
    public static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    public void destinationBoundsFollowSidewaysAndMirroredPortalTransforms() {
        Frustum display = new Frustum(new Matrix4f(), new Matrix4f());
        ClientViewEnvironment.Transform transform = new ClientViewEnvironment.Transform(Direction.U, Direction.W, Direction.S,
            new GeometryVector(10, -20, 30));
        Frustum destination = new ClientMeshEntities.DestinationFrustum(display, transform);
        assertTrue(destination.isVisible(new AABB(19.75, 9.75, -30.25, 20.25, 10.25, -29.75)));
        assertFalse(destination.isVisible(new AABB(19.75, 3.75, -30.25, 20.25, 4.25, -29.75)));
        assertTrue(destination.isVisible(new AABB(Double.NEGATIVE_INFINITY, Double.NEGATIVE_INFINITY,
            Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY)));
        assertTrue(destination.isVisible(new AABB(Double.NaN, 0, 0, Double.NaN, 1, 1)));
    }

    @Test
    public void vanillaRendererRetainsExtendedBoundsAndLeashesAcrossTheFrustum() {
        EntityRenderDispatcher dispatcher = mock(EntityRenderDispatcher.class);
        EntityRendererProvider.Context context = mock(EntityRendererProvider.Context.class);
        when(context.getEntityRenderDispatcher()).thenReturn(dispatcher);
        CullingRenderer renderer = new CullingRenderer(context);
        Frustum display = new Frustum(new Matrix4f(), new Matrix4f());
        Frustum destination = new ClientMeshEntities.DestinationFrustum(display, ClientViewEnvironment.Transform.IDENTITY);
        Entity source = mock(Entity.class, withSettings().extraInterfaces(Leashable.class));
        when(source.shouldRender(0, 0, 0)).thenReturn(true);
        when(source.getInterpolatedBoundingBox(0.5F)).thenReturn(new AABB(10, 0, 0, 11, 1, 1));
        assertFalse(renderer.shouldRender(source, destination, 0, 0, 0, 0.5F));
        Entity holder = mock(Entity.class);
        when(((Leashable) source).getLeashHolder()).thenReturn(holder);
        doReturn(renderer).when(dispatcher).getRenderer(holder);
        when(holder.getInterpolatedBoundingBox(0.5F)).thenReturn(new AABB(-11, 0, 0, -10, 1, 1));
        assertTrue(renderer.shouldRender(source, destination, 0, 0, 0, 0.5F));
        renderer.extendedBounds = new AABB(-1, -1, -1, 11, 1, 1);
        when(((Leashable) source).getLeashHolder()).thenReturn(null);
        assertTrue(renderer.shouldRender(source, destination, 0, 0, 0, 0.5F));
    }

    @Test
    public void eachRenderCameraUsesItsOwnFrustumWithoutReducingProjectionDistance() {
        ClientLevel level = mock(ClientLevel.class);
        ClientMeshEntities features = new ClientMeshEntities(mock(ClientMeshSections.View.class), level);
        Entity source = mock(Entity.class);
        UUID id = UUID.randomUUID();
        when(source.getUUID()).thenReturn(id);
        when(level.entitiesForRendering()).thenReturn(List.of(source));
        EntityRenderDispatcher dispatcher = mock(EntityRenderDispatcher.class);
        EntityRenderState state = new EntityRenderState();
        when(dispatcher.extractEntity(source, 0.5F)).thenReturn(state);
        AABB bounds = new AABB(500, 20, 10, 501, 21, 11);
        when(dispatcher.shouldRender(any(), any(), anyDouble(), anyDouble(), anyDouble(), anyFloat()))
            .thenAnswer(call -> ((Frustum) call.getArgument(1)).isVisible(bounds));
        Minecraft minecraft = mock(Minecraft.class);
        when(minecraft.getEntityRenderDispatcher()).thenReturn(dispatcher);
        List<EntityRenderState> states = new ArrayList<>();
        try (MockedStatic<Minecraft> runtime = mockStatic(Minecraft.class);
             MockedStatic<ClientMeshEntities> ownership = mockStatic(ClientMeshEntities.class, CALLS_REAL_METHODS)) {
            runtime.when(Minecraft::getInstance).thenReturn(minecraft);
            ownership.when(() -> ClientMeshEntities.hiddenFromWorld(source)).thenReturn(false);
            features.extractLocalEntities(Set.of(id), dispatcher, 0.5F, states);
            Frustum main = new Frustum(new Matrix4f(), new Matrix4f());
            CameraRenderState camera = new CameraRenderState();
            camera.pos = Vec3.ZERO;
            assertFalse(features.entityVisibility(camera, main, ClientViewEnvironment.Transform.IDENTITY).test(state));
            Frustum shadow = new Frustum(new Matrix4f(), new Matrix4f());
            shadow.prepare(500, 20, 10);
            assertTrue(features.entityVisibility(camera, shadow, ClientViewEnvironment.Transform.IDENTITY).test(state));
            verify(dispatcher, times(2)).shouldRender(eq(source), any(Frustum.class), eq(0.0), eq(0.0), eq(0.0), eq(0.5F));
            Predicate<EntityRenderState> visible = features.entityVisibility(camera, main, ClientViewEnvironment.Transform.IDENTITY);
            doReturn(false).when(dispatcher).shouldRender(any(), any(), anyDouble(), anyDouble(), anyDouble(), anyFloat());
            assertTrue(visible.test(state));
            state.nameTag = Component.literal("Visible label");
            assertTrue(visible.test(state));
            state.nameTag = null;
            state.scoreText = Component.literal("Visible score");
            assertTrue(visible.test(state));
            state.scoreText = null;
            state.outlineColor = 0xFFFFFF;
            assertTrue(visible.test(state));
            assertTrue(visible.test(new EntityRenderState()));
        }
    }

    private static final class CullingRenderer extends EntityRenderer<Entity, EntityRenderState> {
        private AABB extendedBounds;

        private CullingRenderer(EntityRendererProvider.Context context) {
            super(context);
        }

        @Override
        public EntityRenderState createRenderState() {
            return new EntityRenderState();
        }

        @Override
        protected AABB getBoundingBoxForCulling(Entity entity, float partialTick) {
            return extendedBounds == null ? super.getBoundingBoxForCulling(entity, partialTick) : extendedBounds;
        }
    }
}
