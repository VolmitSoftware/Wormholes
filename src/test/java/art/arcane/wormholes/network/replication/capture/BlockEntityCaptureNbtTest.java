package art.arcane.wormholes.network.replication.capture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.List;

import com.github.retrooper.packetevents.protocol.nbt.NBTCompound;

import org.bukkit.DyeColor;
import org.bukkit.Material;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.block.sign.SignSide;
import org.junit.jupiter.api.Test;

import art.arcane.wormholes.platform.WormholesPlatform;
import art.arcane.wormholes.render.blockentity.BlockEntityCapturer;
import art.arcane.wormholes.render.blockentity.BlockEntityNbt;
import art.arcane.wormholes.render.blockentity.BlockEntityNbtAssembler;
import art.arcane.wormholes.render.blockentity.BlockEntitySample;

final class BlockEntityCaptureNbtTest {
    @Test
    void aSignWithoutASnapshotAccessorIsAssembledFromItsLines() throws IOException {
        Sign sign = sign(new String[] {"hello", "world", "", ""}, new String[] {"back", "", "", ""}, true, DyeColor.RED);

        assertNull(WormholesPlatform.blockEntityNbt(sign), "a state without a snapshot NBT accessor yields no vanilla tag");
        NBTCompound assembled = BlockEntityNbtAssembler.assemble(sign);
        assertNotNull(assembled);
        NBTCompound front = assembled.getCompoundTagOrThrow("front_text");
        assertEquals("{\"text\":\"hello\"}", front.getStringListTagOrThrow("messages").getTag(0).getValue());
        assertEquals("{\"text\":\"world\"}", front.getStringListTagOrThrow("messages").getTag(1).getValue());
        assertEquals(4, front.getStringListTagOrThrow("messages").size());
        assertEquals("red", front.getStringTagValueOrThrow("color"));
        assertEquals(1, front.getNumberTagValueOrThrow("has_glow_text").intValue());
        assertEquals("{\"text\":\"back\"}", assembled.getCompoundTagOrThrow("back_text").getStringListTagOrThrow("messages").getTag(0).getValue());
        assertEquals(0, assembled.getNumberTagValueOrThrow("is_waxed").intValue());

        BlockEntitySample sample = BlockEntityCapturer.capture(sign, List.of("minecraft:sign"), false);
        assertNotNull(sample);
        assertEquals("minecraft:sign", sample.typeKey());
        NBTCompound decoded = BlockEntityNbt.decode(sample.nbt());
        assertTrue(decoded.contains("front_text"));
        assertEquals("bukkit-api", BlockEntityCapturer.activePath());
    }

    @Test
    void nonWhitelistedStatesAreNotCaptured() {
        Sign sign = sign(new String[] {"a", "b", "c", "d"}, new String[] {"", "", "", ""}, false, DyeColor.BLACK);
        assertNull(BlockEntityCapturer.capture(sign, List.of("minecraft:banner"), false));
    }

    @Test
    void theEncodedSampleRoundTripsThroughTheDiffPayload() throws IOException {
        Sign sign = sign(new String[] {"x", "", "", ""}, new String[] {"", "", "", ""}, false, DyeColor.BLACK);
        BlockEntitySample sample = BlockEntityCapturer.capture(sign, List.of("minecraft:sign"), false);
        byte[] payload = BlockEntityCapturer.encode(sample);
        BlockEntitySample decoded = BlockEntityCapturer.decode(payload);
        assertEquals(sample, decoded);
    }

    private static Sign sign(String[] frontLines, String[] backLines, boolean glowing, DyeColor color) {
        SignSide front = side(frontLines, glowing, color);
        SignSide back = side(backLines, false, DyeColor.BLACK);
        return (Sign) Proxy.newProxyInstance(Sign.class.getClassLoader(), new Class<?>[] {Sign.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getType" -> Material.OAK_SIGN;
                case "getSide" -> args[0] == Side.FRONT ? front : back;
                case "getLines" -> frontLines;
                case "isWaxed" -> Boolean.FALSE;
                case "isGlowingText" -> Boolean.valueOf(glowing);
                case "getColor" -> color;
                case "getX" -> Integer.valueOf(1);
                case "getY" -> Integer.valueOf(64);
                case "getZ" -> Integer.valueOf(2);
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == args[0]);
                case "toString" -> "sign";
                default -> defaultValue(method.getReturnType());
            });
    }

    private static SignSide side(String[] lines, boolean glowing, DyeColor color) {
        return (SignSide) Proxy.newProxyInstance(SignSide.class.getClassLoader(), new Class<?>[] {SignSide.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getLines" -> lines;
                case "getLine" -> lines[((Integer) args[0]).intValue()];
                case "isGlowingText" -> Boolean.valueOf(glowing);
                case "getColor" -> color;
                case "hashCode" -> Integer.valueOf(System.identityHashCode(proxy));
                case "equals" -> Boolean.valueOf(proxy == args[0]);
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
