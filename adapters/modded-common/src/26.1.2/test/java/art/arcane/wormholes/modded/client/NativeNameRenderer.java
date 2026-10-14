package art.arcane.wormholes.modded.client;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;

abstract class NativeNameRenderer extends EntityRenderer<Entity, EntityRenderState> {
    private static final double NAME_DISTANCE = 4096.0D;
    private static final double SCORE_DISTANCE = 100.0D;

    NativeNameRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    EntityRenderState names(Entity entity) {
        EntityRenderState state = createRenderState();
        state.distanceToCameraSq = entityRenderDispatcher.distanceToSqr(entity);
        if (state.distanceToCameraSq < NAME_DISTANCE && shouldShowName(entity, state.distanceToCameraSq)) {
            state.nameTag = getNameTag(entity);
            state.nameTagAttachment = entity.getAttachments().getNullable(EntityAttachment.NAME_TAG, 0, entity.getYRot(0.5F));
        } else {
            state.nameTag = null;
        }
        state.scoreText = state.distanceToCameraSq < SCORE_DISTANCE ? entity.belowNameDisplay() : null;
        return state;
    }
}
