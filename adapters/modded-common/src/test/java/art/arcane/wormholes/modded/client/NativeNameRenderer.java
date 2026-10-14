package art.arcane.wormholes.modded.client;

import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.world.entity.Entity;

abstract class NativeNameRenderer extends EntityRenderer<Entity, EntityRenderState> {
    NativeNameRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    EntityRenderState names(Entity entity) {
        EntityRenderState state = createRenderState();
        extractNameTags(entity, state, 0.5F);
        return state;
    }
}
