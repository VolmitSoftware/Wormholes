package art.arcane.wormholes.clientgametest;

import art.arcane.wormholes.modded.client.ClientMeshEntities;
import art.arcane.wormholes.modded.client.ClientMeshSections;
import art.arcane.wormholes.modded.client.ClientPalette;

import art.arcane.optics.math.BlockBox;
import art.arcane.optics.frame.OpticTransform;
import art.arcane.optics.stream.Brick;
import art.arcane.optics.stream.SectionBiomes;
import art.arcane.optics.stream.ViewStreamMessage;
import art.arcane.optics.stream.ViewStreamProtocolException;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.LightLayer;

import java.util.Arrays;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;

public final class ClientMeshLightingAssertions {
    private ClientMeshLightingAssertions() {
    }

    public static void verify(ClientLevel level) {
        BlockPos position = new BlockPos(8, 8, 8);
        int block = level.getLightEngine().getLayerListener(LightLayer.BLOCK).getLightValue(position);
        int sky = level.getLightEngine().getLayerListener(LightLayer.SKY).getLightValue(position);
        int raw = level.getLightEngine().getRawBrightness(position, 4);
        ClientMeshEntities outer = scene(level, 2, 15);
        ClientMeshEntities inner = scene(level, 7, 9);
        inDestinationWorld(outer, () -> {
            require(level, position, 2, 15, 11);
            try {
                inDestinationWorld(inner, () -> {
                    require(level, position, 7, 9, 7);
                    throw new ScopedFailure();
                });
            } catch (ScopedFailure expected) {
                require(level, position, 2, 15, 11);
            }
        });
        require(level, position, block, sky, raw);
        if (ClientMeshEntities.active() != null) {
            throw new AssertionError("Scene lighting scope leaked into the main world");
        }
    }

    private static void inDestinationWorld(ClientMeshEntities scene, Runnable body) {
        try {
            Method scope = ClientMeshEntities.class.getDeclaredMethod("inDestinationWorld", Runnable.class);
            scope.setAccessible(true);
            scope.invoke(scene, body);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof ScopedFailure scoped) {
                throw scoped;
            }
            throw new AssertionError("Destination lighting scope failed", failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Destination lighting scope is unavailable", failure);
        }
    }

    private static ClientMeshEntities scene(ClientLevel level, int block, int sky) {
        ClientMeshSections sections = new ClientMeshSections(new ClientPalette(BuiltInRegistries.BLOCK), 1_048_576);
        byte[] blockLight = new byte[2048];
        byte[] skyLight = new byte[2048];
        Arrays.fill(blockLight, (byte) (block | block << 4));
        Arrays.fill(skyLight, (byte) (sky | sky << 4));
        try {
            sections.begin(7, 1, new BlockBox(0, 0, 0, 16, 16, 16), 1);
            sections.put(new ViewStreamMessage.MeshSection(7, 1, 0, 0, 0, 1, 0,
                Brick.single(0, 0).withLight(blockLight, skyLight), SectionBiomes.NONE));
        } catch (ViewStreamProtocolException failure) {
            throw new AssertionError("Lighting fixture section was rejected", failure);
        }
        ClientMeshEntities scene = new ClientMeshEntities(sections.view(7), level);
        try {
            Method synchronize = ClientMeshEntities.class.getDeclaredMethod("synchronize", OpticTransform.class);
            synchronize.setAccessible(true);
            synchronize.invoke(scene, OpticTransform.IDENTITY);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Lighting fixture transform is unavailable", failure);
        }
        return scene;
    }

    private static void require(ClientLevel level, BlockPos position, int block, int sky, int raw) {
        if (level.getBrightness(LightLayer.BLOCK, position) != block
            || level.getBrightness(LightLayer.SKY, position) != sky || level.getRawBrightness(position, 4) != raw) {
            throw new AssertionError("Native world did not use the active scene's block, sky and raw light");
        }
    }

    private static final class ScopedFailure extends RuntimeException {
    }
}
