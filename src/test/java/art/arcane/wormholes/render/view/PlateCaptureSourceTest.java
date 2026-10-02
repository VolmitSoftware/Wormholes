package art.arcane.wormholes.render.view;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.List;

import org.bukkit.Chunk;
import org.bukkit.ChunkSnapshot;
import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.SignSide;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.render.FidelitySettings;
import art.arcane.wormholes.render.ProjectionCellKey;
import art.arcane.wormholes.render.plate.PlateCaptureJob;

final class PlateCaptureSourceTest {
    @Test
    void aBusyChunkCapsItsBlockEntitiesAndReportsTheCaptureIncomplete() {
        boolean blockEntities = FidelitySettings.blockEntities;
        List<String> types = FidelitySettings.blockEntityTypes;
        FidelitySettings.blockEntities = true;
        FidelitySettings.blockEntityTypes = List.of("minecraft:sign");
        try {
            int count = PlateCaptureJob.MAX_BLOCK_ENTITIES_PER_CHUNK + 16;
            BlockState[] signs = new BlockState[count];
            for (int i = 0; i < count; i++) {
                signs[i] = sign(i & 15, 64 + (i >> 4), 3);
            }

            PlateCaptureSource.CapturedChunk busy = new PlateCaptureSource(PlateCaptureSource.Options.column(true)).capture(world(chunk(signs)), 0, 0);

            assertEquals(PlateCaptureJob.MAX_BLOCK_ENTITIES_PER_CHUNK, busy.blockEntities().size(), "one chunk never serialises more than the per-chunk cap");
            assertFalse(busy.blockEntitiesComplete(), "a capped chunk leaves its missing block entities to live sampling");

            BlockState[] few = new BlockState[] {sign(1, 64, 3), sign(2, 64, 3)};
            PlateCaptureSource.CapturedChunk quiet = new PlateCaptureSource(PlateCaptureSource.Options.column(true)).capture(world(chunk(few)), 0, 0);

            assertEquals(2, quiet.blockEntities().size());
            assertTrue(quiet.blockEntitiesComplete());
        } finally {
            FidelitySettings.blockEntities = blockEntities;
            FidelitySettings.blockEntityTypes = types;
        }
    }

    @Test
    void sectionCaptureFiltersOtherHeightsBeforeApplyingBlockEntityCap() {
        boolean blockEntities = FidelitySettings.blockEntities;
        List<String> types = FidelitySettings.blockEntityTypes;
        FidelitySettings.blockEntities = true;
        FidelitySettings.blockEntityTypes = List.of("minecraft:sign");
        try {
            BlockState[] signs = new BlockState[PlateCaptureJob.MAX_BLOCK_ENTITIES_PER_CHUNK + 2];
            for (int i = 0; i < signs.length - 1; i++) {
                signs[i] = sign(i & 15, 64 + (i >> 4), 3);
            }
            signs[signs.length - 1] = sign(2, -17, 3);
            PlateCaptureSource source = new PlateCaptureSource(new PlateCaptureSource.Options(true, -32, -17, false));
            PlateCaptureSource.CapturedChunk captured = source.capture(world(chunk(signs)), 0, 0);
            assertEquals(1, captured.blockEntities().size());
            assertTrue(captured.blockEntities().containsKey(ProjectionCellKey.pack(2, -17, 3)));
            assertTrue(captured.blockEntitiesComplete());
        } finally {
            FidelitySettings.blockEntities = blockEntities;
            FidelitySettings.blockEntityTypes = types;
        }
    }

    private static World world(Chunk chunk) {
        return (World) Proxy.newProxyInstance(World.class.getClassLoader(), new Class<?>[] {World.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getChunkAt" -> chunk;
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == args[0]);
                case "toString" -> "world";
                default -> defaultValue(method.getReturnType());
            });
    }

    private static Chunk chunk(BlockState[] states) {
        ChunkSnapshot snapshot = (ChunkSnapshot) Proxy.newProxyInstance(ChunkSnapshot.class.getClassLoader(),
            new Class<?>[] {ChunkSnapshot.class}, (proxy, method, args) -> defaultValue(method.getReturnType()));
        return (Chunk) Proxy.newProxyInstance(Chunk.class.getClassLoader(), new Class<?>[] {Chunk.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getTileEntities" -> states;
                case "getChunkSnapshot" -> snapshot;
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == args[0]);
                case "toString" -> "chunk";
                default -> defaultValue(method.getReturnType());
            });
    }

    private static Sign sign(int x, int y, int z) {
        String[] lines = new String[] {"x", "", "", ""};
        SignSide side = (SignSide) Proxy.newProxyInstance(SignSide.class.getClassLoader(), new Class<?>[] {SignSide.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getLines" -> lines;
                case "getLine" -> lines[((Integer) args[0]).intValue()];
                case "getColor" -> DyeColor.BLACK;
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == args[0]);
                default -> defaultValue(method.getReturnType());
            });
        return (Sign) Proxy.newProxyInstance(Sign.class.getClassLoader(), new Class<?>[] {Sign.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getType" -> Material.OAK_SIGN;
                case "getSide" -> side;
                case "getLines" -> lines;
                case "getColor" -> DyeColor.BLACK;
                case "getX" -> Integer.valueOf(x);
                case "getY" -> Integer.valueOf(y);
                case "getZ" -> Integer.valueOf(z);
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == args[0]);
                case "toString" -> "sign";
                default -> defaultValue(method.getReturnType());
            });
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return Boolean.FALSE;
        }
        if (type == int.class) {
            return Integer.valueOf(0);
        }
        if (type == long.class) {
            return Long.valueOf(0L);
        }
        if (type == double.class) {
            return Double.valueOf(0.0D);
        }
        if (type == float.class) {
            return Float.valueOf(0.0F);
        }
        return null;
    }
}
