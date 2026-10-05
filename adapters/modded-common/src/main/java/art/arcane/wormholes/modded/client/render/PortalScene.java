package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import it.unimi.dsi.fastutil.longs.LongIterable;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.state.level.CameraRenderState;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.ObjLongConsumer;

public interface PortalScene {
    ClientPortalGeometry geometry();

    default boolean fullWorld() {
        return false;
    }

    default boolean empty(long sectionKey) {
        return false;
    }

    default ClientViewEnvironment environment() {
        return null;
    }

    BlockAndTintGetter world(long sectionKey);

    default MeshIdentity meshContext() {
        return null;
    }

    default MeshIdentity meshIdentity(long sectionKey) {
        return null;
    }

    default boolean matchesMeshIdentity(long sectionKey, MeshIdentity retained) {
        return retained != null && retained.same(meshIdentity(sectionKey));
    }

    LongIterable sectionKeys();

    long revision(long sectionKey);

    default List<BlockEntityRenderState> blockEntities() {
        return List.of();
    }

    default List<EntityRenderState> entities() {
        return List.of();
    }

    default Predicate<EntityRenderState> entityVisibility(CameraRenderState camera, Frustum frustum) {
        return state -> true;
    }
    interface MeshIdentity {
        int contextHash();

        boolean sameContext(MeshIdentity other);

        boolean same(MeshIdentity other);

        void references(ObjLongConsumer<Object> consumer);
    }
}
