package art.arcane.wormholes.modded.client;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.mockito.stubbing.Answer;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

final class NativeCullingRenderer extends EntityRenderer<Entity, EntityRenderState> {
    private AABB extendedBounds;

    NativeCullingRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    static void bounds(Entity entity, AABB bounds) {
        when(entity.getInterpolatedBoundingBox(0.5F)).thenReturn(bounds);
    }

    static void answer(EntityRenderDispatcher dispatcher, Answer<Boolean> answer) {
        when(dispatcher.shouldRender(any(), any(), anyDouble(), anyDouble(), anyDouble(), anyFloat())).thenAnswer(answer);
    }

    static void verifyCameras(EntityRenderDispatcher dispatcher, Entity entity) {
        verify(dispatcher, times(2)).shouldRender(eq(entity), any(Frustum.class), eq(0.0), eq(0.0), eq(0.0), eq(0.5F));
    }

    static void reject(EntityRenderDispatcher dispatcher) {
        doReturn(false).when(dispatcher).shouldRender(any(), any(), anyDouble(), anyDouble(), anyDouble(), anyFloat());
    }

    boolean visible(Entity entity, Frustum frustum) {
        return shouldRender(entity, frustum, 0, 0, 0, 0.5F);
    }

    void extend(AABB bounds) {
        extendedBounds = bounds;
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
