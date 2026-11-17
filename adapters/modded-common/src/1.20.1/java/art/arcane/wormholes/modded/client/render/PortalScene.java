package art.arcane.wormholes.modded.client.render;

import art.arcane.optics.aperture.ApertureDescriptor;
import art.arcane.optics.stream.EnvironmentState;
import art.arcane.optics.stream.ViewStreamMessage;
import it.unimi.dsi.fastutil.longs.LongIterable;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.BlockAndTintGetter;

import java.util.function.ObjLongConsumer;

public interface PortalScene {
    ApertureDescriptor geometry();

    default ViewStreamMessage.PortalAffine affine() {
        return null;
    }

    default ClientLevel sourceLevel() {
        return null;
    }

    default boolean sourceExpired() {
        return false;
    }

    default boolean fullWorld() {
        return false;
    }

    default boolean empty(long sectionKey) {
        return false;
    }

    default EnvironmentState environment() {
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

    default boolean refreshing(long sectionKey) {
        return false;
    }

    /**
     * Draws the scene's entities and block entities into the view being rendered. 1.20.1 has no render-state extraction,
     * so the scene renders its features directly, inside whatever level-query context each one needs.
     */
    default void renderFeatures(PortalFeatureRenderer features) {
    }

    interface MeshIdentity {
        int contextHash();

        boolean sameContext(MeshIdentity other);

        boolean same(MeshIdentity other);

        void references(ObjLongConsumer<Object> consumer);
    }
}
