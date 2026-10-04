package art.arcane.wormholes.modded.client.render;

import art.arcane.wormholes.render.client.ClientPortalGeometry;
import art.arcane.wormholes.network.client.ClientViewEnvironment;
import it.unimi.dsi.fastutil.longs.LongIterable;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.client.renderer.entity.state.EntityRenderState;

import java.util.List;

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

    LongIterable sectionKeys();

    long revision(long sectionKey);

    default List<BlockEntityRenderState> blockEntities() {
        return List.of();
    }

    default List<EntityRenderState> entities() {
        return List.of();
    }
}
