package art.arcane.wormholes.modded.client;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class PreparedTravelChunkCacheTest {
    @Test
    public void retentionRunsAfterMainThreadAdmissionInsideTheDeferredVanillaBody() throws IOException {
        MethodNode callback = method("art/arcane/wormholes/modded/mixin/client/PreparedTravelChunkCacheMixin", "wormholes$rememberChunk");
        AnnotationNode injection = callback.visibleAnnotations.getFirst();
        assertEquals("Lorg/spongepowered/asm/mixin/injection/Inject;", injection.desc);
        assertEquals(List.of("handleLevelChunkWithLight"), value(injection, "method"));
        AnnotationNode point = (AnnotationNode) ((List<?>) value(injection, "at")).getFirst();
        assertEquals("INVOKE", value(point, "value"));
        assertEquals("Lnet/minecraft/network/protocol/PacketUtils;ensureRunningOnSameThread(Lnet/minecraft/network/protocol/Packet;Lnet/minecraft/network/PacketListener;Lnet/minecraft/network/PacketProcessor;)V",
            value(point, "target"));
        assertEquals("AFTER", ((String[]) value(point, "shift"))[1]);
        MethodNode outer = method("art/arcane/wormholes/modded/mixin/client/PreparedTravelWorldPacketsMixin", "wormholes$sourceWorld");
        AnnotationNode wrap = outer.visibleAnnotations.getFirst();
        assertEquals("Lcom/llamalad7/mixinextras/injector/wrapmethod/WrapMethod;", wrap.desc);
        assertTrue(((List<?>) value(wrap, "method")).contains("handleLevelChunkWithLight"));
        List<MethodInsnNode> calls = calls(outer);
        assertTrue(index(calls, "deferWorldPacket") < index(calls, "call"));
    }

    @Test
    public void physicalListenerLevelAndOriginalPacketArePassedWithoutReadingPredictedMinecraftLevel() throws IOException {
        MethodNode callback = method("art/arcane/wormholes/modded/mixin/client/PreparedTravelChunkCacheMixin", "wormholes$rememberChunk");
        List<MethodInsnNode> calls = calls(callback);
        MethodInsnNode level = calls.get(index(calls, "getLevel"));
        assertEquals("net/minecraft/client/multiplayer/ClientPacketListener", level.owner);
        assertEquals("()Lnet/minecraft/client/multiplayer/ClientLevel;", level.desc);
        MethodInsnNode retain = calls.get(index(calls, "rememberNativeChunk"));
        assertEquals("(Lnet/minecraft/client/multiplayer/ClientLevel;Lnet/minecraft/network/protocol/game/ClientboundLevelChunkWithLightPacket;)V", retain.desc);
        assertTrue(index(calls, "getLevel") < index(calls, "rememberNativeChunk"));
        for (AbstractInsnNode instruction : callback.instructions) {
            if (instruction instanceof FieldInsnNode field) {
                assertTrue(!field.owner.equals("net/minecraft/client/Minecraft") || !field.name.equals("level"));
            }
        }
    }

    @Test
    public void installedVanillaBoundaryPrecedesChunkAndLightConsumption() throws IOException {
        MethodNode vanilla = method("net/minecraft/client/multiplayer/ClientPacketListener", "handleLevelChunkWithLight");
        List<MethodInsnNode> calls = calls(vanilla);
        assertTrue(index(calls, "ensureRunningOnSameThread") < index(calls, "replaceWithPacketData"));
        assertTrue(index(calls, "ensureRunningOnSameThread") < index(calls, "lightData"));
    }

    private static List<MethodInsnNode> calls(MethodNode method) {
        List<MethodInsnNode> result = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call) {
                result.add(call);
            }
        }
        return result;
    }

    private static int index(List<MethodInsnNode> calls, String name) {
        for (int index = 0; index < calls.size(); index++) {
            if (calls.get(index).name.equals(name)) {
                return index;
            }
        }
        throw new AssertionError(name);
    }

    private static MethodNode method(String owner, String name) throws IOException {
        try (InputStream bytes = PreparedTravelChunkCacheTest.class.getResourceAsStream("/" + owner + ".class")) {
            assertNotNull(bytes);
            ClassNode type = new ClassNode();
            new ClassReader(bytes).accept(type, 0);
            for (MethodNode method : type.methods) {
                if (method.name.equals(name)) {
                    return method;
                }
            }
        }
        throw new AssertionError(owner + "." + name);
    }

    private static Object value(AnnotationNode annotation, String key) {
        for (int index = 0; index < annotation.values.size(); index += 2) {
            if (annotation.values.get(index).equals(key)) {
                return annotation.values.get(index + 1);
            }
        }
        throw new AssertionError(key);
    }
}
