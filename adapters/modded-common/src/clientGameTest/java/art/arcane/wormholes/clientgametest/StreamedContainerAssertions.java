package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.client.render.ClientPortalRenderer;
import art.arcane.wormholes.modded.client.render.PortalScene;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.lang.reflect.Field;
import java.util.List;

final class StreamedContainerAssertions {
    private static final List<Block> BLOCKS = List.of(Blocks.CHEST, Blocks.ENDER_CHEST,
        BuiltInRegistries.BLOCK.getOptional(Identifier.withDefaultNamespace("purple_shulker_box")).orElseThrow());

    private StreamedContainerAssertions() {
    }

    static boolean captured(int key, int offset, int z) {
        for (int index = 0; index < BLOCKS.size(); index++) {
            BlockPos position = new BlockPos(offset + 7 + index, 191, z);
            BlockState state = NativeClientViewAssertions.state(key, position);
            if (state == null || !state.is(BLOCKS.get(index))) {
                return false;
            }
        }
        return true;
    }

    static void rendered(int key, int offset, int z) {
        PortalScene scene = scene(key);
        for (int index = 0; index < BLOCKS.size(); index++) {
            BlockPos position = new BlockPos(offset + 7 + index, 191, z);
            boolean found = false;
            for (BlockEntityRenderState state : scene.blockEntities()) {
                if (position.equals(state.blockPos) && state.blockEntityType.isValid(BLOCKS.get(index).defaultBlockState())) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                throw new AssertionError("Streamed container has no rendered block entity at " + position
                    + " for portal " + key + "; states=" + scene.blockEntities());
            }
        }
    }

    static String describe(int key, int offset, int z) {
        StringBuilder result = new StringBuilder("[");
        for (int index = 0; index < BLOCKS.size(); index++) {
            if (index != 0) {
                result.append(", ");
            }
            BlockPos position = new BlockPos(offset + 7 + index, 191, z);
            result.append(position).append("=").append(NativeClientViewAssertions.state(key, position));
        }
        return result.append("]").toString();
    }

    private static PortalScene scene(int key) {
        try {
            Field portalsField = ClientPortalRenderer.class.getDeclaredField("portals");
            portalsField.setAccessible(true);
            Int2ObjectMap<?> portals = (Int2ObjectMap<?>) portalsField.get(ClientPortalRenderer.instance());
            Object portal = portals.get(key);
            if (portal == null) {
                throw new AssertionError("Streamed container portal is not registered: " + key);
            }
            Field sceneField = portal.getClass().getDeclaredField("scene");
            sceneField.setAccessible(true);
            return (PortalScene) sceneField.get(portal);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Streamed container render state is unavailable", failure);
        }
    }
}
