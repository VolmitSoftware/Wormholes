package art.arcane.wormholes.modded.client;

import org.junit.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.AnnotationNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class PreparedChunkCacheTest {
    @Test
    public void onlyTheCurrentlyAttachedWorldPublishesMainRendererLightUpdates() throws IOException {
        MethodNode method = method("art/arcane/wormholes/modded/mixin/client/PreparedChunkCacheMixin", "wormholesPreparedLight");
        List<AbstractInsnNode> instructions = new ArrayList<>();
        List<Integer> opcodes = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() >= 0) {
                instructions.add(instruction);
                opcodes.add(instruction.getOpcode());
            }
        }
        assertEquals(List.of(Opcodes.ALOAD, Opcodes.GETFIELD, Opcodes.INVOKESTATIC, Opcodes.IFNE, Opcodes.ALOAD,
            Opcodes.INVOKEVIRTUAL, Opcodes.RETURN), opcodes);
        assertEquals(0, ((VarInsnNode) instructions.get(0)).var);
        FieldInsnNode owner = (FieldInsnNode) instructions.get(1);
        assertEquals("art/arcane/wormholes/modded/mixin/client/PreparedChunkCacheMixin", owner.owner);
        assertEquals("level", owner.name);
        MethodInsnNode active = (MethodInsnNode) instructions.get(2);
        assertEquals("art/arcane/wormholes/modded/client/WormholesClient", active.owner);
        assertEquals("activeLevel", active.name);
        assertEquals("(" + owner.desc + ")Z", active.desc);
        assertEquals(3, ((VarInsnNode) instructions.get(4)).var);
        MethodInsnNode cancel = (MethodInsnNode) instructions.get(5);
        assertEquals("org/spongepowered/asm/mixin/injection/callback/CallbackInfo", cancel.owner);
        assertEquals("cancel", cancel.name);
        AbstractInsnNode target = ((JumpInsnNode) instructions.get(3)).label;
        while (target.getOpcode() < 0) {
            target = target.getNext();
        }
        assertSame(instructions.getLast(), target);
    }

    @Test
    public void lightIsolationRunsBeforeTheVanillaRendererCallback() throws IOException {
        AnnotationNode injection = method("art/arcane/wormholes/modded/mixin/client/PreparedChunkCacheMixin", "wormholesPreparedLight").visibleAnnotations.getFirst();
        assertEquals("Lorg/spongepowered/asm/mixin/injection/Inject;", injection.desc);
        assertEquals(List.of("onLightUpdate"), value(injection, "method"));
        assertEquals(Boolean.TRUE, value(injection, "cancellable"));
        List<?> points = (List<?>) value(injection, "at");
        assertEquals(1, points.size());
        AnnotationNode point = (AnnotationNode) points.getFirst();
        assertEquals("Lorg/spongepowered/asm/mixin/injection/At;", point.desc);
        assertEquals("HEAD", value(point, "value"));
    }

    @Test
    public void noOpForgetPacketsCannotInvalidateColumnsThatWereNeverRemoved() throws IOException {
        MethodNode callback = method("art/arcane/wormholes/modded/mixin/client/PreparedChunkStorageMixin", "wormholes$unloaded");
        AnnotationNode injection = callback.visibleAnnotations.getFirst();
        assertEquals(List.of("replace", "drop"), value(injection, "method"));
        AnnotationNode point = (AnnotationNode) ((List<?>) value(injection, "at")).getFirst();
        assertEquals("INVOKE", value(point, "value"));
        assertEquals("Lnet/minecraft/client/multiplayer/ClientLevel;unload(Lnet/minecraft/world/level/chunk/LevelChunk;)V",
            value(point, "target"));
        for (String name : List.of("replace", "drop")) {
            MethodNode storage = method("net/minecraft/client/multiplayer/ClientChunkCache$Storage", name);
            int unloads = 0;
            for (AbstractInsnNode instruction : storage.instructions) {
                if (instruction instanceof MethodInsnNode call && call.owner.equals("net/minecraft/client/multiplayer/ClientLevel")
                    && call.name.equals("unload")) {
                    unloads++;
                }
            }
            assertEquals(1, unloads);
        }
        MethodNode vanilla = method("net/minecraft/client/multiplayer/ClientChunkCache", "drop");
        int removal = -1;
        for (AbstractInsnNode instruction : vanilla.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals("net/minecraft/client/multiplayer/ClientChunkCache$Storage")
                && call.name.equals("drop")) {
                removal = vanilla.instructions.indexOf(instruction);
            }
        }
        assertTrue(removal >= 0);
        int noOpBranches = 0;
        for (AbstractInsnNode instruction : vanilla.instructions) {
            if (instruction.getOpcode() == Opcodes.RETURN && vanilla.instructions.indexOf(instruction) < removal) {
                noOpBranches++;
            }
            if (instruction instanceof JumpInsnNode branch && instruction.getOpcode() == Opcodes.IFEQ
                && vanilla.instructions.indexOf(instruction) < removal && vanilla.instructions.indexOf(branch.label) > removal) {
                noOpBranches++;
            }
        }
        assertEquals(2, noOpBranches);
    }

    private static MethodNode method(String owner, String name) throws IOException {
        try (InputStream bytes = PreparedChunkCacheTest.class.getResourceAsStream("/" + owner + ".class")) {
            assertNotNull(bytes);
            ClassNode type = new ClassNode();
            new ClassReader(bytes).accept(type, 0);
            for (MethodNode method : type.methods) {
                if (method.name.equals(name)) {
                    return method;
                }
            }
        }
        throw new AssertionError(name);
    }

    private static Object value(AnnotationNode annotation, String key) {
        for (int index = 0; index < annotation.values.size(); index += 2) {
            if (annotation.values.get(index).equals(key)) {
                return annotation.values.get(index + 1);
            }
        }
        throw new AssertionError("Missing annotation value " + key);
    }
}
