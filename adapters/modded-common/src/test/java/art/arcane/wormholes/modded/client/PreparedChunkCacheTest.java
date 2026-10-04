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

public class PreparedChunkCacheTest {
    @Test
    public void onlyTheCurrentlyAttachedWorldPublishesMainRendererLightUpdates() throws IOException {
        MethodNode method = callback();
        List<AbstractInsnNode> instructions = new ArrayList<>();
        List<Integer> opcodes = new ArrayList<>();
        for (AbstractInsnNode instruction : method.instructions) {
            if (instruction.getOpcode() >= 0) {
                instructions.add(instruction);
                opcodes.add(instruction.getOpcode());
            }
        }
        assertEquals(List.of(Opcodes.INVOKESTATIC, Opcodes.GETFIELD, Opcodes.ALOAD, Opcodes.GETFIELD,
            Opcodes.IF_ACMPEQ, Opcodes.ALOAD, Opcodes.INVOKEVIRTUAL, Opcodes.RETURN), opcodes);
        MethodInsnNode current = (MethodInsnNode) instructions.get(0);
        assertEquals("net/minecraft/client/Minecraft", current.owner);
        assertEquals("getInstance", current.name);
        FieldInsnNode attached = (FieldInsnNode) instructions.get(1);
        assertEquals("net/minecraft/client/Minecraft", attached.owner);
        assertEquals("level", attached.name);
        assertEquals(0, ((VarInsnNode) instructions.get(2)).var);
        FieldInsnNode owner = (FieldInsnNode) instructions.get(3);
        assertEquals("art/arcane/wormholes/modded/mixin/client/PreparedChunkCacheMixin", owner.owner);
        assertEquals("level", owner.name);
        assertEquals(attached.desc, owner.desc);
        assertEquals(3, ((VarInsnNode) instructions.get(5)).var);
        MethodInsnNode cancel = (MethodInsnNode) instructions.get(6);
        assertEquals("org/spongepowered/asm/mixin/injection/callback/CallbackInfo", cancel.owner);
        assertEquals("cancel", cancel.name);
        AbstractInsnNode target = ((JumpInsnNode) instructions.get(4)).label;
        while (target.getOpcode() < 0) {
            target = target.getNext();
        }
        assertSame(instructions.getLast(), target);
    }

    @Test
    public void lightIsolationRunsBeforeTheVanillaRendererCallback() throws IOException {
        AnnotationNode injection = callback().visibleAnnotations.getFirst();
        assertEquals("Lorg/spongepowered/asm/mixin/injection/Inject;", injection.desc);
        assertEquals(List.of("onLightUpdate"), value(injection, "method"));
        assertEquals(Boolean.TRUE, value(injection, "cancellable"));
        List<?> points = (List<?>) value(injection, "at");
        assertEquals(1, points.size());
        AnnotationNode point = (AnnotationNode) points.getFirst();
        assertEquals("Lorg/spongepowered/asm/mixin/injection/At;", point.desc);
        assertEquals("HEAD", value(point, "value"));
    }

    private static MethodNode callback() throws IOException {
        String path = "/art/arcane/wormholes/modded/mixin/client/PreparedChunkCacheMixin.class";
        try (InputStream bytes = PreparedChunkCacheTest.class.getResourceAsStream(path)) {
            assertNotNull(bytes);
            ClassNode type = new ClassNode();
            new ClassReader(bytes).accept(type, 0);
            for (MethodNode method : type.methods) {
                if (method.name.equals("wormholesPreparedLight")) {
                    return method;
                }
            }
        }
        throw new AssertionError("Prepared light callback is missing");
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
